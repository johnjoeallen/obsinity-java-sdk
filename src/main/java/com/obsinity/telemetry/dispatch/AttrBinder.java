package com.obsinity.telemetry.dispatch;

import java.util.function.Function;

import com.obsinity.telemetry.model.TelemetryHolder;

/** Binds @Attr parameter with optional presence + conversion. */
final class AttrBinder implements ParamBinder {
	private final String key;
	private final boolean required;
	private final Function<String, Object> converter; // String -> target type

	AttrBinder(String key, boolean required, Function<String, Object> converter) {
		this.key = key;
		this.required = required;
		this.converter = converter;
	}

	@Override
	public Object bind(TelemetryHolder holder) {
		String raw = holder.attr(key);
		if (raw == null) {
			if (required) throw new AttrBindingException(key, "missing required attribute");
			return null;
		}
		try {
			return converter.apply(raw);
		} catch (RuntimeException ex) {
			if (required) throw new AttrBindingException(key, "invalid value: " + raw);
			return null;
		}
	}
}
