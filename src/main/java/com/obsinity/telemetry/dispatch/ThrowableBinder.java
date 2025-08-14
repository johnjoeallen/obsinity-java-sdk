package com.obsinity.telemetry.dispatch;

import com.obsinity.telemetry.annotations.BindEventThrowable;
import com.obsinity.telemetry.model.TelemetryHolder;

/** Binds the event Throwable per @BindEventThrowable (SELF, CAUSE, ROOT_CAUSE), with optional required flag. */
final class ThrowableBinder implements ParamBinder {
	private final boolean required;
	private final BindEventThrowable.Source source;

	ThrowableBinder(boolean required, BindEventThrowable.Source source) {
		this.required = required;
		this.source = (source != null ? source : BindEventThrowable.Source.SELF);
	}

	@Override
	public Object bind(TelemetryHolder holder) {
		Throwable t = (holder != null ? holder.getThrowable() : null);

		switch (source) {
			case SELF -> {
				// use t as-is
			}
			case CAUSE -> {
				if (t != null) t = t.getCause();
			}
			case ROOT_CAUSE -> {
				if (t != null) {
					while (t.getCause() != null) {
						t = t.getCause();
					}
				}
			}
		}

		if (t == null && required) {
			throw new AttrBindingException("<throwable>", "missing throwable");
		}
		return t;
	}
}
