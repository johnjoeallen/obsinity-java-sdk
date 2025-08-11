package com.obsinity.telemetry.processor;

import com.obsinity.telemetry.aspect.FlowOptions;
import com.obsinity.telemetry.model.TelemetryHolder;
import com.obsinity.telemetry.receivers.TelemetryReceiver;
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

@Component
@RequiredArgsConstructor
public class TelemetryProcessor {

	private final TelemetryAttributeBinder telemetryAttributeBinder;
	private final TelemetryProcessorSupport telemetryProcessorSupport;
	private final List<TelemetryReceiver> receivers;

	/**
	 * Main orchestration of flow start/finish and step event emission.
	 * - If a new flow starts, open the holder, push it, notify, and (if root) start a batch.
	 * - For nested steps, create the event at entry, bind params immediately, then finalize on exit.
	 */
	public final Object proceed(final ProceedingJoinPoint joinPoint, final FlowOptions options) throws Throwable {
		final boolean active = telemetryProcessorSupport.hasActiveFlow();
		final boolean flowAnnotation = options != null && options.autoFlowLevel() == StandardLevel.OFF;
		final boolean startsNewFlow = flowAnnotation || !active; // promote lone @Step to a flow
		final TelemetryHolder parent = telemetryProcessorSupport.currentHolder();
		final boolean opensRoot = startsNewFlow && parent == null;

		final boolean stepAnnotated = options != null && options.autoFlowLevel() != StandardLevel.OFF;
		final boolean nestedStep = stepAnnotated && active && !startsNewFlow;

		final TelemetryHolder opened = startsNewFlow
			? openFlowIfNeeded(joinPoint, options, parent, opensRoot)
			: null;

		// Step entry: create & append the event, push it so app can contextPut() into it immediately
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

				// Enrich with @Attribute-annotated params at entry (event already exists)
				telemetryAttributeBinder.bind(ev, joinPoint);
			}
		}

		try {
			telemetryProcessorSupport.safe(new TelemetryProcessorSupport.UnsafeRunnable() {
				@Override
				public void run() {
					onInvocationStarted(telemetryProcessorSupport.currentHolder(), options);
				}
			});

			final Object result = joinPoint.proceed();

			if (nestedStep) {
				finalizeCurrentEvent(null);
			}

			telemetryProcessorSupport.safe(new TelemetryProcessorSupport.UnsafeRunnable() {
				@Override
				public void run() {
					onSuccess(telemetryProcessorSupport.currentHolder(), result, options);
				}
			});
			return result;
		} catch (final Throwable t) {
			if (nestedStep) {
				finalizeCurrentEvent(t);
			}
			telemetryProcessorSupport.safe(new TelemetryProcessorSupport.UnsafeRunnable() {
				@Override
				public void run() {
					onError(telemetryProcessorSupport.currentHolder(), t, options);
				}
			});
			throw t;
		} finally {
			telemetryProcessorSupport.safe(new TelemetryProcessorSupport.UnsafeRunnable() {
				@Override
				public void run() {
					onInvocationFinishing(telemetryProcessorSupport.currentHolder(), options);
				}
			});
			finishFlowIfOpened(opened, opensRoot, joinPoint, options);
		}
	}

	/* ===================== simplified helpers (local to processor) ===================== */

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
			correlationId = traceId; // root: corr = trace
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

		telemetryAttributeBinder.bind(opened, joinPoint);

		if (opensRoot) {
			telemetryProcessorSupport.startNewBatch();
		}

		telemetryProcessorSupport.push(opened);
		try {
			onFlowStarted(opened, parent, options);
		} catch (Exception ignored) {
			// Intentionally ignore receiver/side-effect errors on start
		}
		return opened;
	}

	private void finishFlowIfOpened(final TelemetryHolder opened,
									final boolean opensRoot,
									final ProceedingJoinPoint joinPoint,
									final FlowOptions options) {
		if (opened == null) {
			// Intentionally empty: nothing to finish
			return;
		}
		try {
			onFlowFinishing(opened, options); // mutates holder + notifies
			if (opensRoot) {
				final List<TelemetryHolder> batch = telemetryProcessorSupport.finishBatchAndGet();
				if (batch != null && !batch.isEmpty()) {
					onRootFlowFinished(batch, options);
				}
			}
		} catch (Exception ignored) {
			// Intentionally ignore receiver/side-effect errors on finish
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

		// Holder will set phase=finish, compute duration from monotonic nanos,
		// and replace the last event instance with one carrying endEpochNanos.
		curr.endStepEvent(epochEnd, endNanoTime, updates);
	}

	/* --------------------- existing methods below unchanged --------------------- */

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

	protected void onFlowStarted(final TelemetryHolder opened, final TelemetryHolder parent,
								 final FlowOptions options) {
		telemetryProcessorSupport.addToBatch(opened);                 // record in execution (start) order
		telemetryProcessorSupport.notifyFlowStarted(receivers, opened);
	}

	protected void onFlowFinishing(final TelemetryHolder opened,
								   final FlowOptions options) {
		telemetryProcessorSupport.setEndTime(opened, Instant.now());  // mutate the same instance already in the batch
		telemetryProcessorSupport.notifyFlowFinished(receivers, opened);
	}

	protected void onRootFlowFinished(final List<TelemetryHolder> batch,
									  final FlowOptions options) {
		telemetryProcessorSupport.notifyRootFlowFinished(receivers, batch);
	}

	protected void onInvocationStarted(final TelemetryHolder current,
									   final FlowOptions options) {
		// Intentionally empty: override for custom pre-invocation behavior
	}

	protected void onInvocationFinishing(final TelemetryHolder current,
										 final FlowOptions options) {
		// Intentionally empty: override for custom post-invocation behavior
	}

	protected void onSuccess(final TelemetryHolder current, final Object result,
							 final FlowOptions options) {
		// Intentionally empty: override to observe successful returns
	}

	protected void onError(final TelemetryHolder current, final Throwable error,
						   final FlowOptions options) {
		// Intentionally empty: override to observe errors
	}
}
