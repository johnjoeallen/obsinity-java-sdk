package com.obsinity.telemetry.dispatch;

import com.obsinity.telemetry.model.TelemetryHolder;

/** Binds @Err parameter (self or cause), optional required. */
final class ErrBinder implements ParamBinder {
	private final boolean required;
	private final boolean bindCause;

	ErrBinder(boolean required, boolean bindCause) {
		this.required = required;
		this.bindCause = bindCause;
	}

	@Override
	public Object bind(TelemetryHolder holder) {
		Throwable t = holder.getThrowable();
		if (bindCause && t != null) t = t.getCause();
		if (t == null && required) throw new AttrBindingException("<throwable>", "missing throwable");
		return t;
	}
}
