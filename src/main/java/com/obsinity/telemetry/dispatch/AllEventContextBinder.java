package com.obsinity.telemetry.dispatch;

import java.util.LinkedHashMap;
import java.util.Map;

import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;

/** Binds the entire TelemetryHolder.eventContext() map into a handler parameter. */
public final class AllEventContextBinder implements ParamBinder {
	@Override
	public Object bind(TelemetryHolder holder, Lifecycle phase, Throwable error) {
		if (holder == null) return null;
		Map<String, Object> src = holder.eventContext();
		return (src == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(src);
	}
}
