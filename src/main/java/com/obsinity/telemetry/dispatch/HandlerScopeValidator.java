package com.obsinity.telemetry.dispatch;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.obsinity.telemetry.annotations.EventScope;
import com.obsinity.telemetry.annotations.OnEvent;
import com.obsinity.telemetry.annotations.OnEveryEvent;
import com.obsinity.telemetry.annotations.OnUnMatchedEvent;
import com.obsinity.telemetry.annotations.TelemetryEventHandler;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import io.opentelemetry.api.trace.SpanKind;
import com.obsinity.telemetry.model.Lifecycle;

/**
 * Startup validator that enforces safe combinations of @OnEvent, @OnEveryEvent, @OnUnMatchedEvent with
 * optional @EventScope on @TelemetryEventHandler components.
 */
@Component
public class HandlerScopeValidator implements SmartInitializingSingleton {

	private final ListableBeanFactory beanFactory;

	public HandlerScopeValidator(ListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	@Override
	public void afterSingletonsInstantiated() {
		Map<String, Object> beans = beanFactory.getBeansWithAnnotation(TelemetryEventHandler.class);

		for (Map.Entry<String, Object> e : beans.entrySet()) {
			String beanName = e.getKey();
			Class<?> userClass = AopUtils.getTargetClass(e.getValue());

			EventScope scope = userClass.getAnnotation(EventScope.class);
			Set<String> scopePrefixes = scope == null ? Set.of() : Set.of(scope.prefixes());
			Set<Lifecycle> scopeLifecycles = scope == null ? Set.of() : Set.of(scope.lifecycles());
			Set<SpanKind> scopeKinds = scope == null ? Set.of() : Set.of(scope.kinds());
			EventScope.ErrorMode scopeErrorMode = scope == null ? EventScope.ErrorMode.ANY : scope.errorMode();

			List<Method> onEventMethods = methodsAnnotated(userClass, OnEvent.class);
			List<Method> onUnmatchedMethods = methodsAnnotated(userClass, OnUnMatchedEvent.class);

			// Mixed mode requires scope if any COMPONENT unmatched present
			boolean hasComponentUnmatched = onUnmatchedMethods.stream()
					.map(m -> m.getAnnotation(OnUnMatchedEvent.class))
					.anyMatch(a -> a.scope() == OnUnMatchedEvent.Scope.COMPONENT);

			if (hasComponentUnmatched && scope == null) {
				throw new BeanCreationException(
						beanName,
						err(userClass)
								+ " mixes @OnUnMatchedEvent(scope=COMPONENT) and @OnEvent but has no @EventScope. "
								+ "Add @EventScope(prefixes=...) to bound the component’s domain.");
			}

			// Validate each @OnEvent intersects class scope
			for (Method m : onEventMethods) {
				OnEvent a = m.getAnnotation(OnEvent.class);
				validateOnEventAgainstScope(
						beanName, userClass, m, a, scopePrefixes, scopeLifecycles, scopeKinds, scopeErrorMode);
			}

			// Method-level misuse: cannot annotate with both
			for (Method m : userClass.getMethods()) {
				if (m.isAnnotationPresent(OnEvent.class) && m.isAnnotationPresent(OnUnMatchedEvent.class)) {
					throw new BeanCreationException(
							beanName, err(userClass, m) + "cannot have both @OnEvent and @OnUnMatchedEvent.");
				}
			}

			// Warn if GLOBAL unmatched is paired with @EventScope (ignored)
			boolean hasGlobalUnmatched = onUnmatchedMethods.stream()
					.map(m -> m.getAnnotation(OnUnMatchedEvent.class))
					.anyMatch(a -> a.scope() == OnUnMatchedEvent.Scope.GLOBAL);
			if (hasGlobalUnmatched && scope != null) {
				// optional: log a warning
			}

			// Validate @OnEveryEvent against scope (must intersect)
			for (Method m : methodsAnnotated(userClass, OnEveryEvent.class)) {
				OnEveryEvent ann = m.getAnnotation(OnEveryEvent.class);
				if (!scopePrefixes.isEmpty() && !overlapsByPrefix(scopePrefixes, "")) {
					// scope restricts by prefix, so tap sees only those anyway
					// no explicit name filter on OnEveryEvent, so fine
				}
				if (!scopeLifecycles.isEmpty()
						&& ann.lifecycle().length > 0
						&& !intersects(scopeLifecycles, Set.of(ann.lifecycle()))) {
					throw new BeanCreationException(
							beanName,
							err(userClass, m) + "@OnEveryEvent lifecycle filters do not intersect component scope.");
				}
				if (!scopeKinds.isEmpty() && ann.kinds().length > 0 && !intersects(scopeKinds, Set.of(ann.kinds()))) {
					throw new BeanCreationException(
							beanName,
							err(userClass, m) + "@OnEveryEvent kind filters do not intersect component scope.");
				}
				// errorMode: taps don’t have errorMode; always ANY — so always compatible
			}
		}
	}

	private static List<Method> methodsAnnotated(Class<?> c, Class<? extends Annotation> ann) {
		return Arrays.stream(c.getMethods())
				.filter(m -> m.isAnnotationPresent(ann))
				.collect(Collectors.toList());
	}

	private static void validateOnEventAgainstScope(
			String beanName,
			Class<?> userClass,
			Method m,
			OnEvent a,
			Set<String> scopePrefixes,
			Set<Lifecycle> scopeLifecycles,
			Set<SpanKind> scopeKinds,
			EventScope.ErrorMode scopeErrorMode) {
		String eventName = a.name();

		// Prefix intersection
		if (!scopePrefixes.isEmpty()) {
			boolean ok = scopePrefixes.stream().anyMatch(p -> eventName.startsWith(p));
			if (!ok) {
				throw new BeanCreationException(
						beanName,
						err(userClass, m) + "event name outside component @EventScope prefixes. " + "Scope="
								+ scopePrefixes + ", method name='" + eventName + "'.");
			}
		}

		// Lifecycle intersection
		if (!scopeLifecycles.isEmpty()
				&& a.lifecycle().length > 0
				&& !intersects(scopeLifecycles, Set.of(a.lifecycle()))) {
			throw new BeanCreationException(
					beanName, err(userClass, m) + "lifecycles do not intersect component @EventScope.");
		}

		// Kind intersection
		if (!scopeKinds.isEmpty() && a.kinds().length > 0 && !intersects(scopeKinds, Set.of(a.kinds()))) {
			throw new BeanCreationException(
					beanName, err(userClass, m) + "span kinds do not intersect component @EventScope.");
		}

		// Error mode intersection
		if (scopeErrorMode != EventScope.ErrorMode.ANY) {
			// OnEvent doesn’t expose error tri-state; assume always compatible for now
		}
	}

	private static boolean overlapsByPrefix(Set<String> prefixes, String candidate) {
		if (candidate.isEmpty()) return true;
		for (String p : prefixes) {
			if (candidate.startsWith(p) || p.startsWith(candidate)) return true;
		}
		return false;
	}

	private static <E> boolean intersects(Set<E> scope, Set<E> method) {
		if (scope.isEmpty() || method.isEmpty()) return true;
		for (E e : method) if (scope.contains(e)) return true;
		return false;
	}

	private static String err(Class<?> c) {
		return "Handler component '" + c.getName() + "': ";
	}

	private static String err(Class<?> c, Method m) {
		return "Handler method '" + c.getSimpleName() + "#" + m.getName() + "()': ";
	}
}
