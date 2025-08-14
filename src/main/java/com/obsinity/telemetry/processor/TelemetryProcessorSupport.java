package com.obsinity.telemetry.processor;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.obsinity.telemetry.annotations.OrphanAlert;
import com.obsinity.telemetry.model.TelemetryHolder;

/**
 * Thread-local flow context + batching helpers. (Dispatching is handled by TelemetryDispatchBus in TelemetryProcessor.)
 */
@Component
public class TelemetryProcessorSupport {

	private static final Logger log = LoggerFactory.getLogger(TelemetryProcessorSupport.class);

	/** Per-thread stack of active flows/holders (top = current). */
	private final InheritableThreadLocal<Deque<TelemetryHolder>> ctx;

	/**
	 * Per-thread, per-root in-order list of completed {@link TelemetryHolder}s. Created when the root opens; appended
	 * to on flow start (for batching), emitted and cleared at root exit.
	 */
	private final InheritableThreadLocal<List<TelemetryHolder>> batch;

	public TelemetryProcessorSupport() {
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

	/** Returns the holder just below the current top (the parent), or null if none. */
	TelemetryHolder currentHolderBelowTop() {
		final Deque<TelemetryHolder> d = ctx.get();
		if (d.size() < 2) return null;
		final var it = d.descendingIterator();
		it.next(); // skip top
		return it.next(); // parent
	}

	boolean hasActiveFlow() {
		return !ctx.get().isEmpty();
	}

	void push(final TelemetryHolder h) {
		if (h != null) ctx.get().addLast(h);
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

	void addToBatch(final TelemetryHolder holder) {
		final List<TelemetryHolder> list = batch.get();
		if (list != null && holder != null) list.add(holder);
	}

	List<TelemetryHolder> finishBatchAndGet() {
		final List<TelemetryHolder> out = batch.get();
		batch.remove(); // clean slate for the next root
		return out;
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

	/* --------------------- orphan step logging --------------------- */

	/**
	 * Log that a @Step executed with no active Flow and is being promoted. Default level is ERROR if {@code level} is
	 * null.
	 */
	void logOrphanStep(final String stepName, final OrphanAlert.Level level) {
		final OrphanAlert.Level lvl = (level != null ? level : OrphanAlert.Level.ERROR);
		switch (lvl) {
			case ERROR -> log.error("Step '{}' executed with no active Flow; auto-promoted to Flow.", stepName);
			case WARN -> log.warn("Step '{}' executed with no active Flow; auto-promoted to Flow.", stepName);
			case INFO -> log.info("Step '{}' executed with no active Flow; auto-promoted to Flow.", stepName);
			case DEBUG -> log.debug("Step '{}' executed with no active Flow; auto-promoted to Flow.", stepName);
		}
	}
}
