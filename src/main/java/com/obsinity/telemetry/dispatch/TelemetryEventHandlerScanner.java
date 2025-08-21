package com.obsinity.telemetry.dispatch;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;

import io.opentelemetry.api.trace.SpanKind;
import com.obsinity.telemetry.annotations.EventReceiver;
import com.obsinity.telemetry.annotations.GlobalFlowFallback;
import com.obsinity.telemetry.annotations.OnEventLifecycle;
import com.obsinity.telemetry.annotations.OnEventScope;
import com.obsinity.telemetry.annotations.OnFlowCompleted;
import com.obsinity.telemetry.annotations.OnFlowFailure;
import com.obsinity.telemetry.annotations.OnFlowNotMatched;
import com.obsinity.telemetry.annotations.OnFlowStarted;
import com.obsinity.telemetry.annotations.OnFlowSuccess;
import com.obsinity.telemetry.annotations.OnOutcome;
import com.obsinity.telemetry.annotations.Outcome;
import com.obsinity.telemetry.annotations.PullAllContextValues;
import com.obsinity.telemetry.annotations.PullAttribute;
import com.obsinity.telemetry.annotations.PullContextValue;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;
import com.obsinity.telemetry.processor.TelemetryProcessorSupport;

/**
 * Scans Spring beans annotated with {@link EventReceiver} (or {@link GlobalFlowFallback}) and produces a registry of
 * {@link HandlerGroup}s for flow-centric dispatch.
 *
 * <p>Component filters: - {@code @OnEventScope} (repeatable): prefix filter (OR across values) -
 * {@code @OnEventLifecycle} (repeatable): lifecycle filter (OR across values)
 *
 * <p>Handler semantics: - @OnFlowStarted(name) -> phase=FLOW_STARTED only (no outcome). If @OnEventLifecycle present,
 * must include FLOW_STARTED. - @OnFlowSuccess(name) -> (FLOW_FINISHED, SUCCESS). If @OnEventLifecycle present, must
 * include FLOW_FINISHED. - @OnFlowFailure(name) -> (FLOW_FINISHED, FAILURE). If @OnEventLifecycle present, must include
 * FLOW_FINISHED. - @OnFlowCompleted(name) -> lifecycle inferred from parameters: * List<TelemetryHolder> param ->
 * phase=ROOT_FLOW_FINISHED * TelemetryHolder param only -> phase=FLOW_FINISHED * both/none -> error Outcomes
 * from @OnOutcome (default = both SUCCESS & FAILURE). If method also declares @OnEventLifecycle, it must include the
 * inferred phase. - @OnFlowNotMatched -> component/global unmatched; lifecycle[] may be specified on the annotation
 *
 * <p>Batch binding: - Methods declaring a parameter of type List<TelemetryHolder> are valid only for
 * ROOT_FLOW_FINISHED.
 */
@Configuration
public class TelemetryEventHandlerScanner {

	private final ListableBeanFactory beanFactory;
	private final TelemetryProcessorSupport support;

	public TelemetryEventHandlerScanner(ListableBeanFactory beanFactory, TelemetryProcessorSupport support) {
		this.beanFactory = beanFactory;
		this.support = support;
	}

