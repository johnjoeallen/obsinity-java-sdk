package com.obsinity.telemetry.dispatch;

import java.util.Map;
import java.util.UUID;

import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;

/** Binds a single value from TelemetryHolder.eventContext() into a handler parameter. */
public final class EventContextBinder implements ParamBinder {
	private final String name;
	private final Class<?> targetType;

	public EventContextBinder(String name, Class<?> targetType) {
		this.name = name;
		this.targetType = targetType;
	}

	@Override
	public Object bind(TelemetryHolder holder, Lifecycle phase, Throwable error) {
		if (holder == null) return null;
		Map<String, Object> ctx = holder.eventContext();
		Object raw = (ctx != null ? ctx.get(name) : null);
		return coerce(raw, targetType);
	}

	/** Minimal, safe coercions for common simple types. */
	static Object coerce(Object raw, Class<?> targetType) {
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
		// Best effort: let reflection perform any later widening/narrowing if applicable.
		return raw;
	}
}
