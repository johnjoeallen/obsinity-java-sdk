package com.obsinity.telemetry.dispatch;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import io.opentelemetry.api.trace.SpanKind;
import com.obsinity.telemetry.annotations.BindEventThrowable;
import com.obsinity.telemetry.annotations.DispatchMode;
import com.obsinity.telemetry.annotations.OnEvent;
import com.obsinity.telemetry.annotations.PullAllContextValues;
import com.obsinity.telemetry.annotations.PullAttribute;
import com.obsinity.telemetry.annotations.PullContextValue;
import com.obsinity.telemetry.annotations.RequiredAttributes;
import com.obsinity.telemetry.annotations.TelemetryEventHandler;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;

/**
 * Scans beans for methods annotated with {@link OnEvent} on classes marked with {@link TelemetryEventHandler} and
 * produces immutable {@link Handler} descriptors.
 *
 * <p><b>Name matching:</b> supports exact name ({@code @OnEvent(name="...")} or {@code @OnEvent("...")}) and prefix
 * matching ({@code @OnEvent(namePrefix="...")}). Regex name matching is not supported.
 *
 * <p><b>Filters:</b> lifecycle ({@link OnEvent#lifecycle()}), span kind ({@link OnEvent#kinds()}), exception
 * types/message/cause ({@link OnEvent#throwableTypes()}, {@link OnEvent#messageRegex()}, {@link OnEvent#causeType()}),
 * combined with {@link OnEvent#mode()}.
 *
 * <p><b>DispatchMode & exception binding rules:</b>
 *
 * <ul>
 *   <li>{@code mode=NORMAL}: <b>no</b> {@code @BindEventThrowable} parameter allowed.
 *   <li>{@code mode=ERROR}: <b>exactly one</b> {@code @BindEventThrowable} parameter is required and its type must be
 *       {@link Throwable} or a subclass.
 *   <li>{@code mode=ALWAYS}: {@code @BindEventThrowable} is optional; if present, at most one and must be a
 *       {@link Throwable} type.
 * </ul>
 *
 * <p><b>Strict catch‑all validation:</b> For each selector discovered on this class, if any handler exists (any mode),
 * there must be at least one {@code mode=ERROR} handler whose {@code @BindEventThrowable} parameter type is
 * {@code Exception} or {@code Throwable}. Missing this will fail startup.
 */
@Component
public final class TelemetryEventHandlerScanner {

