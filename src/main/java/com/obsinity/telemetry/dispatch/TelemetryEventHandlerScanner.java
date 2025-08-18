package com.obsinity.telemetry.dispatch;

import com.obsinity.telemetry.annotations.*;
import com.obsinity.telemetry.model.Lifecycle;
import io.opentelemetry.api.trace.SpanKind;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Scans Spring beans annotated with {@link TelemetryEventHandler} and produces a registry of {@link HandlerGroup}s.
 *
 * Registers:
 *  - {@link OnEvent} into dot-chop tiers (per-phase, per-mode).
 *  - {@link OnEveryEvent} into additive taps (per-phase, per-mode).
 *  - {@link OnUnMatchedEvent} into component-scoped or global unmatched buckets (per-phase).
 *
 * Notes:
 *  - Validation (scopes, intersections, mixed unmatched) should be enforced by {@link HandlerScopeValidator}.
 */
@Configuration
public class TelemetryEventHandlerScanner {

	private final ListableBeanFactory beanFactory;

	public TelemetryEventHandlerScanner(ListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	/** Build and expose the complete set of handler groups once at startup. */
	@Bean
	public List<HandlerGroup> handlerGroups() {
		Map<String, Object> beans = beanFactory.getBeansWithAnnotation(TelemetryEventHandler.class);
		List<HandlerGroup> groups = new ArrayList<>(beans.size());

		for (Map.Entry<String, Object> entry : beans.entrySet()) {
			Object bean = entry.getValue();
			Class<?> userClass = AopUtils.getTargetClass(bean);
			String componentName = userClass.getSimpleName();

			HandlerGroup group = new HandlerGroup(componentName);

			// --- @OnEvent ---
			for (Method m : methodsAnnotated(userClass, OnEvent.class)) {
				OnEvent a = m.getAnnotation(OnEvent.class);
				String exactName = a.name(); // dot-chop key
				DispatchMode mode = a.mode();

				Lifecycle[] phases = (a.lifecycle() == null || a.lifecycle().length == 0)
					? Lifecycle.values()
					: a.lifecycle();

				Handler h = buildOnEventHandler(bean, userClass, m, exactName, mode, a);
				for (Lifecycle p : phases) {
					group.registerOnEvent(exactName, p, mode, h);
				}
			}

			// --- @OnEveryEvent (taps) ---
			for (Method m : methodsAnnotated(userClass, OnEveryEvent.class)) {
				OnEveryEvent a = m.getAnnotation(OnEveryEvent.class);
				DispatchMode mode = a.mode();

				Lifecycle[] phases = (a.lifecycle() == null) ? new Lifecycle[0] : a.lifecycle();

				Handler h = buildOnEveryEventHandler(bean, userClass, m, mode, a);
				if (phases.length == 0) {
					group.registerTap(mode, h); // taps.any
				} else {
					group.registerTap(mode, h, phases);
				}
			}

			// --- @OnUnMatchedEvent (component-scoped & global) ---
			for (Method m : methodsAnnotated(userClass, OnUnMatchedEvent.class)) {
				OnUnMatchedEvent a = m.getAnnotation(OnUnMatchedEvent.class);

				Lifecycle[] phases = (a.lifecycle() == null || a.lifecycle().length == 0)
					? Lifecycle.values()
					: a.lifecycle();

				DispatchMode mode = a.mode();

				Handler h = buildOnUnmatchedHandler(bean, userClass, m, mode, a.lifecycle());
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
       Builder helpers
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
			? null
			: Pattern.compile(a.messageRegex());

		Class<?> causeTypeOrNull = null;
		if (a.causeType() != null && !a.causeType().isEmpty()) {
			try {
				causeTypeOrNull = Class.forName(a.causeType());
			} catch (ClassNotFoundException ignored) {
				// Leave null if the provided FQCN isn't resolvable at bootstrap
			}
		}

		List<ParamBinder> binders = defaultBindersFor(m);
		Set<String> requiredAttrs = Set.of();
		String id = userClass.getSimpleName() + "#" + m.getName();

		return new Handler(
			bean,
			m,
			exactName,
			lifecycleMask,
			kindMask,
			mode,
			throwableTypes,
			includeSubclasses,
			messagePattern,
			causeTypeOrNull,
			binders,
			requiredAttrs,
			id
		);
	}

	private static Handler buildOnEveryEventHandler(Object bean,
													Class<?> userClass,
													Method m,
													DispatchMode mode,
													OnEveryEvent a) {
		BitSet lifecycleMask = bitsetForLifecycles(a.lifecycle());
		BitSet kindMask = bitsetForKinds(a.kinds());

		List<ParamBinder> binders = defaultBindersFor(m);
		Set<String> requiredAttrs = Set.of();
		String id = userClass.getSimpleName() + "#" + m.getName();

		return new Handler(
			bean,
			m,
			null,              // no dot-chop name for taps
			lifecycleMask,
			kindMask,
			mode,
			List.of(),         // no throwable filtering at annotation level
			true,
			null,
			null,
			binders,
			requiredAttrs,
			id
		);
	}

	private static Handler buildOnUnmatchedHandler(Object bean,
												   Class<?> userClass,
												   Method m,
												   DispatchMode mode,
												   Lifecycle[] lifecycles) {
		List<ParamBinder> binders = defaultBindersFor(m);
		Set<String> requiredAttrs = Set.of();
		String id = userClass.getSimpleName() + "#" + m.getName();

		return new Handler(
			bean,
			m,
			null,                         // no name key for unmatched
			bitsetForLifecycles(lifecycles), // respect declared lifecycles (null = any)
			null,                         // kind any
			mode,                         // use requested mode
			List.of(),                    // no throwable filters
			true,
			null,
			null,
			binders,
			requiredAttrs,
			id
		);
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

	/**
	 * Builds simple default binders so common parameter types "just work"
	 * even without custom parameter annotations:
	 * - TelemetryHolder
	 * - Lifecycle
	 * - Throwable
	 * - SpanKind
	 */
	private static List<ParamBinder> defaultBindersFor(Method m) {
		Class<?>[] pts = m.getParameterTypes();
		List<ParamBinder> binders = new ArrayList<>(pts.length);

		for (Class<?> pt : pts) {
			if (com.obsinity.telemetry.model.TelemetryHolder.class.isAssignableFrom(pt)) {
				binders.add((holder, phase, error) -> holder);
			} else if (com.obsinity.telemetry.model.Lifecycle.class.isAssignableFrom(pt)) {
				binders.add((holder, phase, error) -> phase);
			} else if (java.lang.Throwable.class.isAssignableFrom(pt)) {
				binders.add((holder, phase, error) -> error);
			} else if (io.opentelemetry.api.trace.SpanKind.class.isAssignableFrom(pt)) {
				binders.add((holder, phase, error) ->
					holder == null ? null : (holder.kind() == null ? SpanKind.INTERNAL : holder.kind()));
			} else {
				// Unknown parameter type → null (extend with richer binders as needed)
				binders.add((holder, phase, error) -> null);
			}
		}
		return binders;
	}
}