	@Bean
	public List<HandlerGroup> handlerGroups() {
		// Gather receivers
		Map<String, Object> receivers = new LinkedHashMap<>();
		receivers.putAll(beanFactory.getBeansWithAnnotation(EventReceiver.class));
		receivers.putAll(beanFactory.getBeansWithAnnotation(GlobalFlowFallback.class));

		List<HandlerGroup> groups = new ArrayList<>(receivers.size());

		for (Map.Entry<String, Object> entry : receivers.entrySet()) {
			Object bean = entry.getValue();
			Class<?> userClass = AopUtils.getTargetClass(bean);
			String componentName = userClass.getSimpleName();

			boolean isGlobalFallback = AnnotationUtils.findAnnotation(userClass, GlobalFlowFallback.class) != null;

			// Component scope: prefixes + lifecycles
			List<String> prefixes = readRepeatable(userClass, OnEventScope.class).stream()
					.map(ann -> {
						Object v = AnnotationUtils.getValue(ann, "value");
						if (v == null || String.valueOf(v).isBlank()) {
							v = AnnotationUtils.getValue(ann, "prefix");
						}
						return (v == null) ? "" : String.valueOf(v);
					})
					.filter(s -> s != null && !s.isBlank())
					.toList();

			Set<Lifecycle> lifecycleSet = readRepeatable(userClass, OnEventLifecycle.class).stream()
					.flatMap(a -> {
						Object v = AnnotationUtils.getValue(a, "value");
						if (v instanceof Lifecycle[] arr) return Arrays.stream(arr);
						if (v instanceof Lifecycle lc) return Arrays.stream(new Lifecycle[] {lc});
						return Arrays.<Lifecycle>stream(new Lifecycle[0]);
					})
					.collect(Collectors.toCollection(LinkedHashSet::new));

			HandlerGroup.Scope scope = HandlerGroup.Scope.of(
					prefixes.toArray(String[]::new),
					lifecycleSet.isEmpty() ? null : lifecycleSet.toArray(new Lifecycle[0]));

			HandlerGroup group = new HandlerGroup(componentName, scope);

			// Guard (exactName + phase + outcome-bucket [+ failureType]) within this component
			Map<String, List<String>> dupGuard = new LinkedHashMap<>();

			// ---------- OnFlowStarted (phase only; no outcomes) ----------
			for (Method m : methodsAnnotated(userClass, OnFlowStarted.class)) {
				String name = readAliasedName(m, OnFlowStarted.class);
				validateNonBlank(userClass, m, "@OnFlowStarted.name", name);
				validateMethodLifecycleAnnotationIncludes(userClass, m, Lifecycle.FLOW_STARTED, "@OnFlowStarted");

				// Use the group's "completed" bucket for FLOW_STARTED only (no outcome axis)
				registerStartedPhase(group, bean, userClass, m, name, dupGuard);
			}

			// ---------- OnFlowSuccess (FLOW_FINISHED, SUCCESS only) ----------
			for (Method m : methodsAnnotated(userClass, OnFlowSuccess.class)) {
				String name = readAliasedName(m, OnFlowSuccess.class);
				validateNonBlank(userClass, m, "@OnFlowSuccess.name", name);
				validateMethodLifecycleAnnotationIncludes(userClass, m, Lifecycle.FLOW_FINISHED, "@OnFlowSuccess");

				registerOutcome(group, bean, userClass, m, name, Lifecycle.FLOW_FINISHED, Outcome.SUCCESS, dupGuard);
			}

			// ---------- OnFlowFailure (FLOW_FINISHED, FAILURE only) ----------
			for (Method m : methodsAnnotated(userClass, OnFlowFailure.class)) {
				String name = readAliasedName(m, OnFlowFailure.class);
				validateNonBlank(userClass, m, "@OnFlowFailure.name", name);
				validateMethodLifecycleAnnotationIncludes(userClass, m, Lifecycle.FLOW_FINISHED, "@OnFlowFailure");

				registerOutcome(group, bean, userClass, m, name, Lifecycle.FLOW_FINISHED, Outcome.FAILURE, dupGuard);
			}

			// ---------- OnFlowCompleted (infer lifecycle; outcomes from @OnOutcome or both) ----------
			for (Method m : methodsAnnotated(userClass, OnFlowCompleted.class)) {
				String name = readAliasedName(m, OnFlowCompleted.class);
				validateNonBlank(userClass, m, "@OnFlowCompleted.name", name);

				InferredLifecycle inf = inferLifecycleFromParams(userClass, m);
				validateMethodLifecycleAnnotationMatches(userClass, m, inf.phase);

				Set<Outcome> outcomes = declaredOutcomesOrBoth(m);
				for (Outcome oc : outcomes) {
					registerOutcome(group, bean, userClass, m, name, inf.phase, oc, dupGuard);
				}
			}

// ---------- Component/global not matched ----------
			for (Method m : methodsAnnotated(userClass, OnFlowNotMatched.class)) {
				validateBatchParamIfPresent(userClass, m,
					new Lifecycle[]{Lifecycle.ROOT_FLOW_FINISHED}, false, "@OnFlowNotMatched");

				Handler h = buildHandler(bean, userClass, m, null);

				// lifecyclesDeclaredOnMethod (may be null/empty)
				Lifecycle[] lifecyclesOnMethod = readLifecycleArray(m, OnFlowNotMatched.class);

				// lifecyclesDeclaredOnClass (via @OnEventLifecycle at class level)
				Set<Lifecycle> classLifecycles = readRepeatable(userClass, OnEventLifecycle.class).stream()
					.flatMap(a -> {
						Object v = org.springframework.core.annotation.AnnotationUtils.getValue(a, "value");
						if (v instanceof Lifecycle[] arr) return Arrays.stream(arr);
						if (v instanceof Lifecycle lc) return Arrays.stream(new Lifecycle[]{lc});
						return Arrays.<Lifecycle>stream(new Lifecycle[0]);
					})
					.collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

				// Compute toRegister:
				// - If the method declared lifecycles → use them.
				// - Else, if the class declared lifecycles → use those.
				// - Else default to FLOW_FINISHED only (not all phases).
				java.util.LinkedHashSet<Lifecycle> toRegister = new java.util.LinkedHashSet<>();
				if (lifecyclesOnMethod != null && lifecyclesOnMethod.length > 0) {
					toRegister.addAll(java.util.Arrays.asList(lifecyclesOnMethod));
				} else if (!classLifecycles.isEmpty()) {
					toRegister.addAll(classLifecycles);
				} else {
					toRegister.add(Lifecycle.FLOW_FINISHED);
				}

				// Register in the proper bucket (component vs global)
				for (Lifecycle p : toRegister) {
					if (isGlobalFallback) {
						group.registerGlobalUnmatchedCompleted(p, h);   // “completed” = both outcomes
					} else {
						group.registerComponentUnmatchedCompleted(p, h);
					}
				}
			}

			validateNoExactDuplicates(userClass, dupGuard);
			groups.add(group);
		}

		groups.sort(Comparator.comparing(HandlerGroup::getComponentName));
		return groups;
	}

