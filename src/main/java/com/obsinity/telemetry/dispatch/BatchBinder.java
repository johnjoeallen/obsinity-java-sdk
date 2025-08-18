package com.obsinity.telemetry.dispatch;

import java.util.List;

import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;

public final class BatchBinder implements ParamBinder {
	private final Class<?> paramType;

	public BatchBinder(Class<?> paramType) {
		this.paramType = paramType;
	}

	@Override
	public Object bind(TelemetryHolder holder, Lifecycle phase, Throwable error) {
		throw new UnsupportedOperationException("Batch binder requires batch context");
	}

	/** Bind from batch context (not from single holder). */
	public Object bindBatch(List<TelemetryHolder> batch) {
		// Parameter must be List<TelemetryHolder> (or raw List)
		return batch;
	}

	public Class<?> paramType() {
		return paramType;
	}
}
