package com.obsinity.telemetry.processor;

import com.obsinity.telemetry.aspect.FlowOptions;
import com.obsinity.telemetry.model.TelemetryHolder;
import com.obsinity.telemetry.receivers.TelemetryDispatchBus;
import com.obsinity.telemetry.utils.TelemetryIdGenerator;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.spi.StandardLevel;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Orchestrates flow and step telemetry lifecycle.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li><strong>Flow orchestration</strong> — Detects whether the current invocation starts a new flow
 *       (root or nested) and manages the holder stack via {@link TelemetryProcessorSupport}.</li>
 *   <li><strong>Step events</strong> — For nested {@code @Step}s, creates the event at <em>entry</em>,
 *       binds {@code @Attribute}-annotated parameters immediately, and finalizes on exit (success/error).</li>
 *   <li><strong>Attribute binding</strong> — Uses {@link TelemetryAttributeBinder} to enrich the flow or the
 *       current step’s event with attribute data derived from the join point context.</li>
 *   <li><strong>Batching</strong> — Maintains an in-order per-root batch of finished flows, emitted when
 *       the root closes.</li>
 *   <li><strong>Asynchronous delivery</strong> — Signals lifecycle stages (start/finish/root-finish) to
 *       receivers asynchronously via {@link TelemetryDispatchBus} (per-receiver bounded queue + worker).</li>
 * </ul>
 *
 * <h2>Event timing model</h2>
 * <ul>
 *   <li>Flow start/end wall-clock stamps are captured as {@link Instant} and epoch nanos (for export).</li>
 *   <li>Step events store a transient, monotonic {@code startNanoTime} at entry; duration is computed as
 *       {@code System.nanoTime() - startNanoTime} on exit to avoid wall-clock skew.</li>
 * </ul>
 *
 * <h2>Thread-safety</h2>
 * <p>Active flow context is kept in per-thread stacks and batches inside {@link TelemetryProcessorSupport}.
 * This processor never shares a {@link TelemetryHolder} across threads during construction; delivery to receivers
 * is decoupled and performed by the dispatch bus on background threads.</p>
 */
@Component
@RequiredArgsConstructor
public class TelemetryProcessor {

	/** Binds attributes from JP context to a flow holder or an event. */
	private final TelemetryAttributeBinder telemetryAttributeBinder;
	/** Manages per-thread state (holder stack, batches) and small utilities. */
	private final TelemetryProcessorSupport telemetryProcessorSupport;
	/** Asynchronous fan-out to receivers (per-receiver queue + worker). */
	private final TelemetryDispatchBus dispatchBus;

	/**
	 * Execute the intercepted method while emitting telemetry for flows and steps.
	 *
	 * <p><strong>Algorithm (high level):</strong></p>
	 * <ol>
	 *   <li>Decide whether this invocation <em>starts a new flow</em> (explicit {@code @Flow} or
	 *       auto-promotion of a lone {@code @Step}). If yes, create and push a {@link TelemetryHolder}.</li>
	 *   <li>If this is a <em>nested step</em> inside an active flow, create the step {@code OEvent} at entry,
	 *       bind parameters, and push it on the holder’s event stack (so app code can {@code contextPut}).</li>
	 *   <li>Proceed with the original invocation.</li>
	 *   <li>On success/error, finalize the current step event if one was opened.</li>
	 *   <li>On method exit, finalize and pop the flow if one was opened; if it was a root, emit the batch.</li>
	 * </ol>
	 *
	 * @param joinPoint the intercepted invocation
	 * @param options   resolved flow/step options from annotations (never {@code null} for advised methods)
	 * @return the original method’s return value
	 * @throws Throwable any user code exception is rethrown after telemetry finalization
	 */
	public final Object proceed(final ProceedingJoinPoint joinPoint, final FlowOptions options) throws Throwable {
		final boolean active = telemetryProcessorSupport.hasActiveFlow();
		final boolean flowAnnotation = options != null && options.autoFlowLevel() == StandardLevel.OFF;
		// Promote lone @Step to a flow if there isn't an active one.
		final boolean startsNewFlow = flowAnnotation || !active;

		final TelemetryHolder parent = telemetryProcessorSupport.currentHolder();
		final boolean opensRoot = startsNewFlow && parent == null;

		// Nested @Step → event (when we are not starting a flow here)
		final boolean stepAnnotated = options != null && options.autoFlowLevel() != StandardLevel.OFF;
		final boolean nestedStep = stepAnnotated && active && !startsNewFlow;

		// Flow entry
		final TelemetryHolder opened = startsNewFlow
			? openFlowIfNeeded(joinPoint, options, parent, opensRoot)
			: null;

		// Step entry (create event immediately so param binding and contextPut land on the event)
		if (nestedStep) {
			final TelemetryHolder curr = telemetryProcessorSupport.currentHolder();
			if (curr != null) {
				final Instant now = Instant.now();
				final long epochStart = telemetryProcessorSupport.unixNanos(now);
				final long startNanoTime = System.nanoTime();

				final Signature sig = joinPoint.getSignature();
				final Map<String, Object> base = new LinkedHashMap<>();
				base.put("class", sig.getDeclaringTypeName());
				base.put("method", sig.getName());

				final TelemetryHolder.OEvent ev = curr.beginStepEvent(
					resolveStepName(joinPoint, options),
					epochStart,
					startNanoTime,
					new TelemetryHolder.OAttributes(base)
				);
				// Enrich from @Attribute params (event already exists)
				telemetryAttributeBinder.bind(ev, joinPoint);
			}
		}

		try {
			telemetryProcessorSupport.safe(() ->
				onInvocationStarted(telemetryProcessorSupport.currentHolder(), options)
			);

			final Object result = joinPoint.proceed();

			if (nestedStep) {
				finalizeCurrentEvent(null);
			}

			telemetryProcessorSupport.safe(() ->
				onSuccess(telemetryProcessorSupport.currentHolder(), result, options)
			);
			return result;
		} catch (final Throwable t) {
			if (nestedStep) {
				finalizeCurrentEvent(t);
			}
			telemetryProcessorSupport.safe(() ->
				onError(telemetryProcessorSupport.currentHolder(), t, options)
			);
			throw t;
		} finally {
			telemetryProcessorSupport.safe(() ->
				onInvocationFinishing(telemetryProcessorSupport.currentHolder(), options)
			);
			finishFlowIfOpened(opened, opensRoot, joinPoint, options);
		}
	}