	/* =========================
	Registration helpers
	========================= */

	private void registerStartedPhase(
			HandlerGroup group,
			Object bean,
			Class<?> userClass,
			Method m,
			String exactName,
			Map<String, List<String>> dupGuard) {
		// Validate batch param constraints (only ROOT_FLOW_FINISHED allows List<TelemetryHolder>)
		validateBatchParamIfPresent(
				userClass, m, new Lifecycle[] {Lifecycle.ROOT_FLOW_FINISHED}, false, "flow handler");

		Handler h = buildHandler(bean, userClass, m, exactName);
		// Use group's "completed" bucket for FLOW_STARTED only (acts as no-outcome list)
		String key = dupKey(exactName, Lifecycle.FLOW_STARTED, "STARTED");
		dupGuard.computeIfAbsent(key, k -> new ArrayList<>()).add(userClass.getSimpleName() + "#" + m.getName());
		group.registerFlowCompleted(exactName, Lifecycle.FLOW_STARTED, h);
	}

	private void registerOutcome(
			HandlerGroup group,
			Object bean,
			Class<?> userClass,
			Method m,
			String exactName,
			Lifecycle phase,
			Outcome outcome,
			Map<String, List<String>> dupGuard) {
		// Validate batch param constraints (only ROOT_FLOW_FINISHED allows List<TelemetryHolder>)
		validateBatchParamIfPresent(
				userClass, m, new Lifecycle[] {Lifecycle.ROOT_FLOW_FINISHED}, false, "flow handler");

		Handler h = buildHandler(bean, userClass, m, exactName);

		String key = dupKey(exactName, phase, outcome.name());
		// For FAILURE, include the throwable specificity key to distinguish slots; generic/unbound collapse to "*"
		if (outcome == Outcome.FAILURE) {
			key = key + "|" + failureTypeKey(h.failureThrowableType());
		}
		dupGuard.computeIfAbsent(key, k -> new ArrayList<>()).add(userClass.getSimpleName() + "#" + m.getName());

		if (outcome == Outcome.SUCCESS) {
			group.registerFlowSuccess(exactName, phase, h);
		} else if (outcome == Outcome.FAILURE) {
			group.registerFlowFailure(exactName, phase, h);
		}
	}

