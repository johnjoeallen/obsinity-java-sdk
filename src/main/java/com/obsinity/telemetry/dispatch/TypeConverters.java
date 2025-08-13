package com.obsinity.telemetry.dispatch;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

/** Minimal string -> type converters for @Attr. */
public final class TypeConverters {
	private TypeConverters() {}

	@SuppressWarnings("unchecked")
	public static Function<String, Object> forType(Class<?> target) {
		if (target == String.class) return s -> s;
		if (target == Long.class || target == long.class) return s -> Long.parseLong(s);
		if (target == Integer.class || target == int.class) return s -> Integer.parseInt(s);
		if (target == Double.class || target == double.class) return s -> Double.parseDouble(s);
		if (target == Boolean.class || target == boolean.class)
			return s -> {
				if ("1".equals(s)) return Boolean.TRUE;
				if ("0".equals(s)) return Boolean.FALSE;
				return Boolean.parseBoolean(s);
			};
		if (target == UUID.class) return s -> UUID.fromString(s);
		if (target == Instant.class) return s -> Instant.parse(s);
		if (target == Duration.class) return s -> Duration.parse(s);
		// Fallback to identity; callers should validate types before using.
		return s -> s;
	}
}
