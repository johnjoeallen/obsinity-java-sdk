package com.obsinity.telemetry.processor;

import com.obsinity.telemetry.model.TelemetryHolder;
import com.obsinity.telemetry.receivers.TelemetryReceiver;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

@Component
public class TelemetryProcessorSupport {

	/** Per-thread stack of active flows (top = current). */
	private final InheritableThreadLocal<Deque<TelemetryHolder>> ctx;

	/**
	 * Per-thread, per-root in-order list of finished {@link TelemetryHolder}s.
	 * Created when the root opens; appended to on each flow finish; emitted and cleared at root exit.
	 */
	private final InheritableThreadLocal<List<TelemetryHolder>> batch;

	public TelemetryProcessorSupport() {
		this.ctx = new InheritableThreadLocal<Deque<TelemetryHolder>>() {
			@Override
			protected Deque<TelemetryHolder> initialValue() {
				return new ArrayDeque<>();
			}
		};
		this.batch = new InheritableThreadLocal<List<TelemetryHolder>>() {
			@Override
			protected List<TelemetryHolder> initialValue() {
				return new ArrayList<>();
			}
		};
	}

	/* --------------------- flow stack --------------------- */

	TelemetryHolder currentHolder() {
		final Deque<TelemetryHolder> d = ctx.get();
		if (d.isEmpty()) {
			return null;
		} else {
			return d.peekLast();
		}
	}

	boolean hasActiveFlow() {
		final Deque<TelemetryHolder> d = ctx.get();
		return !d.isEmpty();
	}

	void push(final TelemetryHolder h) {
		final Deque<TelemetryHolder> d = ctx.get();
		d.addLast(h);
	}

	void pop(final TelemetryHolder expectedTop) {
		final Deque<TelemetryHolder> d = ctx.get();
		final boolean notEmpty = !d.isEmpty();
		if (notEmpty) {
			final TelemetryHolder last = d.peekLast();
			if (last == expectedTop) {
				d.removeLast();
			} else {
				d.clear();
			}
		} else {
			// Intentionally empty: nothing to pop
		}
	}

	/* --------------------- batch helpers --------------------- */

	void startNewBatch() {
		batch.set(new ArrayList<>());
	}

	void addToBatch(final TelemetryHolder finished) {
		final List<TelemetryHolder> list = batch.get();
		if (list != null && finished != null) {
			list.add(finished);
		} else {
			// Intentionally empty: nothing to add
		}
	}

	List<TelemetryHolder> finishBatchAndGet() {
		final List<TelemetryHolder> out = batch.get();
		batch.remove(); // clean slate for the next root
		return out;
	}

	/* --------------------- receiver notifications --------------------- */

	void notifyFlowStarted(final List<TelemetryReceiver> receivers, final TelemetryHolder holder) {
		if (receivers == null || receivers.isEmpty() || holder == null) {
			return;
		}
		for (TelemetryReceiver r : receivers) {
			try {
				r.flowStarted(holder);
			} catch (Exception ignored) {
				// Intentionally ignore receiver exceptions to avoid breaking the flow
			}
		}
	}

	void notifyFlowFinished(final List<TelemetryReceiver> receivers, final TelemetryHolder holder) {
		if (receivers == null || receivers.isEmpty() || holder == null) {
			return;
		}
		for (TelemetryReceiver r : receivers) {
			try {
				r.flowFinished(holder);
			} catch (Exception ignored) {
				// Intentionally ignore receiver exceptions to avoid breaking the flow
			}
		}
	}

	void notifyRootFlowFinished(final List<TelemetryReceiver> receivers, final List<TelemetryHolder> batchList) {
		if (receivers == null || receivers.isEmpty() || batchList == null || batchList.isEmpty()) {
			return;
		}
		for (TelemetryReceiver r : receivers) {
			try {
				r.rootFlowFinished(batchList);
			} catch (Exception ignored) {
				// Intentionally ignore receiver exceptions to avoid breaking the flow
			}
		}
	}

	/* --------------------- mutation helpers --------------------- */

	void setEndTime(final TelemetryHolder h, final Instant end) {
		if (h != null) {
			h.setEndTimestamp(end); // assumes setter exists
		} else {
			// Intentionally empty: nothing to mutate
		}
	}

	/* --------------------- utility --------------------- */

	long unixNanos(final Instant ts) {
		final long seconds = ts.getEpochSecond();
		final int nanos = ts.getNano();
		return seconds * 1_000_000_000L + nanos;
	}

	interface UnsafeRunnable {
		void run() throws Exception;
	}

	void safe(final UnsafeRunnable r) {
		try {
			r.run();
		} catch (Exception ignored) {
			// Intentionally ignore hook/receiver exceptions to keep the main flow healthy
		}
	}
}
