package com.obsinity.telemetry.dispatch;

import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.opentelemetry.api.trace.SpanKind;
import com.obsinity.telemetry.annotations.DispatchMode;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;

/** Immutable, precompiled handler descriptor discovered at bootstrap. */
public record Handler(
		Object bean,
		Method method,
		String exactName, // dot-chop key (validated by scanner; no wildcards)
		BitSet lifecycleMask,
		BitSet kindMask,
		DispatchMode mode, // COMBINED / SUCCESS / FAILURE
		List<Class<? extends Throwable>> throwableTypes,
		boolean includeSubclasses,
		Pattern messagePattern, // optional regex on Throwable.getMessage()
		Class<?> causeTypeOrNull, // optional Throwable.getCause() type
		List<ParamBinder> binders,
		Set<String> requiredAttrs,
		String id // e.g. beanClass#method
		) {

	/** Lifecycle acceptance (null mask = any). */
	public boolean lifecycleAccepts(Lifecycle lc) {
		if (lc == null) return true;
		return (lifecycleMask == null) || lifecycleMask.get(lc.ordinal());
	}

	/** SpanKind acceptance (null mask = any). */
	public boolean kindAccepts(SpanKind kind) {
		if (kind == null) return (kindMask == null) || (kindMask.get(SpanKind.INTERNAL.ordinal()));
		return (kindMask == null) || kindMask.get(kind.ordinal());
	}

	public boolean isCombinedMode() {
		return mode == DispatchMode.COMBINED;
	}

	public boolean isSuccessMode() {
		return mode == DispatchMode.SUCCESS;
	}

	public boolean isFailureMode() {
		return mode == DispatchMode.FAILURE;
	}

	/** Convenience accessor for the dispatcher’s dot-chop map key. */
	public String nameKey() {
		return exactName;
	}

	/** Back-compat shim so callers can use getMode() instead of record accessor mode(). */
	public DispatchMode getMode() {
		return mode;
	}

	/** Human-friendly id for logs. */
	public String debugName() {
		try {
			return (id != null ? id : (bean.getClass().getSimpleName() + "#" + method.getName()));
		} catch (Throwable t) {
			return "<handler>";
		}
	}

	/** Fast acceptance test combining mode, lifecycle/kind masks, required attrs, and failure filters. */
	public boolean accepts(Lifecycle phase, TelemetryHolder h, boolean failed, Throwable error) {
		// 0) Mode vs error presence
		if (isSuccessMode() && failed) return false;
		if (isFailureMode() && !failed) return false;

		// 1) Lifecycle / Kind
		if (!lifecycleAccepts(phase)) return false;
		SpanKind k = (h == null || h.kind() == null) ? SpanKind.INTERNAL : h.kind();
		if (!kindAccepts(k)) return false;

		// 2) Required attributes
		if (requiredAttrs != null && !requiredAttrs.isEmpty()) {
			Map<String, ?> attrs = (h == null || h.attributes() == null)
					? null
					: h.attributes().asMap();
			if (attrs == null || !attrs.keySet().containsAll(requiredAttrs)) return false;
		}

		// 3) Throwable filters (only on failure path)
		if (!failed) return true;

		// 3a) Type checks
		if (throwableTypes != null && !throwableTypes.isEmpty()) {
			boolean ok = false;
			for (Class<? extends Throwable> tt : throwableTypes) {
				if (tt == null) continue;
				if (includeSubclasses
						? (tt.isInstance(error))
						: (error != null && error.getClass().equals(tt))) {
					ok = true;
					break;
				}
			}
			if (!ok) return false;
		}

		// 3b) Message regex
		if (messagePattern != null) {
			String msg = (error == null ? null : error.getMessage());
			if (msg == null || !messagePattern.matcher(msg).find()) return false;
		}

		// 3c) Cause type
		if (causeTypeOrNull != null) {
			Throwable cause = (error == null ? null : error.getCause());
			if (cause == null || !causeTypeOrNull.isInstance(cause)) return false;
		}

		return true;
	}

	/**
	 * Invoke the handler method using precompiled {@link ParamBinder}s. Ensures reflective access even for non-public
	 * declaring classes (e.g., test inner classes).
	 */
	public void invoke(TelemetryHolder h, Lifecycle phase) throws Exception {
		final int paramCount = method.getParameterCount();
		final Object[] args = new Object[paramCount];

		final int n = (binders == null) ? 0 : Math.min(binders.size(), paramCount);
		final Throwable error = (h == null ? null : h.throwable());

		for (int i = 0; i < n; i++) {
			final ParamBinder b = binders.get(i);
			args[i] = (b == null) ? null : b.bind(h, phase, error);
		}
		// Any remaining parameters beyond known binders default to null.

		// One-place robust access fix (covers non-public declaring classes & JPMS)
		if (!method.canAccess(bean)) {
			method.setAccessible(true);
		}

		try {
			method.invoke(bean, args);
		} catch (IllegalAccessException e) {
			// Rare edge under stricter environments: retry once after forcing accessibility
			method.setAccessible(true);
			method.invoke(bean, args);
		}
	}
}
