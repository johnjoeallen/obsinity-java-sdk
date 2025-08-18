package com.obsinity.telemetry.dispatch;

import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;

/** Binds the entire TelemetryHolder into a handler parameter. */
public final class HolderBinder implements ParamBinder {
	@Override
	public Object bind(TelemetryHolder holder, Lifecycle phase, Throwable error) {
		return holder;
	}
}
