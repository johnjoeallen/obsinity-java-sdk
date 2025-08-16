package com.obsinity.telemetry.dispatch;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import com.obsinity.telemetry.annotations.TelemetryEventHandler;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;

/**
 * Groups handlers per @TelemetryEventHandler bean.
 *
 * <p>- Validates signatures and binding rules. - Builds a per-bean structure: lifecycle → nameKey (exact or "") → {
 * NORMAL | ALWAYS | ERROR } lists. - Enforces that for any lifecycle used by the bean, a blank ("") selector exists
 * (for dot-chop fallback). - Forbids declaring more than one **distinct method** as a blank ("") wildcard ERROR handler
 * per handler class.
 */
@Component
public final class TelemetryEventHandlerScanner {

	private static final Logger log = LoggerFactory.getLogger(TelemetryEventHandlerScanner.class);

	/* -------------------- PUBLIC API -------------------- */

	/** Scan a single bean and return a grouped descriptor. */
	public HandlerGroup scanGrouped(Object bean) {
		Class<?> userClass = resolveUserClass(bean);
		if (userClass == null || !userClass.isAnnotationPresent(TelemetryEventHandler.class)) {
			return null; // not a handler bean
		}

		// Build flat list, then group per lifecycle/name/mode
		List<Handler> methods = scanFlat(bean, userClass);

		Map<Lifecycle, Map<String, HandlerGroup.ModeBuckets>> index = new EnumMap<>(Lifecycle.class);
		for (Lifecycle lc : Lifecycle.values()) index.put(lc, new LinkedHashMap<>());

		EnumSet<Lifecycle> lifecyclesUsed = EnumSet.noneOf(Lifecycle.class);

		// Duplicate detection within this class: (lifecycle|name|mode|signature)
		Map<String, Method> seen = new LinkedHashMap<>();

		// Track distinct blank ("") ERROR methods in this class (count at end!)
		LinkedHashSet<Method> blankErrorMethodsInClass = new LinkedHashSet<>();

		for (Handler h : methods) {
			OnEvent on = h.method().getAnnotation(OnEvent.class);
			String nameKey = nameKeyOf(on);

			for (Lifecycle lc : Lifecycle.values()) {
				if (!h.lifecycleAccepts(lc)) continue;
				lifecyclesUsed.add(lc);

				Map<String, HandlerGroup.ModeBuckets> byName = index.get(lc);
				HandlerGroup.ModeBuckets bucket = byName.computeIfAbsent(nameKey, k -> new HandlerGroup.ModeBuckets());

				// Duplicate guard: lifecycle|name|mode|signature (params included)
				String dupeKey = lc + "|" + nameKey + "|" + modeOf(h) + "|" + signature(h.method());
				Method prev = seen.putIfAbsent(dupeKey, h.method());
				if (prev != null) {
					// LOG the duplicate clearly before throwing
					log.error(
							"[Obsinity] Duplicate @OnEvent detected in {}  lifecycle={}  name='{}'  mode={}\n"
									+ "  • First : {}\n"
									+ "  • Dup   : {}",
							userClass.getName(),
							lc,
							nameKey,
							modeOf(h),
							signature(prev),
							signature(h.method()));
					throw new IllegalStateException("[Obsinity] Duplicate @OnEvent in "
							+ userClass.getName()
							+ " for lifecycle=" + lc + ", name='" + nameKey + "', mode=" + modeOf(h)
							+ " — already declared by " + signature(prev)
							+ ", duplicate: " + signature(h.method()));
				}

				if (h.isErrorMode()) {
					bucket.error.add(h);
					// Record distinct blank-error method once (not per lifecycle)
					if (nameKey.isEmpty()) {
						blankErrorMethodsInClass.add(h.method());
					}
				} else if (h.isAlwaysMode()) {
					bucket.always.add(h);
				} else {
					bucket.normal.add(h);
				}
			}
		}

		// Enforce at most one distinct blank ("") ERROR method per handler class
		if (blankErrorMethodsInClass.size() > 1) {
			StringBuilder sb = new StringBuilder();
			for (Method m : blankErrorMethodsInClass) {
				sb.append("\n  • ").append(signature(m));
			}
			log.error("[Obsinity] Multiple blank wildcard ERROR handlers found in {}:{}", userClass.getName(), sb);
			throw new IllegalStateException("[Obsinity] Multiple blank wildcard ERROR handlers found in "
					+ userClass.getName() + "; only one is allowed per handler class.");
		}

		// Require a "" selector per lifecycle used (for dot-chop fallback)
		for (Lifecycle lc : lifecyclesUsed) {
			Map<String, HandlerGroup.ModeBuckets> byName = index.get(lc);
			if (!byName.containsKey("")) {
				log.error(
						"[Obsinity] Missing blank (\"\") selector in {} for lifecycle={} (required for dot-chop fallback).",
						userClass.getName(),
						lc);
				throw new IllegalStateException("[Obsinity] Missing blank (\"\") selector in "
						+ userClass.getName() + " for lifecycle=" + lc
						+ " (required to enable dot-chop fallback).");
			}
		}

		return new HandlerGroup(bean, index);
	}

