package com.obsinity.telemetry.receivers;

import java.util.List;
import java.util.Objects;

import com.obsinity.telemetry.model.TelemetryHolder;

/** Lightweight signal for async delivery to receivers. */
public final class TelemetrySignal {

	public enum Stage {
		START,
		FINISH,
		ROOT_FINISH
	}

	public final Stage stage;
	public final TelemetryHolder holder; // used for START/FINISH
	public final List<TelemetryHolder> batch; // used for ROOT_FINISH

	private TelemetrySignal(Stage stage, TelemetryHolder holder, List<TelemetryHolder> batch) {
		this.stage = Objects.requireNonNull(stage, "stage");
		this.holder = holder;
		this.batch = batch;
	}

	public static TelemetrySignal start(TelemetryHolder h) {
		return new TelemetrySignal(Stage.START, Objects.requireNonNull(h, "holder"), null);
	}

	public static TelemetrySignal finish(TelemetryHolder h) {
		return new TelemetrySignal(Stage.FINISH, Objects.requireNonNull(h, "holder"), null);
	}

	public static TelemetrySignal rootFinish(List<TelemetryHolder> batch) {
		return new TelemetrySignal(StageROOT_FINISH, null, Objects.requireNonNull(batch, "batch"));
	}

	// tiny typo-proof helper
	private static final Stage StageROOT_FINISH = Stage.ROOT_FINISH;
}
