package com.obsinity.telemetry.dispatch;

import com.obsinity.telemetry.model.TelemetryHolder;
import java.util.List;

public final class RootBatchContext {
	private static final ThreadLocal<List<TelemetryHolder>> TL = new ThreadLocal<>();
	private RootBatchContext() {}
	public static void set(List<TelemetryHolder> batch) { TL.set(batch); }
	public static List<TelemetryHolder> get() { return TL.get(); }
	public static void clear() { TL.remove(); }
}
