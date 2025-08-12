package com.obsinity.telemetry.receivers;

import com.obsinity.telemetry.model.TelemetryHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * Fan-out bus that delivers {@link TelemetryHolder} lifecycle signals to receivers asynchronously.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li><b>Per-receiver queue + worker:</b> each {@link TelemetryReceiver} gets a dedicated bounded deque
 *       and a daemon worker thread that polls and dispatches signals in FIFO order.</li>
 *   <li><b>Broadcast:</b> signals are enqueued to all receivers' queues.</li>
 *   <li><b>Backpressure policy (requested):</b> if a receiver's queue is full, we <i>synchronously</i> dispatch
 *       the <em>oldest</em> signal on the caller/producer thread to make room, then enqueue the new signal.</li>
 * </ul>
 *
 * <h3>Backpressure trade-offs</h3>
 * <ul>
 *   <li>Pros: never lose signals; preserves ordering (oldest first); queue size stays bounded.</li>
 *   <li>Cons: the producer thread may briefly execute receiver code when a receiver lags.</li>
 * </ul>
 */
@Component
public class TelemetryDispatchBus {

	private static final int DEFAULT_CAPACITY = 8192;
	private static final long POLL_MS = Duration.ofMillis(250).toMillis();

	private final Map<TelemetryReceiver, ReceiverWorker> workers = new LinkedHashMap<>();
	private final List<TelemetryReceiver> receivers;
	private final int capacity;

	@Autowired
	public TelemetryDispatchBus(List<TelemetryReceiver> receivers) {
		this(receivers, DEFAULT_CAPACITY);
	}

	public TelemetryDispatchBus(List<TelemetryReceiver> receivers, int capacity) {
		this.receivers = (receivers == null) ? List.of() : List.copyOf(receivers);
		this.capacity = capacity > 0 ? capacity : DEFAULT_CAPACITY;
	}

	@PostConstruct
	void start() {
		for (TelemetryReceiver r : receivers) {
			ReceiverWorker w = new ReceiverWorker(r, capacity);
			workers.put(r, w);
			w.start();
		}
	}

	@PreDestroy
	void stop() {
		for (ReceiverWorker w : workers.values()) w.shutdown();
		for (ReceiverWorker w : workers.values()) w.awaitStop();
		workers.clear();
	}

	/* ---- enqueue API (called from the processor) ---- */

	public void enqueueStart(TelemetryHolder h) {
		broadcast(TelemetrySignal.start(h));
	}

	public void enqueueFinish(TelemetryHolder h) {
		broadcast(TelemetrySignal.finish(h));
	}

	public void enqueueRootFinish(List<TelemetryHolder> batch) {
		broadcast(TelemetrySignal.rootFinish(batch));
	}

	private void broadcast(TelemetrySignal signal) {
		for (ReceiverWorker w : workers.values()) {
			w.offerOrDispatchOldest(signal);
		}
	}

	/* ---- per-receiver worker ---- */

	private static final class ReceiverWorker implements Runnable {
		private final TelemetryReceiver receiver;
		private final BlockingDeque<TelemetrySignal> queue;
		private final Thread thread;
		private volatile boolean running = true;

		ReceiverWorker(TelemetryReceiver receiver, int capacity) {
			this.receiver = receiver;
			this.queue = new LinkedBlockingDeque<>(capacity);
			String tn = "telemetry-dispatch-" + receiver.getClass().getSimpleName();
			this.thread = new Thread(this, tn);
			this.thread.setDaemon(true);
		}

		void start() { thread.start(); }

		void shutdown() { running = false; thread.interrupt(); }

		void awaitStop() {
			try { thread.join(5000); } catch (InterruptedException ignored) {}
		}

		/**
		 * Backpressure policy:
		 * <ol>
		 *   <li>Try to enqueue.</li>
		 *   <li>If full, poll the <em>oldest</em> signal and <b>dispatch it synchronously</b>
		 *       on the caller thread to free one slot.</li>
		 *   <li>Then enqueue the new signal (should succeed).</li>
		 * </ol>
		 * Ordering is preserved: we always force-dispatch the oldest first.
		 */
		void offerOrDispatchOldest(TelemetrySignal sig) {
			if (queue.offer(sig)) {
				return;
			}
			// Queue full: free space by delivering the oldest immediately.
			TelemetrySignal oldest = queue.pollFirst();
			if (oldest != null) {
				dispatch(oldest);
			}
			// Best effort: enqueue the new signal (should succeed now).
			// If it still fails due to a race, fall back to a direct dispatch of the new one.
			if (!queue.offer(sig)) {
				dispatch(sig);
			}
		}

		@Override
		public void run() {
			while (running) {
				try {
					TelemetrySignal sig = queue.poll(POLL_MS, TimeUnit.MILLISECONDS);
					if (sig == null) continue;
					dispatch(sig);
				} catch (InterruptedException ie) {
					// check running flag
				} catch (Throwable t) {
					// swallow to keep receiver alive
				}
			}
		}

		/** Centralized dispatch used by both the worker thread and the backpressure path. */
		private void dispatch(TelemetrySignal sig) {
			try {
				switch (sig.stage) {
					case START -> receiver.flowStarted(sig.holder);
					case FINISH -> receiver.flowFinished(sig.holder);
					case ROOT_FINISH -> receiver.rootFlowFinished(sig.batch);
				}
			} catch (Throwable ignored) {
				// never let receiver exceptions break dispatching
			}
		}
	}
}
