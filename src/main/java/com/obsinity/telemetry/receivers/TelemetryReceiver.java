package com.obsinity.telemetry.receivers;

import com.obsinity.telemetry.model.TelemetryHolder;

/** Receives lifecycle callbacks for flows. Implement either or both. */
public interface TelemetryReceiver {
	default void flowStarted(TelemetryHolder holder) {}
	default void flowFinished(TelemetryHolder holder) {}
}
