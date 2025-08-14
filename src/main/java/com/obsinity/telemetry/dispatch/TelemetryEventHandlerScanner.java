package com.obsinity.telemetry.dispatch;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.obsinity.telemetry.annotations.BindAllContextValues;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import io.opentelemetry.api.trace.SpanKind;
import com.obsinity.telemetry.annotations.BindContextValue;
import com.obsinity.telemetry.annotations.BindEventAttribute;
import com.obsinity.telemetry.annotations.BindEventThrowable;
import com.obsinity.telemetry.annotations.OnEvent;
import com.obsinity.telemetry.annotations.RequiredAttributes;
import com.obsinity.telemetry.annotations.TelemetryEventHandler;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;

/**
 * Scans beans for @OnEvent methods, but only if the bean's user class is annotated with @TelemetryEventHandler.
 *
 * <p>Supported handler parameter binding: - @BindEventAttribute("key"): bind a single persisted attribute (required if
 * the annotation says so) - @BindEventThrowable: bind the event's Throwable (SELF, CAUSE, ROOT_CAUSE)
 * - @BindContextValue("key"): bind a single ephemeral context value - @BindAllContextValues: bind the entire event
 * context map (must target Map<String,Object>) - TelemetryHolder: inject the holder - List<?> (batch): only allowed for
 * lifecycle=ROOT_FLOW_FINISHED
 */
@Component
public final class TelemetryEventHandlerScanner {

	public List<Handler> scan(Object bean) {
		Class<?> userClass = resolveUserClass(bean);

		// Only scan classes marked as TelemetryEventHandler
		if (userClass == null || !userClass.isAnnotationPresent(TelemetryEventHandler.class)) {
			return List.of(); // skip silently
		}

		List<Handler> out = new ArrayList<>();
		for (Method m : userClass.getMethods()) {
			OnEvent on = m.getAnnotation(OnEvent.class);
			if (on == null) continue;

			validateNameSelectors(on, m);

			String exact = on.name().isBlank() ? null : on.name();
			Pattern namePat = on.nameRegex().isBlank() ? null : Pattern.compile(on.nameRegex());

			BitSet lifecycleMask = bitset(on.lifecycle(), Lifecycle.values().length, lc -> lc.ordinal());
			BitSet kindMask = bitset(on.kinds(), SpanKind.values().length, k -> k.ordinal());

			Pattern msgPat = on.messageRegex().isBlank() ? null : Pattern.compile(on.messageRegex());

			Class<?> causeType = null;
			if (!on.causeType().isBlank()) {
				try {
					causeType = ClassUtils.resolveClassName(on.causeType(), userClass.getClassLoader());
				} catch (IllegalArgumentException e) {
					throw new IllegalArgumentException(
							"@OnEvent causeType not found: " + on.causeType() + " on " + signature(userClass, m));
				}
			}

			// Collect required attrs from @RequiredAttributes and any @BindEventAttribute(required=true) params
			Set<String> requiredAttrs = new LinkedHashSet<>();
			RequiredAttributes req = m.getAnnotation(RequiredAttributes.class);
			if (req != null) requiredAttrs.addAll(Arrays.asList(req.value()));

			// Build param binders
			List<ParamBinder> binders = new ArrayList<>();
			Parameter[] params = m.getParameters();
			for (Parameter p : params) {
				BindEventAttribute attr = p.getAnnotation(BindEventAttribute.class);
				BindEventThrowable thr = p.getAnnotation(BindEventThrowable.class);
				BindContextValue ecp = p.getAnnotation(BindContextValue.class);
				BindAllContextValues aec = p.getAnnotation(BindAllContextValues.class);

				if (attr != null) {
					String key = attr.name();
					boolean required = safeRequired(attr);
					if (required) requiredAttrs.add(key);
					Function<Object, Object> objConverter = o -> coerceForParam(o, p.getType());
					binders.add(new AttrBinder(key, required, objConverter, p.getType()));
				} else if (thr != null) {
					// Use enum-based selection for which throwable to bind
					binders.add(new ThrowableBinder(thr.required(), thr.source())); // ErrBinder should accept the enum
				} else if (ecp != null) {
					// Single value from EventContext
					binders.add(new EventContextBinder(ecp.name(), p.getType()));
				} else if (aec != null) {
					// Whole EventContext map
					if (!Map.class.isAssignableFrom(p.getType())) {
						throw new IllegalArgumentException(
								"@BindAllContextValues parameter must be a Map: " + signature(userClass, m));
					}
					binders.add(new AllEventContextBinder());
				} else if (List.class.isAssignableFrom(p.getType())) {
					// Inferred batch parameter — only allowed for ROOT_FLOW_FINISHED
					validateListParamAllowed(on, userClass, m);
					binders.add(new BatchBinder(p.getType()));
				} else if (TelemetryHolder.class.isAssignableFrom(p.getType())) {
					binders.add(new HolderBinder());
				} else {
					throw new IllegalArgumentException(
							"Unsupported parameter binding on " + signature(userClass, m) + " for parameter: " + p);
				}
			}

			List<Class<? extends Throwable>> types = List.of(on.throwableTypes());
			String id = userClass.getSimpleName() + "#" + m.getName();

			out.add(new Handler(
					bean,
					m,
					exact,
					namePat,
					lifecycleMask,
					kindMask,
					on.requireThrowable(),
					types,
					on.includeSubclasses(),
					msgPat,
					causeType,
					List.copyOf(binders),
					Set.copyOf(requiredAttrs),
					id));
		}
		return out;
	}

