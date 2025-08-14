package com.obsinity.telemetry.aspect;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

import io.opentelemetry.api.trace.SpanKind;
import com.obsinity.telemetry.annotations.Flow;
import com.obsinity.telemetry.annotations.Kind;
import com.obsinity.telemetry.annotations.OrphanAlert;
import com.obsinity.telemetry.annotations.Step;

/**
 * Builds {@link FlowOptions} from method annotations.
 *
 * <ul>
 *   <li>{@code @Flow} → FlowType.FLOW, orphanAlertLevel = null
 *   <li>{@code @Step} → FlowType.STEP, orphanAlertLevel = level from {@code @OrphanAlert} if present (else ERROR)
 * </ul>
 */
public final class FlowOptionsFactory {

	private FlowOptionsFactory() {}

	/** Build FlowOptions from a reflective method (expects most-specific method). */
	public static FlowOptions fromMethod(Method method) {
		Class<?> targetClass = method.getDeclaringClass();
		SpanKind spanKind = resolveKind(method, targetClass);

		Flow flow = AnnotatedElementUtils.findMergedAnnotation(method, Flow.class);
		if (flow != null) {
			// Regular @Flow – no orphan semantics on the method itself
			return new FlowOptions(FlowType.FLOW, flow.name(), /*orphanAlertLevel*/ null, spanKind);
		}

		Step step = AnnotatedElementUtils.findMergedAnnotation(method, Step.class);
		if (step != null) {
			// @Step – promotion (orphan) behavior is controlled by optional @OrphanAlert
			OrphanAlert orphan = AnnotatedElementUtils.findMergedAnnotation(method, OrphanAlert.class);
			OrphanAlert.Level level = (orphan != null) ? orphan.level() : OrphanAlert.Level.ERROR;
			return new FlowOptions(FlowType.STEP, step.name(), level, spanKind);
		}

		throw new IllegalArgumentException("Method lacks @Flow or @Step: " + method);
	}

	/** Build FlowOptions by specifying the class + method name (no params). */
	public static FlowOptions fromClassAndMethod(Class<?> type, String methodName) {
		Method m = pickAnnotatedOrSingleByName(type, methodName);
		Method mostSpecific = AopUtils.getMostSpecificMethod(m, type);
		return fromMethod(mostSpecific);
	}

	/** Build FlowOptions by specifying the class + method name + parameter types. */
	public static FlowOptions fromClassAndMethod(Class<?> type, String methodName, Class<?>... paramTypes) {
		Method m = ReflectionUtils.findMethod(type, methodName, paramTypes);
		if (m == null) {
			throw new IllegalArgumentException(
					"No such method: " + type.getName() + "#" + methodName + Arrays.toString(paramTypes));
		}
		Method mostSpecific = AopUtils.getMostSpecificMethod(m, type);
		return fromMethod(mostSpecific);
	}

	/** Build FlowOptions from a ProceedingJoinPoint (resolves most-specific method). */
	public static FlowOptions fromJoinPoint(ProceedingJoinPoint pjp) {
		MethodSignature sig = (MethodSignature) pjp.getSignature();
		Method method = sig.getMethod();
		Class<?> targetClass = (pjp.getTarget() != null) ? pjp.getTarget().getClass() : method.getDeclaringClass();
		Method mostSpecific = AopUtils.getMostSpecificMethod(method, targetClass);
		return fromMethod(mostSpecific);
	}

	/* ------------------ helpers ------------------ */

	private static Method pickAnnotatedOrSingleByName(Class<?> type, String name) {
		Method[] all = ReflectionUtils.getAllDeclaredMethods(type);
		List<Method> sameName =
				Arrays.stream(all).filter(m -> m.getName().equals(name)).toList();
		if (sameName.isEmpty()) {
			throw new IllegalArgumentException("No method named '" + name + "' on " + type.getName());
		}
		if (sameName.size() == 1) return sameName.get(0);

		// Prefer the one annotated with @Flow or @Step
		List<Method> annotated = sameName.stream()
				.filter(m -> AnnotatedElementUtils.hasAnnotation(m, Flow.class)
						|| AnnotatedElementUtils.hasAnnotation(m, Step.class))
				.toList();
		if (annotated.size() == 1) return annotated.get(0);

		throw new IllegalStateException(
				"Ambiguous overloads for " + type.getName() + "#" + name + " — specify parameter types");
	}

	/** Precedence: @Kind on method > @Kind on class > INTERNAL. */
	private static SpanKind resolveKind(Method method, Class<?> targetClass) {
		Kind methodKind = AnnotatedElementUtils.findMergedAnnotation(method, Kind.class);
		if (methodKind != null) return methodKind.value();

		Kind classKind = AnnotatedElementUtils.findMergedAnnotation(targetClass, Kind.class);
		if (classKind != null) return classKind.value();

		return SpanKind.INTERNAL;
	}
}