	private Handler buildHandler(Object bean, Class<?> userClass, Method m, String exactNameOrNull) {
		BitSet lifecycleMask = null; // not used in flow-centric routing
		BitSet kindMask = null;

		List<ParamBinder> binders = buildParamBinders(m);
		String id = userClass.getSimpleName() + "#" + m.getName();

		// Detect a bound throwable type (prefer @BindEventThrowable, else any Throwable param)
		Class<? extends Throwable> boundThrowable = detectBoundThrowableType(m);
		List<Class<? extends Throwable>> throwableTypes =
				(boundThrowable == null) ? List.of() : List.of(boundThrowable);

		return new Handler(
				bean,
				m,
				exactNameOrNull,
				lifecycleMask,
				kindMask,
				throwableTypes, // used for failure specificity
				true, // includeSubclasses
				null, // messagePattern
				null, // causeTypeOrNull
				binders,
				Set.of(), // required attributes
				id);
	}

	/* =========================
	Param/lifecycle inference & validation
	========================= */

	private record InferredLifecycle(Lifecycle phase, boolean usesBatch) {}

	/**
	 * Infer lifecycle for @OnFlowCompleted from method parameters: - List<TelemetryHolder> -> ROOT_FLOW_FINISHED -
	 * TelemetryHolder (and no List<TelemetryHolder>) -> FLOW_FINISHED - Otherwise or both present -> error
	 */
	private InferredLifecycle inferLifecycleFromParams(Class<?> userClass, Method m) {
		boolean hasHolder = false;
		boolean hasBatch = false;

		Class<?>[] pts = m.getParameterTypes();
		Type[] gpts = m.getGenericParameterTypes();

		for (int i = 0; i < pts.length; i++) {
			Class<?> pt = pts[i];

			if (TelemetryHolder.class.isAssignableFrom(pt)) {
				hasHolder = true;
			}
			if (List.class.isAssignableFrom(pt) && gpts[i] instanceof ParameterizedType p) {
				Type[] args = p.getActualTypeArguments();
				if (args.length == 1 && args[0] instanceof Class<?> c && TelemetryHolder.class.isAssignableFrom(c)) {
					hasBatch = true;
				}
			}
		}

		if (hasBatch && hasHolder) {
			throw new IllegalStateException(errPrefix(userClass, m)
					+ "@OnFlowCompleted cannot declare BOTH TelemetryHolder and List<TelemetryHolder> parameters");
		}
		if (hasBatch) {
			return new InferredLifecycle(Lifecycle.ROOT_FLOW_FINISHED, true);
		}
		if (hasHolder) {
			return new InferredLifecycle(Lifecycle.FLOW_FINISHED, false);
		}

		throw new IllegalStateException(
				errPrefix(userClass, m)
						+ "@OnFlowCompleted must declare either TelemetryHolder OR List<TelemetryHolder> to determine lifecycle");
	}

	/** If method carries @OnEventLifecycle, ensure it includes the inferred phase (for @OnFlowCompleted). */
	private void validateMethodLifecycleAnnotationMatches(Class<?> userClass, Method m, Lifecycle inferred) {
		List<OnEventLifecycle> anns = readRepeatable(m, OnEventLifecycle.class);
		if (anns.isEmpty()) return;

		Set<Lifecycle> declared = new LinkedHashSet<>();
		for (OnEventLifecycle a : anns) {
			Object v = AnnotationUtils.getValue(a, "value");
			if (v instanceof Lifecycle[] arr) declared.addAll(Arrays.asList(arr));
			else if (v instanceof Lifecycle lc) declared.add(lc);
		}
		if (!declared.contains(inferred)) {
			throw new IllegalStateException(errPrefix(userClass, m)
					+ "@OnEventLifecycle must include the inferred lifecycle " + inferred
					+ " for @OnFlowCompleted based on parameters");
		}
	}

	/**
	 * For fixed-phase handlers (@OnFlowStarted/@OnFlowSuccess/@OnFlowFailure), if method has @OnEventLifecycle, ensure
	 * it includes the required phase.
	 */
	private void validateMethodLifecycleAnnotationIncludes(
			Class<?> userClass, Method m, Lifecycle required, String where) {
		List<OnEventLifecycle> anns = readRepeatable(m, OnEventLifecycle.class);
		if (anns.isEmpty()) return;

		Set<Lifecycle> declared = new LinkedHashSet<>();
		for (OnEventLifecycle a : anns) {
			Object v = AnnotationUtils.getValue(a, "value");
			if (v instanceof Lifecycle[] arr) declared.addAll(Arrays.asList(arr));
			else if (v instanceof Lifecycle lc) declared.add(lc);
		}
		if (!declared.contains(required)) {
			throw new IllegalStateException(errPrefix(userClass, m) + where
					+ " has @OnEventLifecycle that does not include required lifecycle " + required);
		}
	}

