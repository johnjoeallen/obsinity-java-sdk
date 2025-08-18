// file: src/main/java/com/obsinity/telemetry/dispatch/TelemetryEventHandlerScanner.java
package com.obsinity.telemetry.dispatch;

import com.obsinity.telemetry.annotations.*;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;
import io.opentelemetry.api.trace.SpanKind;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Scans Spring beans annotated with {@link TelemetryEventHandler} and produces a registry of {@link HandlerGroup}s.
 *
 * Includes fail-fast validation to catch common misconfigurations and exact duplicate @OnEvent registrations.
 */
@Configuration
public class TelemetryEventHandlerScanner {

	/** Key used by the bus to stash the root batch (List<TelemetryHolder>) into the holder's eventContext. */
	static final String ROOT_BATCH_CTX_KEY = "__root_batch";

	private final ListableBeanFactory beanFactory;

	public TelemetryEventHandlerScanner(ListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	@Bean
	public List<HandlerGroup> handlerGroups() {
		Map<String, Object> beans = beanFactory.getBeansWithAnnotation(TelemetryEventHandler.class);
		List<HandlerGroup> groups = new ArrayList<>(beans.size());

		for (Map.Entry<String, Object> entry : beans.entrySet()) {
			Object bean = entry.getValue();
			Class<?> userClass = AopUtils.getTargetClass(bean);
			String componentName = userClass.getSimpleName();

			HandlerGroup group = new HandlerGroup(componentName);

			// Track exact @OnEvent duplicates: key = name|phase|mode -> handlers
			Map<String, List<String>> onEventKeysToHandlers = new LinkedHashMap<>();

			// @OnEvent
			for (Method m : methodsAnnotated(userClass, OnEvent.class)) {
				OnEvent a = m.getAnnotation(OnEvent.class);
				validateOnEventMethod(userClass, m, a);

				String exactName = a.name();
				DispatchMode mode = a.mode();
				Lifecycle[] phases = (a.lifecycle() == null || a.lifecycle().length == 0)
					? Lifecycle.values() : a.lifecycle();

				// record keys for duplicate detection
				for (Lifecycle p : phases) {
					String dupKey = dupKey(exactName, p, mode);
					onEventKeysToHandlers
						.computeIfAbsent(dupKey, k -> new ArrayList<>())
						.add(userClass.getSimpleName() + "#" + m.getName());
				}

				Handler h = buildOnEventHandler(bean, userClass, m, exactName, mode, a);
				for (Lifecycle p : phases) {
					group.registerOnEvent(exactName, p, mode, h);
				}
			}

			// Fail fast if any exact duplicates detected for this component
			validateNoExactOnEventDuplicates(userClass, onEventKeysToHandlers);

			// @OnEveryEvent (taps)
			for (Method m : methodsAnnotated(userClass, OnEveryEvent.class)) {
				OnEveryEvent a = m.getAnnotation(OnEveryEvent.class);
				validateBatchParamIfPresent(userClass, m, a.lifecycle(), /*isOnEvent*/ false, "@OnEveryEvent");

				DispatchMode mode = a.mode();
				Lifecycle[] phases = (a.lifecycle() == null) ? new Lifecycle[0] : a.lifecycle();

				Handler h = buildOnEveryEventHandler(bean, userClass, m, mode, a);
				if (phases.length == 0) {
					group.registerTap(mode, h);
				} else {
					group.registerTap(mode, h, phases);
				}
			}

			// @OnUnMatchedEvent
			for (Method m : methodsAnnotated(userClass, OnUnMatchedEvent.class)) {
				OnUnMatchedEvent a = m.getAnnotation(OnUnMatchedEvent.class);
				validateBatchParamIfPresent(userClass, m, a.lifecycle(), /*isOnEvent*/ false, "@OnUnMatchedEvent");

				Lifecycle[] phases = (a.lifecycle() == null || a.lifecycle().length == 0)
					? Lifecycle.values() : a.lifecycle();
				DispatchMode mode = a.mode();

				Handler h = buildOnUnmatchedHandler(bean, userClass, m, mode, phases);
				for (Lifecycle p : phases) {
					if (a.scope() == OnUnMatchedEvent.Scope.COMPONENT) {
						group.registerComponentUnmatched(p, mode, h);
					} else {
						group.registerGlobalUnmatched(p, mode, h);
					}
				}
			}

			groups.add(group);
		}

		groups.sort(Comparator.comparing(g -> g.componentName));
		return groups;
	}

	/* =========================
	   Handler builders
	   ========================= */

	private static Handler buildOnEventHandler(Object bean,
											   Class<?> userClass,
											   Method m,
											   String exactName,
											   DispatchMode mode,
											   OnEvent a) {
		BitSet lifecycleMask = bitsetForLifecycles(a.lifecycle());
		BitSet kindMask = bitsetForKinds(a.kinds());

		List<Class<? extends Throwable>> throwableTypes =
			(a.throwableTypes() == null || a.throwableTypes().length == 0)
				? List.of()
				: List.of(a.throwableTypes());

		boolean includeSubclasses = a.includeSubclasses();
		Pattern messagePattern = (a.messageRegex() == null || a.messageRegex().isEmpty())
			? null : Pattern.compile(a.messageRegex());

		Class<?> causeTypeOrNull = null;
		if (a.causeType() != null && !a.causeType().isEmpty()) {
			try {
				causeTypeOrNull = Class.forName(a.causeType());
			} catch (ClassNotFoundException ignored) { /* optional */ }
		}

		List<ParamBinder> binders = buildParamBinders(m);
		Set<String> requiredAttrs = Set.of();
		String id = userClass.getSimpleName() + "#" + m.getName();

		return new Handler(
			bean, m, exactName, lifecycleMask, kindMask, mode,
			throwableTypes, includeSubclasses, messagePattern, causeTypeOrNull,
			binders, requiredAttrs, id
		);
	}

	private static Handler buildOnEveryEventHandler(Object bean,
													Class<?> userClass,
													Method m,
													DispatchMode mode,
													OnEveryEvent a) {
		BitSet lifecycleMask = bitsetForLifecycles(a.lifecycle());
		BitSet kindMask = bitsetForKinds(a.kinds());

		List<ParamBinder> binders = buildParamBinders(m);
		Set<String> requiredAttrs = Set.of();
		String id = userClass.getSimpleName() + "#" + m.getName();

		return new Handler(
			bean, m, null, lifecycleMask, kindMask, mode,
			List.of(), true, null, null,
			binders, requiredAttrs, id
		);
	}

	private static Handler buildOnUnmatchedHandler(Object bean,
												   Class<?> userClass,
												   Method m,
												   DispatchMode mode,
												   Lifecycle[] lifecycles) {
		List<ParamBinder> binders = buildParamBinders(m);
		Set<String> requiredAttrs = Set.of();
		String id = userClass.getSimpleName() + "#" + m.getName();

		return new Handler(
			bean, m, null, bitsetForLifecycles(lifecycles), null, mode,
			List.of(), true, null, null,
			binders, requiredAttrs, id
		);
	}

	/* =========================
	   Param binding (annotation-aware)
	   ========================= */

	private static List<ParamBinder> buildParamBinders(Method m) {
		Class<?>[] pts = m.getParameterTypes();
		Type[] gpts = m.getGenericParameterTypes();
		Annotation[][] pann = m.getParameterAnnotations();

		List<ParamBinder> binders = new ArrayList<>(pts.length);
		for (int i = 0; i < pts.length; i++) {
			Class<?> pt = pts[i];
			Annotation[] anns = pann[i];

			// Validate illegal mixes on the same parameter
			validatePullAnnotationExclusivity(m, i, anns);

			// 1) Annotation-driven binders first
			ParamBinder b = buildAnnotationBinder(pt, anns);

			// 2) Special case: List<TelemetryHolder> for ROOT_FLOW_FINISHED batch injection
			if (b == null && List.class.isAssignableFrom(pt) && gpts[i] instanceof ParameterizedType ptype) {
				Type[] args = ptype.getActualTypeArguments();
				if (args.length == 1 && args[0] instanceof Class<?> c && TelemetryHolder.class.isAssignableFrom(c)) {
					b = (holder, phase, error) -> {
						if (holder == null || phase != Lifecycle.ROOT_FLOW_FINISHED) return null;
						Map<String, Object> ctx = holder.getEventContext();
						Object v = (ctx == null) ? null : ctx.get(ROOT_BATCH_CTX_KEY);
						if (v instanceof List<?> list) {
							@SuppressWarnings("unchecked")
							List<TelemetryHolder> cast = (List<TelemetryHolder>) list;
							return cast;
						}
						return null;
					};
				}
			}

			// 3) Defaults for well-known types
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

		for (Annotation a : anns) {
			// @PullAttribute("key")
			if (a instanceof PullAttribute pa) {
				final String key = firstNonBlank(pa.value(), pa.name());
				if (isBlank(key)) return (h, p, e) -> null;
				return (holder, phase, error) -> {
					if (holder == null) return null;
					var attrs = holder.attributes();
					Object v = (attrs == null) ? null : attrs.asMap().get(key);
					return castIfPossible(pt, v);
				};
			}
			// @PullContextValue("key") — context is a Map<String,Object>
			if (a instanceof PullContextValue pcv) {
				final String key = firstNonBlank(pcv.value(), pcv.name());
				if (isBlank(key)) return (h, p, e) -> null;
				return (holder, phase, error) -> {
					if (holder == null) return null;
					Map<String, Object> ctx = holder.getEventContext();
					Object v = (ctx == null) ? null : ctx.get(key);
					return castIfPossible(pt, v);
				};
			}
			// @PullAllContextValues — inject the whole Map
			if (a instanceof PullAllContextValues) {
				if (Map.class.isAssignableFrom(pt)) {
					return (holder, phase, error) -> {
						if (holder == null) return Map.of();
						Map<String, Object> ctx = holder.getEventContext();
						return (ctx == null) ? Map.of() : new LinkedHashMap<>(ctx);
					};
				}
				// Unsupported param type for @PullAllContextValues
				return (h, p, e) -> null;
			}
		}
		return null;
	}

	private static Object castIfPossible(Class<?> target, Object value) {
		if (value == null) return null;
		if (target.isInstance(value)) return value;
		// Numeric coercions
		if (target == Integer.class && value instanceof Number n) return n.intValue();
		if (target == Long   .class && value instanceof Number n) return n.longValue();
		if (target == Double .class && value instanceof Number n) return n.doubleValue();
		if (target == Float  .class && value instanceof Number n) return n.floatValue();
		return null;
	}

	/* =========================
	   Validation helpers
	   ========================= */

	private static void validateOnEventMethod(Class<?> userClass, Method m, OnEvent a) {
		// name must be non-blank
		if (a.name() == null || a.name().isBlank()) {
			throw new IllegalStateException(errPrefix(userClass, m) + "@OnEvent.name must be non-blank");
		}

		// mode SUCCESS cannot have throwable filters (nonsensical)
		boolean hasThrowableFilters =
			(a.throwableTypes() != null && a.throwableTypes().length > 0)
				|| (a.messageRegex() != null && !a.messageRegex().isBlank())
				|| (a.causeType() != null && !a.causeType().isBlank());

		if (a.mode() == DispatchMode.SUCCESS && hasThrowableFilters) {
			throw new IllegalStateException(errPrefix(userClass, m) +
				"@OnEvent(mode=SUCCESS) cannot declare throwable filters (throwableTypes/messageRegex/causeType/includeSubclasses)");
		}
	}

	private static void validateBatchParamIfPresent(Class<?> userClass, Method m, Lifecycle[] lifecycles,
													boolean isOnEvent, String where) {
		if (!declaresRootBatchParam(m)) return;

		// Ensure lifecycle includes ROOT_FLOW_FINISHED
		boolean hasRoot = false;
		if (lifecycles != null && lifecycles.length > 0) {
			for (Lifecycle lc : lifecycles) {
				if (lc == Lifecycle.ROOT_FLOW_FINISHED) { hasRoot = true; break; }
			}
		}
		if (!hasRoot) {
			throw new IllegalStateException(errPrefix(userClass, m) +
				where + " method declares List<TelemetryHolder> but does not include lifecycle=ROOT_FLOW_FINISHED");
		}

		// Only one List<TelemetryHolder> param per method
		if (countRootBatchParams(m) > 1) {
			throw new IllegalStateException(errPrefix(userClass, m) +
				"at most one List<TelemetryHolder> parameter is allowed");
		}

		// Guard: onEvent already checked separately, but keep consistent API
		if (isOnEvent) {
			throw new IllegalStateException(errPrefix(userClass, m) +
				where + " cannot be @OnEvent when using List<TelemetryHolder>");
		}
	}

	private static void validatePullAnnotationExclusivity(Method m, int paramIndex, Annotation[] anns) {
		if (anns == null || anns.length == 0) return;
		int pulls = 0;
		boolean hasAllCtx = false;

		for (Annotation a : anns) {
			if (a.annotationType() == PullAttribute.class) pulls++;
			if (a.annotationType() == PullContextValue.class) pulls++;
			if (a.annotationType() == PullAllContextValues.class) { pulls++; hasAllCtx = true; }
		}

		if (pulls > 1) {
			throw new IllegalStateException(String.format(
				"%sParameter %d mixes multiple Pull* annotations on method %s#%s",
				errPrefix(m.getDeclaringClass(), m), paramIndex, m.getDeclaringClass().getSimpleName(), m.getName()
			));
		}

		if (hasAllCtx) {
			// enforce param type is Map
			Class<?> pt = m.getParameterTypes()[paramIndex];
			if (!Map.class.isAssignableFrom(pt)) {
				throw new IllegalStateException(errPrefix(m.getDeclaringClass(), m) +
					"@PullAllContextValues requires parameter type assignable to Map");
			}
		}
	}

	private static void validateNoExactOnEventDuplicates(Class<?> userClass,
														 Map<String, List<String>> onEventKeysToHandlers) {
		List<String> errors = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : onEventKeysToHandlers.entrySet()) {
			List<String> hs = e.getValue();
			if (hs.size() > 1) {
				errors.add(String.format("slot [%s] claimed by %s", e.getKey(), hs));
			}
		}
		if (!errors.isEmpty()) {
			String msg = "Invalid @OnEvent configuration for component " + userClass.getSimpleName()
				+ ": exact duplicate handler registrations detected -> " + errors;
			throw new IllegalStateException(msg);
		}
	}

	private static String dupKey(String name, Lifecycle phase, DispatchMode mode) {
		return name + "|" + phase.name() + "|" + mode.name();
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

	private static String errPrefix(Class<?> userClass, Method m) {
		return "Invalid handler [" + userClass.getSimpleName() + "#" + m.getName() + "]: ";
	}

	/* =========================
	   Utilities
	   ========================= */

	private static List<Method> methodsAnnotated(Class<?> c, Class<? extends Annotation> ann) {
		return Arrays.stream(c.getMethods())
			.filter(m -> m.isAnnotationPresent(ann))
			.collect(Collectors.toList());
	}

	private static BitSet bitsetForLifecycles(Lifecycle[] lifecycles) {
		if (lifecycles == null || lifecycles.length == 0) return null;
		BitSet bs = new BitSet();
		for (Lifecycle lc : lifecycles) {
			bs.set(lc.ordinal());
		}
		return bs;
	}

	private static BitSet bitsetForKinds(SpanKind[] kinds) {
		if (kinds == null || kinds.length == 0) return null;
		BitSet bs = new BitSet();
		for (SpanKind k : kinds) {
			SpanKind kk = (k == null ? SpanKind.INTERNAL : k);
			bs.set(kk.ordinal());
		}
		return bs;
	}

	private static String firstNonBlank(String a, String b) {
		if (!isBlank(a)) return a;
		return isBlank(b) ? "" : b;
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}
}