	/** Back-compat: flat scan (used by scanGrouped). */
	public List<Handler> scan(Object bean) {
		Class<?> userClass = resolveUserClass(bean);
		if (userClass == null || !userClass.isAnnotationPresent(TelemetryEventHandler.class)) {
			return List.of();
		}
		return scanFlat(bean, userClass);
	}

	/* -------------------- Internal: build flat Handler list -------------------- */

	private List<Handler> scanFlat(Object bean, Class<?> userClass) {
		List<Handler> out = new ArrayList<>();

		for (Method m : userClass.getMethods()) {
			OnEvent on = m.getAnnotation(OnEvent.class);
			if (on == null) continue;

			validateNameSelectors(on, m);

			BitSet lifecycleMask = bitset(on.lifecycle(), Lifecycle.values().length, Lifecycle::ordinal);
			BitSet kindMask = bitset(on.kinds(), SpanKind.values().length, SpanKind::ordinal);

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

			// Build binders & validate binding rules
			List<ParamBinder> binders = new ArrayList<>();
			int exceptionParamCount = 0;
			Class<?> exceptionParamType = null;

			for (Parameter p : m.getParameters()) {
				PullAttribute attr = p.getAnnotation(PullAttribute.class);
				BindEventThrowable exAnn = p.getAnnotation(BindEventThrowable.class);
				PullContextValue ecp = p.getAnnotation(PullContextValue.class);
				PullAllContextValues aec = p.getAnnotation(PullAllContextValues.class);

				if (attr != null) {
					String key = attr.name(); // require explicit name
					boolean required = safeRequired(attr);
					Function<Object, Object> conv = o -> coerce(o, p.getType());
					binders.add(new AttrBinder(key, required, conv, p.getType()));

				} else if (exAnn != null) {
					Class<?> t = p.getType();
					if (!Throwable.class.isAssignableFrom(t)) {
						throw new IllegalArgumentException(
								"@BindEventThrowable parameter must be a Throwable: " + signature(userClass, m));
					}
					exceptionParamCount++;
					exceptionParamType = t;
					binders.add(new ThrowableBinder(exAnn.required(), exAnn.source()));

				} else if (ecp != null) {
					binders.add(new EventContextBinder(ecp.name(), p.getType())); // require explicit name

				} else if (aec != null) {
					if (!Map.class.isAssignableFrom(p.getType())) {
						throw new IllegalArgumentException(
								"@PullAllContextValues parameter must be a Map: " + signature(userClass, m));
					}
					binders.add(new AllEventContextBinder());

				} else if (List.class.isAssignableFrom(p.getType())) {
					// only allowed for ROOT_FLOW_FINISHED
					if (!onlyRootFinished(on.lifecycle())) {
						throw new IllegalArgumentException(
								"List<TelemetryHolder> parameters are only allowed for lifecycle=ROOT_FLOW_FINISHED on "
										+ signature(userClass, m));
					}
					binders.add(new BatchBinder(p.getType()));

				} else if (TelemetryHolder.class.isAssignableFrom(p.getType())) {
					binders.add(new HolderBinder());

				} else {
					throw new IllegalArgumentException(
							"Unsupported parameter binding on " + signature(userClass, m) + " for parameter: " + p);
				}
			}

			validateModeAndExceptionParams(on.mode(), exceptionParamCount, exceptionParamType, userClass, m);

			List<Class<? extends Throwable>> types = List.of(on.throwableTypes());
			String exact = on.name().isBlank() ? (on.value().isBlank() ? null : on.value()) : on.name();
			String namePrefix = null; // prefix support removed

			String id = userClass.getSimpleName() + "#" + m.getName();

			out.add(new Handler(
					bean,
					m,
					exact,
					namePrefix,
					lifecycleMask,
					kindMask,
					on.mode(),
					types,
					on.includeSubclasses(),
					msgPat,
					causeType,
					List.copyOf(binders),
					Set.of(), // required attrs (if any) could be added separately
					id));
		}

		return out;
	}

