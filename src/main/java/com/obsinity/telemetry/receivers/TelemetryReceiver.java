package com.obsinity.telemetry.receivers;

import com.obsinity.telemetry.model.TelemetryHolder;

import java.util.List;

/**
 * Receives flow lifecycle notifications.
 */
public interface TelemetryReceiver {
	/**
	 * Called when any flow (root or nested) is opened.
	 */
	default void flowStarted(TelemetryHolder holder) {
	}

	/**
	 * Called when any flow (root or nested) is finished.
	 */
	default void flowFinished(TelemetryHolder holder) {
	}

	/**
	 * Called when a root flow finishes, with all finished flows (root + all nested)
	 * that completed within that root, each with endTimestamp set.
	 */
	default void rootFlowFinished(List<TelemetryHolder> completed) {
	}
}