	private static Set<Outcome> declaredOutcomesOrBoth(Method m) {
		List<OnOutcome> outs = readRepeatable(m, OnOutcome.class);
		if (outs.isEmpty()) {
			return EnumSet.of(Outcome.SUCCESS, Outcome.FAILURE);
		}
		EnumSet<Outcome> set = EnumSet.noneOf(Outcome.class);
		for (OnOutcome o : outs) {
			Outcome val = (Outcome) AnnotationUtils.getValue(o, "value");
			if (val != null) set.add(val);
		}
		if (set.isEmpty()) set = EnumSet.of(Outcome.SUCCESS, Outcome.FAILURE);
		return set;
	}

	/* =========================
	Param binding (annotation-aware)
	========================= */

	private List<ParamBinder> buildParamBinders(Method m) {
		Class<?>[] pts = m.getParameterTypes();
		Type[] gpts = m.getGenericParameterTypes();
		Annotation[][] pann = m.getParameterAnnotations();

		List<ParamBinder> binders = new ArrayList<>(pts.length);
		for (int i = 0; i < pts.length; i++) {
			Class<?> pt = pts[i];
			Annotation[] anns = pann[i];

			validatePullAnnotationExclusivity(m, i, anns);

			ParamBinder b = buildAnnotationBinder(pt, anns);

			// Special case: List<TelemetryHolder> batch for ROOT_FLOW_FINISHED
			if (b == null && List.class.isAssignableFrom(pt) && gpts[i] instanceof ParameterizedType ptype) {
				Type[] args = ptype.getActualTypeArguments();
				if (args.length == 1 && args[0] instanceof Class<?> c && TelemetryHolder.class.isAssignableFrom(c)) {
					b = (holder, phase, error) -> {
						if (phase != Lifecycle.ROOT_FLOW_FINISHED) return null;
						try {
							List<TelemetryHolder> batch = support.getBatch();
							return (batch == null || batch.isEmpty()) ? null : batch;
						} catch (Throwable t) {
							return null;
						}
					};
				}
			}

			// Defaults
			if (b == null) {
				if (TelemetryHolder.class.isAssignableFrom(pt)) {
					b = (holder, phase, error) -> holder;
				} else if (Lifecycle.class.isAssignableFrom(pt)) {
					b = (holder, phase, error) -> phase;
				} else if (Throwable.class.isAssignableFrom(pt)) {
					b = (holder, phase, error) -> error;
				} else if (SpanKind.class.isAssignableFrom(pt)) {
					b = (holder, phase, error) ->
							(holder == null ? null : (holder.kind() == null ? SpanKind.INTERNAL : holder.kind()));
				} else {
					b = (holder, phase, error) -> null;
				}
			}
			binders.add(b);
		}
		return binders;
	}

	private static ParamBinder buildAnnotationBinder(Class<?> pt, Annotation[] anns) {
		if (anns == null || anns.length == 0) return null;

		for (Annotation raw : anns) {
			Class<? extends Annotation> at = raw.annotationType();

			MergedAnnotation<?> ma = MergedAnnotations.from(raw).get(at);
			// @PullAttribute("key") / alias "name"
			if (raw instanceof PullAttribute) {
				String key = strOrBlank(ma.getValue("value").orElse(null));
				if (key.isBlank()) key = strOrBlank(ma.getValue("name").orElse(null));
				final String fkey = key;
				if (!fkey.isBlank()) {
					return (holder, phase, error) -> {
						if (holder == null) return null;
						var attrs = holder.attributes();
						Object v = (attrs == null) ? null : attrs.map().get(fkey);
						return castIfPossible(pt, v);
					};
				}
				continue;
			}
			// @PullContextValue("key") / alias "name"
			if (raw instanceof PullContextValue) {
				String key = strOrBlank(ma.getValue("value").orElse(null));
				if (key.isBlank()) key = strOrBlank(ma.getValue("name").orElse(null));
				final String fkey = key;
				if (!fkey.isBlank()) {
					return (holder, phase, error) -> {
						if (holder == null) return null;
						Map<String, Object> ctx = holder.getEventContext();
						Object v = (ctx == null) ? null : ctx.get(fkey);
						return castIfPossible(pt, v);
					};
				}
				continue;
			}
			// @PullAllContextValues — inject Map
			if (raw instanceof PullAllContextValues) {
				if (Map.class.isAssignableFrom(pt)) {
					return (holder, phase, error) -> {
						if (holder == null) return Map.of();
						Map<String, Object> ctx = holder.getEventContext();
						return (ctx == null) ? Map.of() : new java.util.LinkedHashMap<>(ctx);
					};
				}
				return (h, p, e) -> null;
			}
		}
		return null;
	}

