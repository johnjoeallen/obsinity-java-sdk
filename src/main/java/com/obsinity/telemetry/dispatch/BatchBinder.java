package com.obsinity.telemetry.dispatch;

import java.util.List;

public final class BatchBinder implements ParamBinder {
	private final Class<?> paramType;

	public BatchBinder(Class<?> paramType) {
		this.paramType = paramType;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Object bind(com.obsinity.telemetry.model.TelemetryHolder holder) {
		throw new UnsupportedOperationException("Batch binder requires batch context");
	}

	/** Bind from batch context (not from single holder). */
	public Object bindBatch(List<com.obsinity.telemetry.model.TelemetryHolder> batch) {
		// Parameter must be List<TelemetryHolder> (or raw List)
		return batch;
	}

	public Class<?> paramType() {
		return paramType;
	}
}
