package com.obsinity.telemetry.dispatch;

import java.util.LinkedHashMap;
import java.util.Map;

import com.obsinity.telemetry.model.TelemetryHolder;

/** Binds @AllAttrs to a Map<String,Object> view of attributes. */
final class AllAttrsBinder implements ParamBinder {
	@Override
	public Object bind(TelemetryHolder holder) {
		if (holder == null || holder.attributes() == null || holder.attributes().asMap() == null) {
			return Map.of();
		}
		// Defensive copy so handlers can’t mutate the holder’s internal map
		return new LinkedHashMap<>(holder.attributes().asMap());
	}
}
