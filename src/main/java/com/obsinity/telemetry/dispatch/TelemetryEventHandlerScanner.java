// src/main/java/com/obsinity/telemetry/dispatch/TelemetryEventHandlerScanner.java
package com.obsinity.telemetry.dispatch;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
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
import com.obsinity.telemetry.annotations.OnFlowStarted;
import com.obsinity.telemetry.annotations.OnFlowCompleted;
import com.obsinity.telemetry.annotations.OnFlowFailure;
import com.obsinity.telemetry.annotations.OnFlowNotMatched;
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
 * Scans Spring beans annotated with {@link EventReceiver} (or {@link GlobalFlowFallback})
 * and produces a registry of {@link HandlerGroup}s for flow-centric dispatch.
 *
 * Authoring semantics:
 *  - Component filters: {@code @OnEventScope} (repeatable, prefix OR) and {@code @OnEventLifecycle} (repeatable).
 *  - Handlers:
 *      @OnFlow(name)                  -> completed
 *      @OnFlowSuccess(name)           -> success
 *      @OnFlowFailure(name)           -> failure
 *      @OnFlowCompleted(name)         -> completed (or narrowed via @OnOutcome)
 *      @OnFlowNotMatched              -> component/global fallback
 *
 * Batch binding:
 *  Methods declaring a parameter of type List<TelemetryHolder> are only valid for ROOT_FLOW_FINISHED.
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
		// Gather both receiver types
		Map<String, Object> receivers = new LinkedHashMap<>();
		receivers.putAll(beanFactory.getBeansWithAnnotation(EventReceiver.class));
		receivers.putAll(beanFactory.getBeansWithAnnotation(GlobalFlowFallback.class));

		List<HandlerGroup> groups = new ArrayList<>(receivers.size());

		for (Map.Entry<String, Object> entry : receivers.entrySet()) {
			Object bean = entry.getValue();
			Class<?> userClass = AopUtils.getTargetClass(bean);
			String componentName = userClass.getSimpleName();

			boolean isGlobalFallback = AnnotationUtils.findAnnotation(userClass, GlobalFlowFallback.class) != null;

			// Build component scope (prefix filters + lifecycle filters)
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

			// Repeatable lifecycles: merge all values into one set
			Set<Lifecycle> lifecycleSet = readRepeatable(userClass, OnEventLifecycle.class).stream()
				.flatMap(a -> {
					Object v = AnnotationUtils.getValue(a, "value");
					if (v instanceof Lifecycle[] arr) return Arrays.stream(arr);
					if (v instanceof Lifecycle lc) return Arrays.stream(new Lifecycle[]{lc});
					return Arrays.<Lifecycle>stream(new Lifecycle[0]);
				})
				.collect(Collectors.toCollection(LinkedHashSet::new));

			HandlerGroup.Scope scope = HandlerGroup.Scope.of(
				prefixes.toArray(String[]::new),
				lifecycleSet.isEmpty() ? null : lifecycleSet.toArray(new Lifecycle[0])
			);

			HandlerGroup group = new HandlerGroup(componentName, scope);

			// Track duplicate registrations for exact name+phase+bucket within this component
			Map<String, List<String>> dupGuard = new LinkedHashMap<>();

			// ---------- Handlers: OnFlow -> completed ----------
			for (Method m : methodsAnnotated(userClass, OnFlowStarted.class)) {
				String name = readAliasedName(m, OnFlowStarted.class);
				validateNonBlank(userClass, m, "@OnFlow.name", name);

				registerForAllPhases(group, bean, userClass, m, name, Bucket.COMPLETED, dupGuard);
			}

			// ---------- Handlers: OnFlowSuccess -> success ----------
			for (Method m : methodsAnnotated(userClass, OnFlowSuccess.class)) {
				String name = readAliasedName(m, OnFlowSuccess.class);
				validateNonBlank(userClass, m, "@OnFlowSuccess.name", name);

				registerForAllPhases(group, bean, userClass, m, name, Bucket.SUCCESS, dupGuard);
			}

			// ---------- Handlers: OnFlowFailure -> failure ----------
			for (Method m : methodsAnnotated(userClass, OnFlowFailure.class)) {
				String name = readAliasedName(m, OnFlowFailure.class);
				validateNonBlank(userClass, m, "@OnFlowFailure.name", name);

				registerForAllPhases(group, bean, userClass, m, name, Bucket.FAILURE, dupGuard);
			}

			// ---------- Handlers: OnFlowCompleted -> completed or narrowed by @OnOutcome ----------
			for (Method m : methodsAnnotated(userClass, OnFlowCompleted.class)) {
				String name = readAliasedName(m, OnFlowCompleted.class);
				validateNonBlank(userClass, m, "@OnFlowCompleted.name", name);

				Set<Bucket> buckets = outcomeBucketsFor(m);
				for (Bucket b : buckets) {
					registerForAllPhases(group, bean, userClass, m, name, b, dupGuard);
				}
			}

			// ---------- Component/global not matched ----------
			for (Method m : methodsAnnotated(userClass, OnFlowNotMatched.class)) {
				validateBatchParamIfPresent(userClass, m, new Lifecycle[]{Lifecycle.ROOT_FLOW_FINISHED}, false, "@OnFlowNotMatched");
				Handler h = buildHandler(bean, userClass, m, null);
				// If the annotation carries lifecycle filtering, honor it; otherwise all phases
				Lifecycle[] phases = readLifecycleArray(m, OnFlowNotMatched.class);
				Lifecycle[] toRegister = (phases == null || phases.length == 0) ? Lifecycle.values() : phases;

				for (Lifecycle p : toRegister) {
					if (isGlobalFallback) {
						group.registerGlobalUnmatchedCompleted(p, h);
					} else {
						group.registerComponentUnmatchedCompleted(p, h);
					}
				}
			}

			validateNoExactDuplicates(userClass, dupGuard);
			groups.add(group);
		}

		groups.sort(Comparator.comparing(g -> g.componentName));
		return groups;
	}

	/* =========================
	   Registration helpers
	   ========================= */

	private enum Bucket { COMPLETED, SUCCESS, FAILURE }

	private void registerForAllPhases(
		HandlerGroup group,
		Object bean,
		Class<?> userClass,
		Method m,
		String exactName,
		Bucket bucket,
		Map<String, List<String>> dupGuard
	) {
		validateBatchParamIfPresent(userClass, m, new Lifecycle[]{Lifecycle.ROOT_FLOW_FINISHED}, false, "flow handler");

		Handler h = buildHandler(bean, userClass, m, exactName);
		for (Lifecycle p : Lifecycle.values()) {
			String key = dupKey(exactName, p, bucket);
			dupGuard.computeIfAbsent(key, k -> new ArrayList<>()).add(userClass.getSimpleName() + "#" + m.getName());

			switch (bucket) {
				case COMPLETED -> group.registerFlowCompleted(exactName, p, h);
				case SUCCESS   -> group.registerFlowSuccess(exactName, p, h);
				case FAILURE   -> group.registerFlowFailure(exactName, p, h);
			}
		}
	}

	private Handler buildHandler(
		Object bean,
		Class<?> userClass,
		Method m,
		String exactNameOrNull
	) {
		BitSet lifecycleMask = null; // per-handler lifecycle mask not used in flow-centric routing
		BitSet kindMask = null;

		List<ParamBinder> binders = buildParamBinders(m);
		String id = userClass.getSimpleName() + "#" + m.getName();

		// Throwable filters:
		// - For success/completed buckets, scanner leaves empty filters; dispatcher won't route here on failure.
		// - For failure buckets, filters can be expanded later via dedicated annotations (not shown here).
		return new Handler(
			bean,
			m,
			exactNameOrNull,
			lifecycleMask,
			kindMask,
			List.of(),     // throwableTypes
			true,          // includeSubclasses (n/a without types)
			null,          // messagePattern
			null,          // causeTypeOrNull
			binders,
			Set.of(),      // required attributes (n/a here)
			id
		);
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
						Object v = (attrs == null) ? null : attrs.asMap().get(fkey);
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
			throw new IllegalStateException("Invalid handler configuration for component "
				+ userClass.getSimpleName() + ": exact duplicate handler registrations -> " + errors);
		}
	}

	private static void validateBatchParamIfPresent(
		Class<?> userClass, Method m, Lifecycle[] lifecycles, boolean unused, String where) {
		if (!declaresRootBatchParam(m)) return;

		boolean hasRoot = false;
		if (lifecycles != null && lifecycles.length > 0) {
			for (Lifecycle lc : lifecycles) {
				if (lc == Lifecycle.ROOT_FLOW_FINISHED) { hasRoot = true; break; }
			}
		}
		if (!hasRoot) {
			throw new IllegalStateException(errPrefix(userClass, m) + where
				+ " declares List<TelemetryHolder> but does not include lifecycle=ROOT_FLOW_FINISHED");
		}
		if (countRootBatchParams(m) > 1) {
			throw new IllegalStateException(errPrefix(userClass, m)
				+ "at most one List<TelemetryHolder> parameter is allowed");
		}
	}

	private static void validatePullAnnotationExclusivity(Method m, int paramIndex, Annotation[] anns) {
		if (anns == null || anns.length == 0) return;
		int pulls = 0;
		boolean hasAllCtx = false;

		for (Annotation a : anns) {
			if (a instanceof PullAttribute) pulls++;
			if (a instanceof PullContextValue) pulls++;
			if (a instanceof PullAllContextValues) { pulls++; hasAllCtx = true; }
		}

		if (pulls > 1) {
			throw new IllegalStateException(String.format(
				"%sParameter %d mixes multiple Pull* annotations on method %s#%s",
				errPrefix(m.getDeclaringClass(), m),
				paramIndex, m.getDeclaringClass().getSimpleName(), m.getName()));
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
			} catch (Exception ignored) { }
		}
		Annotation ann = AnnotationUtils.findAnnotation(m, annotationType);
		if (ann != null) {
			Object v = AnnotationUtils.getValue(ann, "lifecycle");
			if (v instanceof Lifecycle[] arr) return arr;
		}
		return null;
	}

	private static Set<Bucket> outcomeBucketsFor(Method m) {
		List<OnOutcome> outs = readRepeatable(m, OnOutcome.class);
		if (outs.isEmpty()) {
			return Set.of(Bucket.COMPLETED);
		}
		Set<Bucket> set = new LinkedHashSet<>();
		for (OnOutcome o : outs) {
			Outcome val = (Outcome) AnnotationUtils.getValue(o, "value");
			if (val == Outcome.SUCCESS) set.add(Bucket.SUCCESS);
			else if (val == Outcome.FAILURE) set.add(Bucket.FAILURE);
		}
		if (set.isEmpty()) set.add(Bucket.COMPLETED);
		return set;
	}

	private static <A extends Annotation> List<A> readRepeatable(Class<?> element, Class<A> type) {
		return MergedAnnotations.from(element).stream(type).map(MergedAnnotation::synthesize).toList();
	}

	private static <A extends Annotation> List<A> readRepeatable(Method element, Class<A> type) {
		return MergedAnnotations.from(element).stream(type).map(MergedAnnotation::synthesize).toList();
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

	private static String dupKey(String name, Lifecycle phase, Bucket bucket) {
		String b = switch (bucket) {
			case COMPLETED -> "C";
			case SUCCESS   -> "S";
			case FAILURE   -> "F";
		};
		return name + "|" + phase.name() + "|" + b;
	}

	private static String strOrBlank(Object v) {
		return v == null ? "" : String.valueOf(v);
	}
}
