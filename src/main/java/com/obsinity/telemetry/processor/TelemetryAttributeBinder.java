package com.obsinity.telemetry.processor;

import com.obsinity.telemetry.annotations.PushAttribute;
import com.obsinity.telemetry.model.OAttributes;
import com.obsinity.telemetry.model.TelemetryHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Producer-side parameter binder.
 *
 * Writes method parameters into:
 *  - a {@link TelemetryHolder}'s attributes/context (via {@link #bind(TelemetryHolder, ProceedingJoinPoint)})
 *  - a provided {@link OAttributes} instance (via {@link #bind(OAttributes, ProceedingJoinPoint)})
 *
 * Supported annotations:
 *  - {@link PushAttribute}: attributes[key] = arg  (skips null when omitIfNull=true)
 *  - "com.obsinity.telemetry.annotations.PushContextValue" (if present): eventContext[key] = arg
 */
@Component
public class TelemetryAttributeBinder {

	/** Optional dependency; present in tests that construct this binder with it. */
	@SuppressWarnings("unused")
	private final AttributeParamExtractor extractor;

	/** Default constructor (kept for contexts that autowire without the extractor). */
	public TelemetryAttributeBinder() {
		this.extractor = null;
	}

	/** Test/config constructor: matches config calling new TelemetryAttributeBinder(AttributeParamExtractor). */
	public TelemetryAttributeBinder(AttributeParamExtractor extractor) {
		this.extractor = extractor;
	}

	/* =======================================================
	 * Overload #1: bind into a TelemetryHolder (attributes + context)
	 * ======================================================= */
	public void bind(TelemetryHolder holder, ProceedingJoinPoint pjp) {
		Objects.requireNonNull(holder, "holder");
		Objects.requireNonNull(pjp, "pjp");

		Method method = resolveMethodWithAnnotations(pjp);
		if (method == null) return;

		Annotation[][] paramAnns = method.getParameterAnnotations();
		Object[] args = pjp.getArgs();
		if (paramAnns.length == 0) return;

		BiConsumer<Object, Object> attrWriter = writerForAttributes(holder);
		BiConsumer<Object, Object> ctxWriter  = writerForContext(holder, true); // lazily create if needed

		for (int i = 0; i < paramAnns.length; i++) {
			Object arg = (args != null && i < args.length) ? args[i] : null;

			for (Annotation ann : paramAnns[i]) {
				// ---- @PushAttribute ----
				if (ann instanceof PushAttribute push) {
					String key = firstNonBlank(push.value(), push.name());
					if (isBlank(key)) continue;
					if (arg == null && push.omitIfNull()) continue;
					if (attrWriter != null) attrWriter.accept(key, arg);
					continue;
				}

				// ---- @PushContextValue (optional, detected by FQCN to avoid hard dependency) ----
				if (isAnnotationNamed(ann, "com.obsinity.telemetry.annotations.PushContextValue")) {
					String key = readStringMember(ann, "value");
					if (isBlank(key)) key = readStringMember(ann, "name");
					if (isBlank(key)) continue;
					if (ctxWriter != null) ctxWriter.accept(key, arg);
				}
			}
		}
	}

	/* =======================================================
	 * Overload #2: bind directly into an OAttributes instance
	 * Used by tests: binder.bind(attrs, pjp)
	 * ======================================================= */
	public void bind(OAttributes attributes, ProceedingJoinPoint pjp) {
		Objects.requireNonNull(attributes, "attributes");
		Objects.requireNonNull(pjp, "pjp");

		Method method = resolveMethodWithAnnotations(pjp);
		if (method == null) return;

		Annotation[][] paramAnns = method.getParameterAnnotations();
		Object[] args = pjp.getArgs();
		if (paramAnns.length == 0) return;

		// Reuse the same writer machinery; this will use attributes.put(...) or attributes.asMap().put(...)
		BiConsumer<Object, Object> attrWriter = makePutFunction(attributes);

		for (int i = 0; i < paramAnns.length; i++) {
			Object arg = (args != null && i < args.length) ? args[i] : null;

			for (Annotation ann : paramAnns[i]) {
				if (ann instanceof PushAttribute push) {
					String key = firstNonBlank(push.value(), push.name());
					if (isBlank(key)) continue;
					if (arg == null && push.omitIfNull()) continue;
					if (attrWriter != null) attrWriter.accept(key, arg);
				}
				// NOTE: context is not applicable when binding into a standalone OAttributes bag
			}
		}
	}

	// ---------------- writers for holder ----------------

	private static BiConsumer<Object, Object> writerForAttributes(TelemetryHolder holder) {
		Object container = holder.attributes();
		if (container == null) {
			// Create a new OAttributes if possible and set it on the holder
			try {
				container = new OAttributes(new LinkedHashMap<>());
				Method set = findMethod(holder.getClass(), "setAttributes", OAttributes.class);
				if (set == null) set = findMethod(holder.getClass(), "attributes", OAttributes.class);
				if (set != null) set.invoke(holder, container);
			} catch (Throwable ignored) {
				// best effort
			}
		}
		return makePutFunction(container);
	}

	private static BiConsumer<Object, Object> writerForContext(TelemetryHolder holder, boolean createIfMissing) {
		Object container = tryGetEventContext(holder);
		if (container == null && createIfMissing) {
			try {
				container = new OAttributes(new LinkedHashMap<>());
				Method setter = findMethod(holder.getClass(), "setEventContext", OAttributes.class);
				if (setter == null) setter = findMethod(holder.getClass(), "eventContext", OAttributes.class);
				if (setter != null) setter.invoke(holder, container);
			} catch (Throwable ignored) {
				// best effort
			}
		}
		return makePutFunction(container);
	}

	private static Object tryGetEventContext(TelemetryHolder holder) {
		Method get = findMethod(holder.getClass(), "getEventContext");
		if (get == null) get = findMethod(holder.getClass(), "eventContext");
		if (get != null) {
			try {
				return get.invoke(holder);
			} catch (IllegalAccessException | InvocationTargetException ignored) {
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static BiConsumer<Object, Object> makePutFunction(Object target) {
		if (target == null) return null;

		// If target is a Map with Object keys, a direct method reference works
		if (target instanceof Map<?, ?>) {
			Map<Object, Object> map = (Map<Object, Object>) target;
			return map::put;
		}

		// Common mutators on custom containers
		Method put = findMethod(target.getClass(), "put", String.class, Object.class);
		if (put != null) return (k, v) -> invokeQuiet(target, put, String.valueOf(k), v);

		Method set = findMethod(target.getClass(), "set", String.class, Object.class);
		if (set != null) return (k, v) -> invokeQuiet(target, set, String.valueOf(k), v);

		Method write = findMethod(target.getClass(), "write", String.class, Object.class);
		if (write != null) return (k, v) -> invokeQuiet(target, write, String.valueOf(k), v);

		// Try to unwrap to Map<String,Object>, but coerce key to String in the lambda
		Map<String, Object> m = tryToMap(target);
		if (m != null) {
			return (k, v) -> m.put(String.valueOf(k), v);
		}

		return null;
	}

	// ---------------- robust method resolution ----------------

	/**
	 * Resolve the concrete target method (handling proxies/bridge methods), preferring one
	 * that actually has parameter annotations like {@link PushAttribute}.
	 */
	private static Method resolveMethodWithAnnotations(ProceedingJoinPoint pjp) {
		MethodSignature sig = (MethodSignature) pjp.getSignature();
		Method m = sig.getMethod(); // may be interface/bridge

		Class<?> targetClass = (pjp.getTarget() != null) ? pjp.getTarget().getClass() : m.getDeclaringClass();
		Class<?>[] paramTypes = m.getParameterTypes();

		// 1) Try exact declared match up the hierarchy
		Method concrete = findDeclaredMethodHierarchy(targetClass, m.getName(), paramTypes);
		if (hasAnyParamPushAnnotation(concrete)) return concrete;

		// 2) Try public method lookup
		try {
			Method pub = targetClass.getMethod(m.getName(), paramTypes);
			if (hasAnyParamPushAnnotation(pub)) return pub;
		} catch (NoSuchMethodException ignored) { }

		// 3) Scan by name + arity; prefer methods that HAVE the annotations we're after
		Method byName = findByNameAndArityPreferAnnotated(targetClass, m.getName(), paramTypes.length);
		if (byName != null) return byName;

		// 4) Fallbacks
		if (concrete != null) return concrete;
		m.setAccessible(true);
		return m;
	}

	private static Method findDeclaredMethodHierarchy(Class<?> type, String name, Class<?>[] params) {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			try {
				Method mm = c.getDeclaredMethod(name, params);
				mm.setAccessible(true);
				return mm;
			} catch (NoSuchMethodException ignored) { }
			// also scan for bridge methods by name/arity
			for (Method cand : c.getDeclaredMethods()) {
				if (cand.getName().equals(name)
					&& cand.getParameterCount() == params.length
					&& (cand.isBridge() || cand.isSynthetic())) {
					cand.setAccessible(true);
					return cand;
				}
			}
		}
		return null;
	}

	private static Method findByNameAndArityPreferAnnotated(Class<?> type, String name, int arity) {
		Method candidateWithoutAnns = null;
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			for (Method cand : c.getDeclaredMethods()) {
				if (cand.getName().equals(name) && cand.getParameterCount() == arity) {
					cand.setAccessible(true);
					if (hasAnyParamPushAnnotation(cand)) {
						return cand; // prefer annotated
					}
					if (candidateWithoutAnns == null) {
						candidateWithoutAnns = cand;
					}
				}
			}
		}
		return candidateWithoutAnns;
	}

	private static boolean hasAnyParamPushAnnotation(Method m) {
		if (m == null) return false;
		for (Annotation[] anns : m.getParameterAnnotations()) {
			for (Annotation a : anns) {
				if (a instanceof PushAttribute) return true;
				if (isAnnotationNamed(a, "com.obsinity.telemetry.annotations.PushContextValue")) return true;
			}
		}
		return false;
	}

	// ---------------- utilities ----------------

	private static boolean isAnnotationNamed(Annotation ann, String fqcn) {
		return ann.annotationType().getName().equals(fqcn);
	}

	private static String readStringMember(Annotation ann, String member) {
		try {
			Method m = ann.annotationType().getMethod(member);
			Object v = m.invoke(ann);
			return (v == null) ? "" : String.valueOf(v);
		} catch (Throwable ignored) {
			return "";
		}
	}

	private static String firstNonBlank(String a, String b) {
		if (!isBlank(a)) return a;
		return isBlank(b) ? "" : b;
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

	private static Method findMethod(Class<?> type, String name, Class<?>... params) {
		try {
			Method m = type.getMethod(name, params);
			m.setAccessible(true);
			return m;
		} catch (NoSuchMethodException e) {
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> tryToMap(Object obj) {
		if (obj instanceof Map<?, ?> m) {
			return (Map<String, Object>) m;
		}
		// Try common accessors returning a Map
		for (String method : new String[]{"asMap", "toMap", "map", "unwrap"}) {
			Method mm = findMethod(obj.getClass(), method);
			if (mm != null) {
				Object res = invokeQuiet(obj, mm);
				if (res instanceof Map<?, ?> rm) {
					return (Map<String, Object>) rm;
				}
			}
		}
		return null;
	}

	private static Object invokeQuiet(Object target, Method m, Object... args) {
		try {
			return m.invoke(target, args);
		} catch (IllegalAccessException | InvocationTargetException ignored) {
			return null;
		}
	}
}
