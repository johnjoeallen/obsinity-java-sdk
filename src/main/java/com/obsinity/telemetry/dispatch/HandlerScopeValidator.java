// src/main/java/com/obsinity/telemetry/dispatch/HandlerScopeValidator.java
package com.obsinity.telemetry.dispatch;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import com.obsinity.telemetry.model.Lifecycle;

// NEW annotations (flow-centric)
import com.obsinity.telemetry.annotations.EventReceiver;
import com.obsinity.telemetry.annotations.OnEventScope;
import com.obsinity.telemetry.annotations.OnEventLifecycle;
import com.obsinity.telemetry.annotations.OnFlow;
import com.obsinity.telemetry.annotations.OnFlowSuccess;
import com.obsinity.telemetry.annotations.OnFlowFailure;
import com.obsinity.telemetry.annotations.OnFlowCompleted;
import com.obsinity.telemetry.annotations.OnFlowNotMatched;

@Component
public class HandlerScopeValidator implements SmartInitializingSingleton {

	private final ListableBeanFactory beanFactory;

	public HandlerScopeValidator(ListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	@Override
	public void afterSingletonsInstantiated() {
		Map<String, Object> beans = beanFactory.getBeansWithAnnotation(EventReceiver.class);

		for (Map.Entry<String, Object> e : beans.entrySet()) {
			String beanName = e.getKey();
			Class<?> userClass = AopUtils.getTargetClass(e.getValue());

			// Class-level scopes (merged & repeatable) — these return Set, not List
			Set<OnEventScope> scopes =
				AnnotatedElementUtils.getMergedRepeatableAnnotations(userClass, OnEventScope.class);
			Set<OnEventLifecycle> lifecyclesAnn =
				AnnotatedElementUtils.getMergedRepeatableAnnotations(userClass, OnEventLifecycle.class);

			// Flatten class-level scope config
			Set<String> scopePrefixes = scopes.stream()
				.map(a -> firstNonBlank(a.value()))
				.filter(s -> !s.isBlank())
				.collect(Collectors.toSet());

			Set<Lifecycle> scopeLifecycles = lifecyclesAnn.stream()
				.flatMap(a -> {
					Object v = AnnotationUtils.getValue(a, "value");
					if (v instanceof Lifecycle lc) {
						return java.util.stream.Stream.of(lc);
					} else if (v instanceof Lifecycle[] arr) {
						return java.util.Arrays.stream(arr);
					} else {
						return java.util.stream.Stream.<Lifecycle>empty();
					}
				})
				.collect(Collectors.toSet());

			// Validate method-level annotations
			for (Method m : userClass.getMethods()) {
				boolean hasAnyFlow =
					m.isAnnotationPresent(OnFlow.class)
						|| m.isAnnotationPresent(OnFlowSuccess.class)
						|| m.isAnnotationPresent(OnFlowFailure.class)
						|| m.isAnnotationPresent(OnFlowCompleted.class);

				boolean hasNotMatched = m.isAnnotationPresent(OnFlowNotMatched.class);

				// Misuse: cannot mix @OnFlow* with @OnFlowNotMatched on the same method
				if (hasAnyFlow && hasNotMatched) {
					throw new BeanCreationException(
						beanName,
						err(userClass, m)
							+ "cannot have both @OnFlow* and @OnFlowNotMatched on the same method.");
				}

				// Validate each @OnFlow* method against class-level scope
				if (m.isAnnotationPresent(OnFlow.class)) {
					OnFlow a = AnnotationUtils.getAnnotation(m, OnFlow.class);
					validateFlowAgainstScope(beanName, userClass, m, a.value(), scopePrefixes, scopeLifecycles);
				}
				if (m.isAnnotationPresent(OnFlowSuccess.class)) {
					OnFlowSuccess a = AnnotationUtils.getAnnotation(m, OnFlowSuccess.class);
					validateFlowAgainstScope(beanName, userClass, m, a.value(), scopePrefixes, scopeLifecycles);
				}
				if (m.isAnnotationPresent(OnFlowFailure.class)) {
					OnFlowFailure a = AnnotationUtils.getAnnotation(m, OnFlowFailure.class);
					validateFlowAgainstScope(beanName, userClass, m, a.value(), scopePrefixes, scopeLifecycles);
				}
				if (m.isAnnotationPresent(OnFlowCompleted.class)) {
					OnFlowCompleted a = AnnotationUtils.getAnnotation(m, OnFlowCompleted.class);
					validateFlowAgainstScope(beanName, userClass, m, a.value(), scopePrefixes, scopeLifecycles);
				}

				// @OnFlowNotMatched: method-only, no name and no lifecycle member to validate here
				if (hasNotMatched) {
					// Nothing to validate for prefixes (no name) or lifecycles (annotation has no lifecycle member).
					// Class-level @OnEventScope / @OnEventLifecycle already constrain what this component will see.
				}
			}
		}
	}

	private static void validateFlowAgainstScope(
		String beanName,
		Class<?> userClass,
		Method m,
		String eventName,
		Set<String> scopePrefixes,
		Set<Lifecycle> scopeLifecycles) {

		if (!scopePrefixes.isEmpty()) {
			boolean ok = scopePrefixes.stream().anyMatch(p -> eventName != null && eventName.startsWith(p));
			if (!ok) {
				throw new BeanCreationException(
					beanName,
					err(userClass, m)
						+ "flow name outside component @OnEventScope prefixes. "
						+ "Scope=" + scopePrefixes + ", method name='" + eventName + "'.");
			}
		}

		// If class declares lifecycles, that’s the contract; method-level flows don’t change it.
		if (!scopeLifecycles.isEmpty()) {
			// nothing else to check for now
		}
	}

	private static <E> boolean intersects(Set<E> scope, Set<E> method) {
		if (scope.isEmpty() || method.isEmpty()) return true;
		for (E e : method) if (scope.contains(e)) return true;
		return false;
	}

	private static String firstNonBlank(String s) {
		return (s == null) ? "" : s.trim();
	}

	private static String err(Class<?> c) {
		return "Handler component '" + c.getName() + "': ";
	}

	private static String err(Class<?> c, Method m) {
		return "Handler method '" + c.getSimpleName() + "#" + m.getName() + "()': ";
	}
}