	public List<Handler> scan(Object bean) {
		Class<?> userClass = resolveUserClass(bean);

		// Only scan classes marked with @TelemetryEventHandler
		if (userClass == null || !userClass.isAnnotationPresent(TelemetryEventHandler.class)) {
			return List.of(); // skip silently
		}

		// Track per-selector stats to enforce catch-all presence
		Map<String, SelectorStats> stats = new LinkedHashMap<>();

		List<Handler> out = new ArrayList<>();
		for (Method m : userClass.getMethods()) {
			OnEvent on = m.getAnnotation(OnEvent.class);
			if (on == null) continue;

			validateNameSelectors(on, m);

			String exact = on.name().isBlank() ? (on.value().isBlank() ? null : on.value()) : on.name();
			String namePrefix = on.namePrefix().isBlank() ? null : on.namePrefix();
			String selectorKey = selectorKeyOf(exact, namePrefix);

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

			// Collect required attrs from @RequiredAttributes and any @PullAttribute(required=true) params
			Set<String> requiredAttrs = new LinkedHashSet<>();
			RequiredAttributes req = m.getAnnotation(RequiredAttributes.class);
			if (req != null) requiredAttrs.addAll(Arrays.asList(req.value()));

			// Build param binders
			List<ParamBinder> binders = new ArrayList<>();
			Parameter[] params = m.getParameters();

			int exceptionParamCount = 0;
			Class<?> exceptionParamType = null;

			for (Parameter p : params) {
				PullAttribute attr = p.getAnnotation(PullAttribute.class);
				BindEventThrowable exAnn = p.getAnnotation(BindEventThrowable.class);
				PullContextValue ecp = p.getAnnotation(PullContextValue.class);
				PullAllContextValues aec = p.getAnnotation(PullAllContextValues.class);

				if (attr != null) {
					String key = readAlias(attr); // supports name() or value()
					boolean required = safeRequired(attr);
					if (required) requiredAttrs.add(key);
					Function<Object, Object> objConverter = o -> coerceForParam(o, p.getType());
					binders.add(new AttrBinder(key, required, objConverter, p.getType()));

				} else if (exAnn != null) {
					// Validate exception parameter type eagerly
					Class<?> t = p.getType();
					if (!Throwable.class.isAssignableFrom(t)) {
						throw new IllegalArgumentException(
								"@BindEventThrowable parameter must be a Throwable: " + signature(userClass, m));
					}
					exceptionParamCount++;
					exceptionParamType = t;
					// Use your enum/source on the annotation if needed (kept as before)
					binders.add(new ThrowableBinder(exAnn.required(), exAnn.source()));

				} else if (ecp != null) {
					// Single value from EventContext
					binders.add(new EventContextBinder(readAlias(ecp), p.getType()));

				} else if (aec != null) {
					// Whole EventContext map
					if (!Map.class.isAssignableFrom(p.getType())) {
						throw new IllegalArgumentException(
								"@PullAllContextValues parameter must be a Map: " + signature(userClass, m));
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

			// Enforce DispatchMode ↔ exception parameter rules
			validateModeAndExceptionParams(on.mode(), exceptionParamCount, exceptionParamType, userClass, m);

			// Track for strict catch-all: any handler for this selector?
			SelectorStats selStats = stats.computeIfAbsent(selectorKey, k -> new SelectorStats());
			selStats.anyHandlers = true;
			// Catch-all exists if mode=ERROR and @BindEventThrowable param type is Exception or Throwable
			if (on.mode() == DispatchMode.ERROR
					&& exceptionParamType != null
					&& (Exception.class.equals(exceptionParamType) || Throwable.class.equals(exceptionParamType))) {
				selStats.hasCatchAllError = true;
			}

			List<Class<? extends Throwable>> types = List.of(on.throwableTypes());
			String id = userClass.getSimpleName() + "#" + m.getName();

			out.add(new Handler(
					bean,
					m,
					exact,
					namePrefix, // <-- prefix, not regex
					lifecycleMask,
					kindMask,
					on.mode(), // <-- pass DispatchMode here
					types,
					on.includeSubclasses(),
					msgPat,
					causeType,
					List.copyOf(binders),
					Set.copyOf(requiredAttrs),
					id));
		}

		// Strict catch-all validation (per selector in this class)
		stats.forEach((key, s) -> {
			if (s.anyHandlers && !s.hasCatchAllError) {
				throw new IllegalStateException(
						"[Obsinity] Missing catch‑all error handler (mode=ERROR with @BindEventThrowable Exception|Throwable) "
								+ "for selector '" + key + "' in " + userClass.getName());
			}
		});

		return out;
	}

	private static final class SelectorStats {
		boolean anyHandlers;
		boolean hasCatchAllError;
	}

	private static String selectorKeyOf(String exact, String prefix) {
		if (exact != null) return "name:" + exact;
		if (prefix != null) return "prefix:" + prefix;
		return "name:*"; // safeguard
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

	/** Validate that only one of exact name or prefix is provided. */
	private static void validateNameSelectors(OnEvent on, Method m) {
		boolean hasExact = !(on.name().isBlank() && on.value().isBlank());
		boolean hasPrefix = !on.namePrefix().isBlank();
		if (hasExact && hasPrefix) {
			throw new IllegalArgumentException("@OnEvent cannot set both name/name(value) and namePrefix on "
					+ signature(m.getDeclaringClass(), m));
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

	private static void validateModeAndExceptionParams(
			DispatchMode mode, int exceptionParamCount, Class<?> exceptionParamType, Class<?> userClass, Method m) {
		switch (mode) {
			case NORMAL -> {
				if (exceptionParamCount > 0) {
					throw new IllegalArgumentException(
							"@OnEvent(mode=NORMAL) must not declare @BindEventThrowable parameter on "
									+ signature(userClass, m));
				}
			}
			case ERROR -> {
				if (exceptionParamCount != 1) {
					throw new IllegalArgumentException(
							"@OnEvent(mode=ERROR) must declare exactly one @BindEventThrowable parameter on "
									+ signature(userClass, m));
				}
				if (exceptionParamType == null || !Throwable.class.isAssignableFrom(exceptionParamType)) {
					throw new IllegalArgumentException(
							"@BindEventThrowable parameter must be a Throwable type on " + signature(userClass, m));
				}
			}
			case ALWAYS -> {
				if (exceptionParamCount > 1) {
					throw new IllegalArgumentException(
							"@OnEvent(mode=ALWAYS) may declare at most one @BindEventThrowable parameter on "
									+ signature(userClass, m));
				}
				if (exceptionParamCount == 1
						&& (exceptionParamType == null || !Throwable.class.isAssignableFrom(exceptionParamType))) {
					throw new IllegalArgumentException(
							"@BindEventThrowable parameter must be a Throwable type on " + signature(userClass, m));
				}
			}
			default -> throw new IllegalArgumentException("Unknown DispatchMode on " + signature(userClass, m));
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

	/** Some @PullAttribute versions may not declare 'required()'. Treat absent method as false. */
	private static boolean safeRequired(PullAttribute attr) {
		try {
			return (boolean) PullAttribute.class.getMethod("required").invoke(attr);
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
		// Unknown/custom types: best effort—return raw and let reflection fail fast if truly incompatible.
		return raw;
	}

	/* ===== Helpers to support name/value alias on annotations ===== */

	private static String readAlias(PullAttribute ann) {
		// Prefer name() if present & non-empty, else value()
		String key = invokeIfPresent(ann, "name");
		if (key == null || key.isEmpty()) key = invokeIfPresent(ann, "value");
		return key;
	}

	private static String readAlias(PullContextValue ann) {
		String key = invokeIfPresent(ann, "name");
		if (key == null || key.isEmpty()) key = invokeIfPresent(ann, "value");
		return key;
	}

	private static String invokeIfPresent(Object ann, String method) {
		try {
			var m = ann.getClass().getMethod(method); // runtime class; works with or without @AliasFor
			Object v = m.invoke(ann);
			return (v instanceof String s) ? s : null;
		} catch (NoSuchMethodException e) {
			return null; // element not present on this annotation
		} catch (Exception e) {
			return null; // defensive
		}
	}
}