	/* ===================== localized helpers (documented) ===================== */

	/**
	 * Start a new flow holder, bind entry attributes, push it on the thread-local stack, and signal START.
	 *
	 * <p>Also initializes a new per-root batch if this is a root flow.</p>
	 */
	private TelemetryHolder openFlowIfNeeded(final ProceedingJoinPoint joinPoint,
											 final FlowOptions options,
											 final TelemetryHolder parent,
											 final boolean opensRoot) {
		final Instant now = Instant.now();
		final long epochNanos = telemetryProcessorSupport.unixNanos(now);

		final String traceId;
		final String parentSpanId;
		final String correlationId;

		if (parent == null) {
			traceId = TelemetryIdGenerator.hex128(TelemetryIdGenerator.generate());
			parentSpanId = null;
			correlationId = traceId; // for roots, correlation = trace
		} else {
			traceId = parent.traceId();
			parentSpanId = parent.spanId();
			if (parent.correlationId() != null && !parent.correlationId().isBlank()) {
				correlationId = parent.correlationId();
			} else {
				correlationId = parent.traceId();
			}
		}

		final String spanId = TelemetryIdGenerator.hex64lsb(TelemetryIdGenerator.generate());

		final TelemetryHolder opened = Objects.requireNonNull(
			createFlowHolder(joinPoint, options, parent, traceId, spanId, parentSpanId, correlationId, now, epochNanos),
			"createFlowHolder() must not return null when starting a flow"
		);

		// Bind @Attribute params to the flow itself (baseline attributes).
		telemetryAttributeBinder.bind(opened, joinPoint);

		if (opensRoot) {
			telemetryProcessorSupport.startNewBatch();
		}

		telemetryProcessorSupport.push(opened);
		try {
			onFlowStarted(opened, parent, options);
		} catch (Exception ignored) {
			// Intentionally ignore receiver/side-effect errors on start.
		}
		return opened;
	}

	/**
	 * Finalize a flow if one was opened by {@link #openFlowIfNeeded}, signal FINISH,
	 * and, if root, signal ROOT_FINISH with the completed batch.
	 */
	private void finishFlowIfOpened(final TelemetryHolder opened,
									final boolean opensRoot,
									final ProceedingJoinPoint joinPoint,
									final FlowOptions options) {
		if (opened == null) {
			// No flow started here.
			return;
		}
		try {
			onFlowFinishing(opened, options); // mutates the same holder instance (sets end time)
			if (opensRoot) {
				final List<TelemetryHolder> batch = telemetryProcessorSupport.finishBatchAndGet();
				if (batch != null && !batch.isEmpty()) {
					onRootFlowFinished(batch, options);
				}
			}
		} catch (Exception ignored) {
			// Intentionally ignore receiver/side-effect errors on finish.
		} finally {
			telemetryProcessorSupport.pop(opened);
		}
	}

	/**
	 * Resolve the step name from {@link FlowOptions#name()} or fall back to the Java method name.
	 */
	private String resolveStepName(final ProceedingJoinPoint joinPoint, final FlowOptions options) {
		if (options != null && options.name() != null && !options.name().isBlank()) {
			return options.name();
		}
		return joinPoint.getSignature().getName();
	}