	private static Object castIfPossible(Class<?> target, Object value) {
		if (value == null) return null;
		if (target.isInstance(value)) return value;
		if (target == Integer.class && value instanceof Number n) return n.intValue();
		if (target == Long.class && value instanceof Number n) return n.longValue();
		if (target == Double.class && value instanceof Number n) return n.doubleValue();
		if (target == Float.class && value instanceof Number n) return n.floatValue();
		return null;
	}

	/* =========================
	Validation helpers
	========================= */

	private static void validateNonBlank(Class<?> userClass, Method m, String field, String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(errPrefix(userClass, m) + field + " must be non-blank");
		}
	}

	private static void validateNoExactDuplicates(Class<?> userClass, Map<String, List<String>> dupGuard) {
		List<String> errors = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : dupGuard.entrySet()) {
			if (e.getValue().size() > 1) {
				errors.add(String.format("slot [%s] claimed by %s", e.getKey(), e.getValue()));
			}
		}
		if (!errors.isEmpty()) {
			throw new IllegalStateException("Invalid handler configuration for component " + userClass.getSimpleName()
					+ ": exact duplicate handler registrations -> " + errors);
		}
	}

	private static void validateBatchParamIfPresent(
			Class<?> userClass, Method m, Lifecycle[] lifecycles, boolean unused, String where) {
		if (!declaresRootBatchParam(m)) return;

		boolean hasRoot = false;
		if (lifecycles != null && lifecycles.length > 0) {
			for (Lifecycle lc : lifecycles) {
				if (lc == Lifecycle.ROOT_FLOW_FINISHED) {
					hasRoot = true;
					break;
				}
			}
		}
		if (!hasRoot) {
			throw new IllegalStateException(errPrefix(userClass, m) + where
					+ " declares List<TelemetryHolder> but does not include lifecycle=ROOT_FLOW_FINISHED");
		}
		if (countRootBatchParams(m) > 1) {
			throw new IllegalStateException(
					errPrefix(userClass, m) + "at most one List<TelemetryHolder> parameter is allowed");
		}
	}

	private static void validatePullAnnotationExclusivity(Method m, int paramIndex, Annotation[] anns) {
		if (anns == null || anns.length == 0) return;
		int pulls = 0;
		boolean hasAllCtx = false;

		for (Annotation a : anns) {
			if (a instanceof PullAttribute) pulls++;
			if (a instanceof PullContextValue) pulls++;
			if (a instanceof PullAllContextValues) {
				pulls++;
				hasAllCtx = true;
			}
		}

		if (pulls > 1) {
			throw new IllegalStateException(String.format(
					"%sParameter %d mixes multiple Pull* annotations on method %s#%s",
					errPrefix(m.getDeclaringClass(), m),
					paramIndex,
					m.getDeclaringClass().getSimpleName(),
					m.getName()));
		}

		if (hasAllCtx) {
			Class<?> pt = m.getParameterTypes()[paramIndex];
			if (!Map.class.isAssignableFrom(pt)) {
				throw new IllegalStateException(errPrefix(m.getDeclaringClass(), m)
						+ "@PullAllContextValues requires parameter type assignable to Map");
			}
		}
	}

	private static String errPrefix(Class<?> userClass, Method m) {
		return "Invalid handler [" + userClass.getSimpleName() + "#" + m.getName() + "]: ";
	}

	/* =========================
	Utils
	========================= */

	private static List<Method> methodsAnnotated(Class<?> c, Class<? extends Annotation> ann) {
		return Arrays.stream(c.getMethods())
				.filter(m -> m.isAnnotationPresent(ann))
				.collect(Collectors.toList());
	}

	private static String readAliasedName(Method m, Class<? extends Annotation> annotationType) {
		MergedAnnotation<?> ma = MergedAnnotations.from(m).get(annotationType);
		if (ma.isPresent()) {
			String name = ma.getString("name");
			if (name == null || name.isBlank()) {
				name = ma.getString("value");
			}
			return name;
		}
		Annotation ann = AnnotationUtils.findAnnotation(m, annotationType);
		if (ann != null) {
			Object v = AnnotationUtils.getValue(ann, "name");
			if (v == null || String.valueOf(v).isBlank()) v = AnnotationUtils.getValue(ann, "value");
			return (v == null) ? null : String.valueOf(v);
		}
		return null;
	}

	// replace the whole method
	private static Lifecycle[] readLifecycleArray(Method m, Class<? extends Annotation> annotationType) {
		MergedAnnotation<?> ma = MergedAnnotations.from(m).get(annotationType);
		if (ma.isPresent()) {
			try {
				// Spring API: use getValue(name, type) -> Optional<T>
				java.util.Optional<Lifecycle[]> opt = ma.getValue("lifecycle", Lifecycle[].class);
				if (opt.isPresent()) return opt.get();
			} catch (Exception ignored) {
			}
		}
		Annotation ann = AnnotationUtils.findAnnotation(m, annotationType);
		if (ann != null) {
			Object v = AnnotationUtils.getValue(ann, "lifecycle");
			if (v instanceof Lifecycle[] arr) return arr;
		}
		return null;
	}

	private static <A extends Annotation> List<A> readRepeatable(Class<?> element, Class<A> type) {
		return MergedAnnotations.from(element).stream(type)
				.map(MergedAnnotation::synthesize)
				.toList();
	}

	private static <A extends Annotation> List<A> readRepeatable(Method element, Class<A> type) {
		return MergedAnnotations.from(element).stream(type)
				.map(MergedAnnotation::synthesize)
				.toList();
	}

	private static boolean declaresRootBatchParam(Method m) {
		return countRootBatchParams(m) > 0;
	}

	private static int countRootBatchParams(Method m) {
		Class<?>[] pts = m.getParameterTypes();
		Type[] gpts = m.getGenericParameterTypes();
		int count = 0;
		for (int i = 0; i < pts.length; i++) {
			if (List.class.isAssignableFrom(pts[i]) && gpts[i] instanceof ParameterizedType p) {
				Type[] args = p.getActualTypeArguments();
				if (args.length == 1 && args[0] instanceof Class<?> c && TelemetryHolder.class.isAssignableFrom(c)) {
					count++;
				}
			}
		}
		return count;
	}

	private static String dupKey(String name, Lifecycle phase, String bucket) {
		return name + "|" + phase.name() + "|" + bucket;
	}

	private static String failureTypeKey(Class<? extends Throwable> t) {
		if (t == null) return "*";
		if (t == Throwable.class || t == Exception.class) return "*";
		return t.getName();
	}

	private static String strOrBlank(Object v) {
		if (v == null) return "";
		String s = String.valueOf(v);
		return (s == null || s.isBlank()) ? "" : s;
	}

	/* =========================
	Throwable binding discovery
	========================= */

	@SuppressWarnings("unchecked")
	private static Class<? extends Throwable> detectBoundThrowableType(Method m) {
		Annotation[][] anns = m.getParameterAnnotations();
		Class<?>[] pts = m.getParameterTypes();

		// 1) Prefer an explicitly annotated @BindEventThrowable param
		for (int i = 0; i < pts.length; i++) {
			boolean hasBind = false;
			for (Annotation a : anns[i]) {
				// Avoid a hard dependency if the annotation class isn’t on the compile path here
				if ("com.obsinity.telemetry.annotations.BindEventThrowable"
						.equals(a.annotationType().getName())) {
					hasBind = true;
					break;
				}
			}
			if (hasBind && Throwable.class.isAssignableFrom(pts[i])) {
				return (Class<? extends Throwable>) pts[i];
			}
		}

		// 2) Otherwise, any Throwable parameter counts
		for (int i = 0; i < pts.length; i++) {
			if (Throwable.class.isAssignableFrom(pts[i])) {
				return (Class<? extends Throwable>) pts[i];
			}
		}
		return null; // generic
	}
}
