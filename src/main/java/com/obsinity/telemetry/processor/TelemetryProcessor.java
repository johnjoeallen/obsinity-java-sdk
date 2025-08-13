package com.obsinity.telemetry.processor;

import com.obsinity.telemetry.aspect.FlowOptions;
import com.obsinity.telemetry.model.Lifecycle;
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
 */
@Component
@RequiredArgsConstructor
public class TelemetryProcessor {

	/** Binds attributes from JP context to a flow holder or an event. */
	private final TelemetryAttributeBinder telemetryAttributeBinder;
	/** Manages per-thread state (holder stack, batches) and small utilities. */
	private final TelemetryProcessorSupport telemetryProcessorSupport;
	/** Event dispatcher that routes to @OnEvent handlers. */
	private final TelemetryDispatchBus dispatchBus;

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

	private void finishFlowIfOpened(final TelemetryHolder opened,
									final boolean opensRoot,
									final ProceedingJoinPoint joinPoint,
									final FlowOptions options) {
		if (opened == null) {
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

	private String resolveStepName(final ProceedingJoinPoint joinPoint, final FlowOptions options) {
		if (options != null && options.name() != null && !options.name().isBlank()) {
			return options.name();
		}
		return joinPoint.getSignature().getName();
	}

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

		curr.endStepEvent(epochEnd, endNanoTime, updates);
	}

	/* ===================== overridable building blocks (documented) ===================== */

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

	protected TelemetryHolder.OResource buildResource() {
		final String sid = resolveServiceId();
		final Map<String, Object> resAttrs = new LinkedHashMap<>();
		resAttrs.put("service.name", sid);
		resAttrs.put("service.id", sid);
		return new TelemetryHolder.OResource(new TelemetryHolder.OAttributes(resAttrs));
	}

	protected TelemetryHolder.OAttributes buildAttributes(final ProceedingJoinPoint pjp, final FlowOptions opts) {
		return new TelemetryHolder.OAttributes(new LinkedHashMap<>());
	}

	protected List<TelemetryHolder.OEvent> buildEvents() {
		return new java.util.ArrayList<>();
	}

	protected List<TelemetryHolder.OLink> buildLinks() {
		return new java.util.ArrayList<>();
	}

	protected TelemetryHolder.OStatus buildStatus() {
		return null;
	}

	protected String resolveServiceId() {
		return System.getProperty("obsinity.serviceId", "obsinity-demo");
	}

	/* ===================== lifecycle hooks (dispatch via bus) ===================== */

	/** Flow just started: add to batch and dispatch START. */
	protected void onFlowStarted(final TelemetryHolder opened, final TelemetryHolder parent,
								 final FlowOptions options) {
		telemetryProcessorSupport.addToBatch(opened);
		dispatchBus.dispatch(Lifecycle.FLOW_STARTED, opened);
	}

	/** Flow is finishing now: set end time and dispatch FINISH. */
	protected void onFlowFinishing(final TelemetryHolder opened,
								   final FlowOptions options) {
		telemetryProcessorSupport.setEndTime(opened, Instant.now());
		dispatchBus.dispatch(Lifecycle.FLOW_FINISHED, opened);
	}

	/** Root flow finished: dispatch the whole batch en-masse. */
	protected void onRootFlowFinished(final List<TelemetryHolder> batch,
									  final FlowOptions options) {
		dispatchBus.dispatchRootFinished(batch);
	}

	/* ===================== optional per-invocation hooks (no-op by default) ===================== */

	protected void onInvocationStarted(final TelemetryHolder current,
									   final FlowOptions options) { }

	protected void onInvocationFinishing(final TelemetryHolder current,
										 final FlowOptions options) { }

	protected void onSuccess(final TelemetryHolder current, final Object result,
							 final FlowOptions options) { }

	protected void onError(final TelemetryHolder current, final Throwable error,
						   final FlowOptions options) { }
}
