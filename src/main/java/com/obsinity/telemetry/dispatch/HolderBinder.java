package com.obsinity.telemetry.dispatch;

import com.obsinity.telemetry.model.TelemetryHolder;

public final class HolderBinder implements ParamBinder {   // ← was package‑private
	@Override
	public Object bind(TelemetryHolder holder) {
		return holder;
	}
}