	/**
	 * Finalize the current step event (if any) by setting result attributes and
	 * closing the event with wall-clock end time + monotonic duration.
	 *
	 * @param errorOrNull {@code null} on success; the thrown error otherwise
	 */
	private void finalizeCurrentEvent(final Throwable errorOrNull) {
		final TelemetryHolder curr = telemetryProcessorSupport.currentHolder();
		if (curr == null) {
			return;
		}
		final Instant now = Instant.now();
		final long epochEnd = telemetryProcessorSupport.unixNanos(now);
		final long endNanoTime = System.nanoTime();

		final Map<String, Object> updates = new LinkedHashMap<>();
		if (errorOrNull == null) {
			updates.put("result", "success");
		} else {
			updates.put("result", "error");
			updates.put("error.type", errorOrNull.getClass().getName());
			updates.put("error.message", String.valueOf(errorOrNull.getMessage()));
		}

		// Holder computes duration from its event's startNanoTime and replaces
		// the last list element with a copy that carries endEpochNanos.
		curr.endStepEvent(epochEnd, endNanoTime, updates);
	}

	/* ===================== overridable building blocks (documented) ===================== */

	/**
	 * Construct a new {@link TelemetryHolder} for a flow.
	 * <p>Subclasses can override to add baseline resource/attributes/events/links/status.</p>
	 */
	protected TelemetryHolder createFlowHolder(final ProceedingJoinPoint pjp, final FlowOptions opts, final TelemetryHolder parent,
											   final String traceId, final String spanId, final String parentSpanId, final String correlationId,
											   final Instant ts, final long tsNanos) {

		final TelemetryHolder.OResource resource = buildResource();
		final TelemetryHolder.OAttributes attributes = buildAttributes(pjp, opts);
		final List<TelemetryHolder.OEvent> events = buildEvents();
		final List<TelemetryHolder.OLink> links = buildLinks();
		final TelemetryHolder.OStatus status = buildStatus();
		final Map<String, Object> extensions = new LinkedHashMap<>();

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

	/** Build the flow resource wrapper (default adds service.* attributes). */
	protected TelemetryHolder.OResource buildResource() {
		final String sid = resolveServiceId();
		final Map<String, Object> resAttrs = new LinkedHashMap<>();
		resAttrs.put("service.name", sid);
		resAttrs.put("service.id", sid);
		return new TelemetryHolder.OResource(new TelemetryHolder.OAttributes(resAttrs));
	}

	/** Build baseline flow attributes (subclasses can populate as needed). */
	protected TelemetryHolder.OAttributes buildAttributes(final ProceedingJoinPoint pjp, final FlowOptions opts) {
		return new TelemetryHolder.OAttributes(new LinkedHashMap<>());
	}

	/** Create an empty, mutable events list. */
	protected List<TelemetryHolder.OEvent> buildEvents() {
		return new java.util.ArrayList<>();
	}

	/** Create an empty, mutable links list. */
	protected List<TelemetryHolder.OLink> buildLinks() {
		return new java.util.ArrayList<>();
	}

	/** Create initial status (default {@code null}). */
	protected TelemetryHolder.OStatus buildStatus() {
		return null;
	}

	/** Resolve the owning service id; defaults to {@code obsinity-demo}. */
	protected String resolveServiceId() {
		return System.getProperty("obsinity.serviceId", "obsinity-demo");
	}

	/* ===================== lifecycle hooks (dispatch via bus) ===================== */

	/**
	 * Flow just started. Records it in the current root batch (execution order)
	 * and signals receivers asynchronously via the dispatch bus.
	 */
	protected void onFlowStarted(final TelemetryHolder opened, final TelemetryHolder parent,
								 final FlowOptions options) {
		telemetryProcessorSupport.addToBatch(opened);
		dispatchBus.enqueueStart(opened);
	}

	/**
	 * Flow is finishing now. Sets the end timestamp and signals receivers asynchronously.
	 */
	protected void onFlowFinishing(final TelemetryHolder opened,
								   final FlowOptions options) {
		telemetryProcessorSupport.setEndTime(opened, Instant.now());
		dispatchBus.enqueueFinish(opened);
	}

	/**
	 * Root flow finished. Emits the in-order list of finished holders for this root.
	 */
	protected void onRootFlowFinished(final List<TelemetryHolder> batch,
									  final FlowOptions options) {
		dispatchBus.enqueueRootFinish(batch);
	}

	/* ===================== optional per-invocation hooks (no-op by default) ===================== */

	/** Called right before the method is invoked (after step/flow entry binding). */
	protected void onInvocationStarted(final TelemetryHolder current,
									   final FlowOptions options) {
		// Intentionally empty: override for custom pre-invocation behavior.
	}

	/** Called right after the method finishes (before flow/step finalization). */
	protected void onInvocationFinishing(final TelemetryHolder current,
										 final FlowOptions options) {
		// Intentionally empty: override for custom post-invocation behavior.
	}

	/** Called if the method returns normally. */
	protected void onSuccess(final TelemetryHolder current, final Object result,
							 final FlowOptions options) {
		// Intentionally empty: override to observe successful returns.
	}

	/** Called if the method throws. */
	protected void onError(final TelemetryHolder current, final Throwable error,
						   final FlowOptions options) {
		// Intentionally empty: override to observe errors.
	}
}
