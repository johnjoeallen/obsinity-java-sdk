package com.obsinity.telemetry.processor;

import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Thread-local flow context + batching helpers.
 * Now uses TelemetryEventDispatcher to notify annotation-based handlers.
 */
@Component
public class TelemetryProcessorSupport {

	/** Dispatcher that delivers events to @OnEvent handlers. */
	private final TelemetryEventDispatcher dispatcher;

	/** Per-thread stack of active flows (top = current). */
	private final InheritableThreadLocal<Deque<TelemetryHolder>> ctx;

	/**
	 * Per-thread, per-root in-order list of finished {@link TelemetryHolder}s.
	 * Created when the root opens; appended to on each flow finish; emitted and cleared at root exit.
	 */
	private final InheritableThreadLocal<List<TelemetryHolder>> batch;

	public TelemetryProcessorSupport(TelemetryEventDispatcher dispatcher) {
		this.dispatcher = dispatcher;

		this.ctx = new InheritableThreadLocal<>() {
			@Override
			protected Deque<TelemetryHolder> initialValue() {
				return new ArrayDeque<>();
			}
		};
		this.batch = new InheritableThreadLocal<>() {
			@Override
			protected List<TelemetryHolder> initialValue() {
				return new ArrayList<>();
			}
		};
	}

	/* --------------------- flow stack --------------------- */

	TelemetryHolder currentHolder() {
		final Deque<TelemetryHolder> d = ctx.get();
		return d.isEmpty() ? null : d.peekLast();
	}

	boolean hasActiveFlow() {
		return !ctx.get().isEmpty();
	}

	void push(final TelemetryHolder h) {
		ctx.get().addLast(h);
	}

	void pop(final TelemetryHolder expectedTop) {
		final Deque<TelemetryHolder> d = ctx.get();
		if (!d.isEmpty()) {
			final TelemetryHolder last = d.peekLast();
			if (last == expectedTop) {
				d.removeLast();
			} else {
				d.clear(); // inconsistent nesting; reset to avoid leaks
			}
		}
	}

	/* --------------------- batch helpers --------------------- */

	void startNewBatch() {
		batch.set(new ArrayList<>());
	}

	void addToBatch(final TelemetryHolder finished) {
		final List<TelemetryHolder> list = batch.get();
		if (list != null && finished != null) list.add(finished);
	}

	List<TelemetryHolder> finishBatchAndGet() {
		final List<TelemetryHolder> out = batch.get();
		batch.remove(); // clean slate for the next root
		return out;
	}

	/* --------------------- handler notifications (via dispatcher) --------------------- */

	void notifyFlowStarted(final TelemetryHolder holder) {
		if (holder == null) return;
		safe(() -> dispatcher.dispatch(Lifecycle.FLOW_STARTED, holder));
	}

	void notifyFlowFinished(final TelemetryHolder holder) {
		if (holder == null) return;
		safe(() -> dispatcher.dispatch(Lifecycle.FLOW_FINISHED, holder));
	}

	/**
	 * Notify end-of-root with the in-order list of completed holders.
	 * Your dispatcher can either:
	 *  - expose a dedicated method (as shown), or
	 *  - emit a synthetic event with Lifecycle.ROOT_FLOW_FINISHED.
	 */
	void notifyRootFlowFinished(final List<TelemetryHolder> batchList) {
		if (batchList == null || batchList.isEmpty()) return;
		safe(() -> dispatcher.dispatchRootFinished(batchList));
	}

	/* --------------------- mutation helpers --------------------- */

	void setEndTime(final TelemetryHolder h, final Instant end) {
		if (h != null) h.setEndTimestamp(end);
	}

	/* --------------------- utility --------------------- */

	long unixNanos(final Instant ts) {
		return ts.getEpochSecond() * 1_000_000_000L + ts.getNano();
	}

	interface UnsafeRunnable {
		void run() throws Exception;
	}

	void safe(final UnsafeRunnable r) {
		try {
			r.run();
		} catch (Exception ignored) {
			// Intentionally ignore to keep the main flow healthy
		}
	}

	/* --------------------- Dispatcher contract --------------------- */

	/**
	 * Minimal dispatcher contract used by this support class.
	 * Implementations should route to @OnEvent handlers discovered by the scanner.
	 */
	public interface TelemetryEventDispatcher {
		void dispatch(Lifecycle phase, TelemetryHolder holder);
		/** Root completion hook; keep for batching use-cases. */
		default void dispatchRootFinished(List<TelemetryHolder> completed) { /* no-op by default */ }
	}
}
