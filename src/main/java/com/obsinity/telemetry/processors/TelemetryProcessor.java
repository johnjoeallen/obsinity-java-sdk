package com.obsinity.telemetry.processors;

import com.obsinity.telemetry.aspects.FlowOptions;
import com.obsinity.telemetry.model.TelemetryHolder;
import com.obsinity.telemetry.receivers.TelemetryReceiver;
import com.obsinity.telemetry.utils.TelemetryIdGenerator;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.spi.StandardLevel;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class TelemetryProcessor {

	/**
	 * Per-thread stack of active flows (top = current).
	 */
	private static final InheritableThreadLocal<Deque<TelemetryHolder>> CTX =
		new InheritableThreadLocal<>() {
			@Override
			protected Deque<TelemetryHolder> initialValue() {
				return new ArrayDeque<>();
			}
		};

	/**
	 * Per-thread, per-root in-order list of finished {@link TelemetryHolder}s.
	 * Created when the root opens; appended to on each flow finish; emitted and cleared at root exit.
	 */
	private static final InheritableThreadLocal<List<TelemetryHolder>> BATCH =
		new InheritableThreadLocal<>() {
			@Override
			protected List<TelemetryHolder> initialValue() {
				return null;
			}
		};

	/**
	 * Immutable list of downstream sinks.
	 */
	private final List<TelemetryReceiver> receivers;

	public final Object proceed(ProceedingJoinPoint joinPoint, FlowOptions options) throws Throwable {
		final boolean hasActive = hasActiveFlow();
		final boolean isFlowAnnotation = options != null && options.autoFlowLevel() == StandardLevel.OFF;
		final boolean startsNewFlow = isFlowAnnotation || (!hasActive); // promote lone @Step to a flow
		final TelemetryHolder parent = currentHolder();
		final boolean opensRoot = startsNewFlow && parent == null;

		// nested step → event
		final boolean isStep = options != null && options.autoFlowLevel() != StandardLevel.OFF;
		final boolean isNestedStep = isStep && hasActive && !startsNewFlow;
		final long stepStartNanos = isNestedStep ? System.nanoTime() : 0L;

		TelemetryHolder opened = null;

		if (startsNewFlow) {
			Instant now = Instant.now();
			long epochNanos = now.getEpochSecond() * 1_000_000_000L + now.getNano();

			String traceId;
			String parentSpanId = null;
			String correlationId;

			if (parent == null) {
				var tid = TelemetryIdGenerator.generate();
				traceId = TelemetryIdGenerator.hex128(tid);
				correlationId = traceId; // root: corr = trace
			} else {
				traceId = parent.traceId();
				parentSpanId = parent.spanId();
				correlationId = (parent.correlationId() != null && !parent.correlationId().isBlank())
					? parent.correlationId()
					: parent.traceId();
			}

			var sid = TelemetryIdGenerator.generate();
			String spanId = TelemetryIdGenerator.hex64lsb(sid);

			opened = Objects.requireNonNull(
				createFlowHolder(joinPoint, options, parent,
					traceId, spanId, parentSpanId, correlationId,
					now, epochNanos),
				"createFlowHolder() must not return null when starting a flow");

			if (opensRoot) startNewBatch();
			push(opened);
			try {
				onFlowStarted(opened, parent, joinPoint, options);
			} catch (Exception ignore) {
			}
		}

		safe(() -> onInvocationStarted(currentHolder(), joinPoint, options));

		try {
			Object result = joinPoint.proceed();

			if (isNestedStep) recordStepEvent(joinPoint, options, stepStartNanos, null);

			safe(() -> onSuccess(currentHolder(), result, joinPoint, options));
			return result;
		} catch (Throwable t) {
			if (isNestedStep) recordStepEvent(joinPoint, options, stepStartNanos, t);
			safe(() -> onError(currentHolder(), t, joinPoint, options));
			throw t;
		} finally {
			try {
				safe(() -> onInvocationFinishing(currentHolder(), joinPoint, options));
			} finally {
				if (opened != null) {
					try {
						onFlowFinishing(opened, joinPoint, options); // mutates holder + notifies
						if (opensRoot) {
							List<TelemetryHolder> batch = finishBatchAndGet();
							if (batch != null && !batch.isEmpty()) {
								onRootFlowFinished(batch, joinPoint, options);
							}
						}
					} catch (Exception ignore) {
					} finally {
						pop(opened);
					}
				}
			}
		}
	}

	/* --------------------- building blocks --------------------- */

	protected TelemetryHolder createFlowHolder(ProceedingJoinPoint pjp, FlowOptions opts, TelemetryHolder parent,
											   String traceId, String spanId, String parentSpanId, String correlationId,
											   Instant ts, long tsNanos) {

		// resource with service.* attributes
		TelemetryHolder.OResource resource = buildResource();

		// attributes (mutable)
		TelemetryHolder.OAttributes attributes = buildAttributes(pjp, opts);

		// mutable collections for events/links
		List<TelemetryHolder.OEvent> events = buildEvents();
		List<TelemetryHolder.OLink> links = buildLinks();

		// status (nullable)
		TelemetryHolder.OStatus status = buildStatus();

		// extensions (mutable)
		Map<String, Object> extensions = new LinkedHashMap<>();

		return new TelemetryHolder(
			/* name      */ opts.name(),
			/* timestamp */ ts,
			/* timeUnix  */ tsNanos,
			/* endTime   */ null,
			/* traceId   */ traceId,
			/* spanId    */ spanId,
			/* parentId  */ parentSpanId,
			/* kind      */ opts.spanKind(),
			/* resource  */ resource,
			/* attrs     */ attributes,
			/* events    */ events,
			/* links     */ links,
			/* status    */ status,
			/* serviceId */ resolveServiceId(),
			/* corrId    */ correlationId,
			/* ext       */ extensions,
			/* synthetic */ Boolean.FALSE
		);
	}

	protected TelemetryHolder.OResource buildResource() {
		String sid = resolveServiceId();
		Map<String, Object> resAttrs = new LinkedHashMap<>();
		resAttrs.put("service.name", sid);
		resAttrs.put("service.id", sid);
		return new TelemetryHolder.OResource(new TelemetryHolder.OAttributes(resAttrs));
	}

	protected TelemetryHolder.OAttributes buildAttributes(ProceedingJoinPoint pjp, FlowOptions opts) {
		return new TelemetryHolder.OAttributes(new LinkedHashMap<>());
	}

	protected List<TelemetryHolder.OEvent> buildEvents() {
		return new ArrayList<>();
	}

	protected List<TelemetryHolder.OLink> buildLinks() {
		return new ArrayList<>();
	}

	protected TelemetryHolder.OStatus buildStatus() {
		return null;
	}

	protected String resolveServiceId() {
		return System.getProperty("obsinity.serviceId", "obsinity-demo");
	}

	/* --------------------- hooks (notify receivers here) --------------------- */

	protected void onFlowStarted(TelemetryHolder opened, TelemetryHolder parent,
								 ProceedingJoinPoint joinPoint, FlowOptions options) {
		// record in execution (start) order
		addToBatch(opened);
		notifyFlowStarted(opened);
	}

	protected void onFlowFinishing(TelemetryHolder opened,
								   ProceedingJoinPoint joinPoint, FlowOptions options) {
		// mutate the same instance already in the batch
		setEndTime(opened, Instant.now());
		notifyFlowFinished(opened);
	}

	/**
	 * Called once at root-flow exit with the in-order list (same instances) of all finished holders.
	 */
	protected void onRootFlowFinished(List<TelemetryHolder> batch,
									  ProceedingJoinPoint joinPoint, FlowOptions options) {
		notifyRootFlowFinished(batch);
	}

	protected void onInvocationStarted(TelemetryHolder current,
									   ProceedingJoinPoint joinPoint, FlowOptions options) {
	}

	protected void onInvocationFinishing(TelemetryHolder current,
										 ProceedingJoinPoint joinPoint, FlowOptions options) {
	}

	protected void onSuccess(TelemetryHolder current, Object result,
							 ProceedingJoinPoint joinPoint, FlowOptions options) {
	}

	protected void onError(TelemetryHolder current, Throwable error,
						   ProceedingJoinPoint joinPoint, FlowOptions options) {
	}

	/* --------------------- step → event helpers --------------------- */

	private void recordStepEvent(ProceedingJoinPoint pjp, FlowOptions options, long startNanos, Throwable error) {
		TelemetryHolder curr = currentHolder();
		if (curr == null) return;

		String stepName = (options != null && options.name() != null && !options.name().isBlank())
			? options.name()
			: pjp.getSignature().getName();

		Instant now = Instant.now();
		long unixNanos = now.getEpochSecond() * 1_000_000_000L + now.getNano();
		long endNanos = System.nanoTime();
		long durNanos = (startNanos > 0L) ? (endNanos - startNanos) : 0L;

		Map<String, Object> attrs = new LinkedHashMap<>();
		attrs.put("phase", "finish");
		attrs.put("duration.nanos", durNanos);
		attrs.put("class", pjp.getSignature().getDeclaringTypeName());
		attrs.put("method", pjp.getSignature().getName());
		if (error == null) {
			attrs.put("result", "success");
		} else {
			attrs.put("result", "error");
			attrs.put("error.type", error.getClass().getName());
			attrs.put("error.message", String.valueOf(error.getMessage()));
		}

		TelemetryHolder.OEvent event = new TelemetryHolder.OEvent(
			stepName,
			unixNanos,
			endNanos,
			new TelemetryHolder.OAttributes(attrs),
			0
		);

		// mutate current holder (no snapshot)
		curr.events().add(event);
	}

	/* --------------------- receiver helpers --------------------- */

	private void notifyFlowStarted(TelemetryHolder holder) {
		if (receivers == null || receivers.isEmpty() || holder == null) return;
		for (TelemetryReceiver r : receivers) {
			try {
				r.flowStarted(holder);
			} catch (Exception ignore) {
			}
		}
	}

	private void notifyFlowFinished(TelemetryHolder holder) {
		if (receivers == null || receivers.isEmpty() || holder == null) return;
		for (TelemetryReceiver r : receivers) {
			try {
				r.flowFinished(holder);
			} catch (Exception ignore) {
			}
		}
	}

	private void notifyRootFlowFinished(List<TelemetryHolder> batch) {
		if (receivers == null || receivers.isEmpty() || batch == null || batch.isEmpty()) return;
		for (TelemetryReceiver r : receivers) {
			try {
				r.rootFlowFinished(batch);
			} catch (Exception ignore) {
			}
		}
	}

	/* --------------------- mutate helpers --------------------- */

	private void setEndTime(TelemetryHolder h, Instant end) {
		if (h == null) return;
		// assumes TelemetryHolder provides a setter for endTimestamp
		h.setEndTimestamp(end);
	}

	/* --------------------- Thread-local helpers --------------------- */

	public static TelemetryHolder currentHolder() {
		Deque<TelemetryHolder> d = CTX.get();
		return d.isEmpty() ? null : d.peekLast();
	}

	public static boolean hasActiveFlow() {
		return !CTX.get().isEmpty();
	}

	private static void push(TelemetryHolder h) {
		CTX.get().addLast(h);
	}

	private static void pop(TelemetryHolder expectedTop) {
		Deque<TelemetryHolder> d = CTX.get();
		if (!d.isEmpty() && d.peekLast() == expectedTop) d.removeLast();
		else d.clear();
	}

	/* --------------------- Batch helpers --------------------- */

	private static void startNewBatch() {
		BATCH.set(new ArrayList<>());
	}

	private static void addToBatch(TelemetryHolder finished) {
		List<TelemetryHolder> list = BATCH.get();
		if (list != null && finished != null) list.add(finished);
	}

	private static List<TelemetryHolder> finishBatchAndGet() {
		List<TelemetryHolder> out = BATCH.get();
		BATCH.remove(); // clean slate for the next root
		return out;
	}

	/* --------------------- utility --------------------- */

	private interface UnsafeRunnable {
		void run() throws Exception;
	}

	private static void safe(UnsafeRunnable r) {
		try {
			r.run();
		} catch (Exception ignore) {
		}
	}
}