	/* -------------------- Small helpers -------------------- */

	private static boolean onlyRootFinished(Lifecycle[] lifecycles) {
		if (lifecycles == null || lifecycles.length == 0) return false;
		for (Lifecycle lc : lifecycles) if (lc != Lifecycle.ROOT_FLOW_FINISHED) return false;
		return true;
	}

	private static String nameKeyOf(OnEvent on) {
		if (on == null) return "";
		if (!on.name().isBlank()) return on.name();
		if (!on.value().isBlank()) return on.value();
		return "";
	}

	private static String modeOf(Handler h) {
		if (h.isErrorMode()) return "ERROR";
		if (h.isAlwaysMode()) return "ALWAYS";
		return "NORMAL";
	}

	private static Class<?> resolveUserClass(Object bean) {
		if (bean == null) return null;
		Class<?> c = bean.getClass();
		if (AopUtils.isAopProxy(bean)) {
			Class<?> target = AopUtils.getTargetClass(bean);
			if (target != null) return ClassUtils.getUserClass(target);
		}
		return ClassUtils.getUserClass(c);
	}

	private static void validateNameSelectors(OnEvent on, Method m) {
		boolean hasExact = !(on.name().isBlank() && on.value().isBlank());
		boolean hasPrefix = !on.namePrefix().isBlank();
		if (hasExact && hasPrefix) {
			throw new IllegalArgumentException("@OnEvent cannot set both name/name(value) and namePrefix on "
					+ signature(m.getDeclaringClass(), m));
		}
		if (hasPrefix) {
			throw new IllegalArgumentException(
					"@OnEvent(namePrefix=...) is no longer supported on " + signature(m.getDeclaringClass(), m));
		}
	}

	private static void validateModeAndExceptionParams(
			DispatchMode mode, int exceptionParamCount, Class<?> exceptionParamType, Class<?> userClass, Method m) {
		switch (mode) {
			case NORMAL -> {
				if (exceptionParamCount > 0) {
					throw new IllegalArgumentException(
							"@OnEvent(mode=NORMAL) must not declare @BindEventThrowable on " + signature(userClass, m));
				}
			}
			case ERROR -> {
				if (exceptionParamCount != 1) {
					throw new IllegalArgumentException(
							"@OnEvent(mode=ERROR) must declare exactly one @BindEventThrowable on "
									+ signature(userClass, m));
				}
				if (exceptionParamType == null || !Throwable.class.isAssignableFrom(exceptionParamType)) {
					throw new IllegalArgumentException(
							"@BindEventThrowable must be a Throwable type on " + signature(userClass, m));
				}
			}
			case ALWAYS -> {
				if (exceptionParamCount > 1) {
					throw new IllegalArgumentException(
							"@OnEvent(mode=ALWAYS) may declare at most one @BindEventThrowable on "
									+ signature(userClass, m));
				}
				if (exceptionParamCount == 1
						&& (exceptionParamType == null || !Throwable.class.isAssignableFrom(exceptionParamType))) {
					throw new IllegalArgumentException(
							"@BindEventThrowable must be a Throwable type on " + signature(userClass, m));
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

	private static String signature(Method m) {
		return m.getDeclaringClass().getName() + "." + m.getName() + Arrays.toString(m.getParameterTypes());
	}

	private static Object coerce(Object raw, Class<?> targetType) {
		if (raw == null) return null;
		if (targetType.isInstance(raw)) return raw;
		if (targetType == String.class) return String.valueOf(raw);
		return raw;
	}

	private static boolean safeRequired(PullAttribute attr) {
		try {
			return (boolean) PullAttribute.class.getMethod("required").invoke(attr);
		} catch (ReflectiveOperationException ignore) {
			return false;
		}
	}
}
