package com.obsinity.telemetry.receivers;

import java.util.List;

import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;

/** Routes lifecycle events to @OnEvent handlers discovered by the scanner. */
public interface TelemetryEventDispatcher {
	void dispatch(Lifecycle phase, TelemetryHolder holder);

	/** Root completion hook; used by batching flows. */
	default void dispatchRootFinished(List<TelemetryHolder> completed) {
		/* no-op */
	}
}
