package com.obsinity.telemetry.dispatch;

import com.obsinity.telemetry.model.TelemetryHolder;

/** Binds a single method parameter from a TelemetryHolder. */
public interface ParamBinder {
	Object bind(TelemetryHolder holder) throws AttrBindingException;
}
