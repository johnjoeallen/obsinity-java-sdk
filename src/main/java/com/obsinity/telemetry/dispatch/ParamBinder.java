package com.obsinity.telemetry.dispatch;

import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;

/**
 * Binds a single method parameter value from the current event context. This version is context-aware (holder + phase +
 * throwable).
 */
@FunctionalInterface
public interface ParamBinder {
	Object bind(TelemetryHolder holder, Lifecycle phase, Throwable error);
}
