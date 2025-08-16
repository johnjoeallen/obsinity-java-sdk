package com.obsinity.telemetry.processor;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;

import com.obsinity.telemetry.annotations.PushAttribute;
import com.obsinity.telemetry.model.OAttributes;

/**
 * Extracts method parameter values annotated with {@link PushAttribute} and writes them into an {@link OAttributes} bag
 * for the current telemetry event.
 *
 * <p>This extractor is typically invoked by the telemetry aspect around methods annotated with {@code @Flow} or
 * {@code @Step}. For each parameter annotated with {@link PushAttribute}:
 *
 * <ul>
 *   <li>The parameter's runtime argument value is written to {@link OAttributes} under the key specified by
 *       {@link PushAttribute#name()}.
 *   <li>If {@link PushAttribute#omitIfNull()} is {@code true} (default) and the runtime value is {@code null}, no
 *       attribute entry is created.
 *   <li>If the runtime value is a {@link Map} (of any generic types), it is stored as a single value under the given
 *       key (entries are <em>not</em> merged into the attributes map).
 * </ul>
 *
 * <h2>Thread-safety</h2>
 *
 * This component is stateless and therefore thread-safe.
 *
 * <h2>Examples</h2>
 *
 * <pre>{@code
 * @Flow(name = "checkout.process")
 * public void checkout(
 *     @PushAttribute("order.id") String orderId,
 *     @PushAttribute(value = "tags", omitIfNull = false) Map<String, Object> tags) {
 *   // ...
 * }
 * }</pre>
 *
 * @see PushAttribute
 * @see OAttributes
 * @since 1.0
 */
@Component
public final class AttributeParamExtractor {

	/**
	 * Copies values from {@link PushAttribute}-annotated parameters into the provided {@link OAttributes}.
	 *
	 * <p>If a parameter value is a {@link Map}, it is stored as the value under the provided key (no per-entry merge is
	 * attempted).
	 *
	 * @param attrs the target attributes bag to mutate; ignored if {@code null}
	 * @param jp the current AOP join point providing the method and argument values; ignored if {@code null}
	 */
	public void extractTo(OAttributes attrs, JoinPoint jp) {
		if (attrs == null || jp == null) return;

		MethodSignature sig = (MethodSignature) jp.getSignature();
		Method method = mostSpecific(sig.getMethod(), jp.getTarget());
		Object[] args = jp.getArgs();
		if (method == null || args == null) return;

		Parameter[] params = method.getParameters();
		int n = Math.min(params.length, args.length);

		for (int i = 0; i < n; i++) {
			Parameter p = params[i];
			Object value = args[i];

			PushAttribute push = p.getAnnotation(PushAttribute.class);
			if (push != null && !(push.omitIfNull() && value == null)) {
				put(attrs, push.name(), value);
			}
		}
	}

	/* --- helpers --- */

	/**
	 * Writes a single attribute key/value into {@link OAttributes}. If the value is a {@link Map}, it is stored as-is
	 * under the key (entries are not merged).
	 *
	 * @param attrs target attributes (non-null)
	 * @param key attribute key; ignored if {@code null} or blank
	 * @param value attribute value; may be {@code null}
	 */
	private static void put(OAttributes attrs, String key, Object value) {
		if (key == null || key.isBlank()) return;
		if (value instanceof Map<?, ?> m) {
			// Store the map object as a single value under 'key'
			attrs.put(key, m);
		} else {
			attrs.put(key, value);
		}
	}

	/**
	 * Resolves the most specific method for the given target class (considering possible AOP proxies).
	 *
	 * @param m the declared method
	 * @param target the invocation target (may be proxied)
	 * @return the most specific method for {@code target}, or {@code m} if {@code target} is {@code null}
	 */
	private static Method mostSpecific(Method m, Object target) {
		return (target != null) ? AopUtils.getMostSpecificMethod(m, target.getClass()) : m;
	}
}