	/** Resolve underlying user class when running with Spring proxies. */
	private static Class<?> resolveUserClass(Object bean) {
		Class<?> candidate = (bean != null ? bean.getClass() : null);
		if (candidate == null) return null;
		if (AopUtils.isAopProxy(bean)) {
			Class<?> target = AopUtils.getTargetClass(bean);
			if (target != null) {
				return ClassUtils.getUserClass(target);
			}
		}
		return ClassUtils.getUserClass(candidate);
	}

	private static void validateNameSelectors(OnEvent on, Method m) {
		boolean hasExact = !on.name().isBlank();
		boolean hasRegex = !on.nameRegex().isBlank();
		if (hasExact && hasRegex) {
			throw new IllegalArgumentException(
					"@OnEvent cannot set both name and nameRegex on " + signature(m.getDeclaringClass(), m));
		}
	}

	private static void validateListParamAllowed(OnEvent on, Class<?> userClass, Method m) {
		var lifecycles = Arrays.asList(on.lifecycle());
		boolean onlyRootFinished =
				!lifecycles.isEmpty() && lifecycles.stream().allMatch(lc -> lc == Lifecycle.ROOT_FLOW_FINISHED);
		if (!onlyRootFinished) {
			throw new IllegalArgumentException(
					"List<TelemetryHolder> parameters are only allowed for lifecycle=ROOT_FLOW_FINISHED on "
							+ signature(userClass, m));
		}
	}

	private static <T> BitSet bitset(T[] values, int size, java.util.function.ToIntFunction<T> ord) {
		if (values == null || values.length == 0) return null; // means “any”
		BitSet bs = new BitSet(size);
		for (T v : values) bs.set(ord.applyAsInt(v));
		return bs;
	}

	private static String signature(Class<?> c, Method m) {
		return c.getName() + "." + m.getName() + Arrays.toString(m.getParameterTypes());
	}

	/** Some @BindEventAttribute versions may not declare 'required()'. Treat absent method as false. */
	private static boolean safeRequired(BindEventAttribute attr) {
		try {
			return (boolean) BindEventAttribute.class.getMethod("required").invoke(attr);
		} catch (NoSuchMethodException ignored) {
			return false;
		} catch (Exception e) {
			return false;
		}
	}

	/** Minimal, safe coercions for attribute binding without forcing Stringification of complex objects. */
	private static Object coerceForParam(Object raw, Class<?> targetType) {
		if (raw == null || targetType == null) return raw;
		if (targetType.isInstance(raw)) return raw;

		if (targetType == String.class) return String.valueOf(raw);

		if (targetType == Integer.class || targetType == int.class) {
			if (raw instanceof Number n) return n.intValue();
			if (raw instanceof String s) return Integer.valueOf(Integer.parseInt(s));
		}
		if (targetType == Long.class || targetType == long.class) {
			if (raw instanceof Number n) return n.longValue();
			if (raw instanceof String s) return Long.valueOf(Long.parseLong(s));
		}
		if (targetType == Double.class || targetType == double.class) {
			if (raw instanceof Number n) return n.doubleValue();
			if (raw instanceof String s) return Double.valueOf(Double.parseDouble(s));
		}
		if (targetType == Float.class || targetType == float.class) {
			if (raw instanceof Number n) return n.floatValue();
			if (raw instanceof String s) return Float.valueOf(Float.parseFloat(s));
		}
		if (targetType == Boolean.class || targetType == boolean.class) {
			if (raw instanceof Boolean b) return b;
			if (raw instanceof Number n) return n.intValue() != 0;
			if (raw instanceof String s) {
				String ss = s.trim();
				if ("1".equals(ss)) return Boolean.TRUE;
				if ("0".equals(ss)) return Boolean.FALSE;
				return Boolean.valueOf(Boolean.parseBoolean(ss));
			}
		}
		if (targetType == UUID.class) {
			if (raw instanceof UUID u) return u;
			if (raw instanceof String s) return UUID.fromString(s);
		}
		// Unknown or custom types: best effort—return raw and let reflection fail fast if truly incompatible.
		return raw;
	}
}
