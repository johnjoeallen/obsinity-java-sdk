// file: src/main/java/com/obsinity/telemetry/dispatch/AttributeValueBinder.java
package com.obsinity.telemetry.dispatch;

import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;

/** Binds a handler parameter annotated with @Attribute from the holder's attributes map. */
public final class AttributeValueBinder implements ParamBinder {
	private final String key;
	private final Class<?> targetType;

	public AttributeValueBinder(String key, Class<?> targetType) {
		this.key = key;
		this.targetType = targetType;
	}

	@Override
	public Object bind(TelemetryHolder holder, Lifecycle phase, Throwable error) {
		if (holder == null || holder.attributes() == null) return null;
		Object raw = holder.attributes().asMap().get(key);
		return coerce(raw, targetType);
	}

	private static Object coerce(Object raw, Class<?> targetType) {
		if (raw == null) return null;
		if (targetType == null) return raw;
		if (targetType.isInstance(raw)) return raw;
		if (targetType == String.class) return String.valueOf(raw);
		// Add numeric/boolean coercions here if you need them later.
		return raw; // best-effort
	}

	@Override
	public String toString() {
		return "AttributeValueBinder[" + key + " -> " + (targetType == null ? "Object" : targetType.getSimpleName()) + "]";
	}
}
