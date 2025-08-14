package com.obsinity.telemetry.dispatch;

import com.obsinity.telemetry.model.TelemetryHolder;

import java.util.function.Function;

/**
 * Binds an attribute key to a handler parameter, with optional presence + conversion.
 * <p>
 * - Uses TelemetryHolder.attrRaw(String) to preserve object values.
 * - Passes through when the value already matches the target type.
 * - Falls back to a converter (object-aware or String-based).
 */
final class AttrBinder implements ParamBinder {
	private final String key;
	private final boolean required;

	// Converter that accepts the raw attribute object (preferred).
	private final Function<Object, Object> objectConverter;

	// If known, the target parameter type (for pass-through/validation).
	private final Class<?> targetType;

	/**
	 * Preferred ctor: accepts an Object-based converter and the explicit target type.
	 * If the raw value already {@code instanceof targetType}, it is returned as-is.
	 */
	AttrBinder(String key, boolean required, Function<Object, Object> objectConverter, Class<?> targetType) {
		this.key = key;
		this.required = required;
		this.objectConverter = objectConverter;
		this.targetType = targetType;
	}

	@Override
	public Object bind(TelemetryHolder holder) {
		Object raw = (holder == null) ? null : holder.attrRaw(key);

		if (raw == null) {
			if (required) throw new AttrBindingException(key, "missing required attribute");
			return null;
		}

		// Pass-through when we know the target type and it's already correct.
		if (targetType != null && targetType.isInstance(raw)) {
			return raw;
		}

		try {
			Object converted = (objectConverter != null) ? objectConverter.apply(raw) : raw;

			// If we know the target type, ensure assignability (allow String fallback when param is String).
			if (targetType != null && converted != null && !targetType.isInstance(converted)) {
				if (targetType == String.class) {
					return String.valueOf(converted);
				}
				throw new AttrBindingException(
					key,
					"converted value type " + converted.getClass().getName() + " not assignable to " + targetType.getName());
			}
			return converted;
		} catch (RuntimeException ex) {
			if (required) {
				throw new AttrBindingException(key, "invalid value for '" + key + "': " + ex.getMessage());
			}
			return null;
		}
	}

	@Override
	public String toString() {
		return "AttrBinder[" + key + (targetType != null ? " -> " + targetType.getSimpleName() : "") + "]";
	}
}
