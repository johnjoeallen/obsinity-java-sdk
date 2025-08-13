package com.obsinity.telemetry.processor;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;

import com.obsinity.telemetry.annotations.Attribute;
import com.obsinity.telemetry.model.TelemetryHolder;

@Component
public final class AttributeParamExtractor {

	/**
	 * Copies values from @Attribute parameters into the provided OAttributes. If a parameter value is a Map<?,?>,
	 * String-keyed entries are merged.
	 */
	public void extractTo(TelemetryHolder.OAttributes attrs, JoinPoint jp) {
		if (attrs == null || jp == null) {
			return;
		}

		MethodSignature sig = (MethodSignature) jp.getSignature();
		Method method = mostSpecific(sig.getMethod(), jp.getTarget());
		Object[] args = jp.getArgs();

		if (method == null || args == null) {
			return;
		}

		Parameter[] params = method.getParameters();
		int n = Math.min(params.length, args.length);

		for (int i = 0; i < n; i++) {
			Attribute ann = params[i].getAnnotation(Attribute.class);
			String name = ann != null ? ann.name() : null;

			if (name != null && !name.isBlank()) {
				Object value = args[i];
				if (value instanceof Map<?, ?> map) {
					attrs.put(name, map); // same as other types for now
				} else {
					attrs.put(name, value);
				}
			}
		}
	}

	/* --- helpers --- */

	private static Method mostSpecific(Method m, Object target) {
		return (target != null) ? AopUtils.getMostSpecificMethod(m, target.getClass()) : m;
	}
}
