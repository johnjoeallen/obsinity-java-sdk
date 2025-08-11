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
	 */
	public final Object proceed(final ProceedingJoinPoint joinPoint, final FlowOptions options) throws Throwable {
		final boolean active = telemetryProcessorSupport.hasActiveFlow();
		final boolean flowAnnotation = options != null && options.autoFlowLevel() == StandardLevel.OFF;
		final boolean startsNewFlow = flowAnnotation || !active; // promote lone @Step to a flow
		final TelemetryHolder parent = telemetryProcessorSupport.currentHolder();
		final boolean opensRoot = startsNewFlow && parent == null;

		// Step (when not starting a new flow) → emit event
		final boolean stepAnnotated = options != null && options.autoFlowLevel() != StandardLevel.OFF;
		final boolean nestedStep = stepAnnotated && active && !startsNewFlow;
		final long stepStartNanos = nestedStep ? System.nanoTime() : 0L;

		final TelemetryHolder opened = startsNewFlow
			? openFlowIfNeeded(joinPoint, options, parent, opensRoot)
			: null;

		try {
			telemetryProcessorSupport.safe(new TelemetryProcessorSupport.UnsafeRunnable() {
				@Override
				public void run() {
					onInvocationStarted(telemetryProcessorSupport.currentHolder(), joinPoint, options);
				}
			});

			final Object result = joinPoint.proceed();

			if (nestedStep) {
				recordStepEvent(joinPoint, options, stepStartNanos, null);
			}

			telemetryProcessorSupport.safe(new TelemetryProcessorSupport.UnsafeRunnable() {
				@Override
				public void run() {
					onSuccess(telemetryProcessorSupport.currentHolder(), result, joinPoint, options);
				}
			});
			return result;
		} catch (final Throwable t) {
			if (nestedStep) {
				recordStepEvent(joinPoint, options, stepStartNanos, t);
			}
			telemetryProcessorSupport.safe(new TelemetryProcessorSupport.UnsafeRunnable() {
				@Override
				public void run() {
					onError(telemetryProcessorSupport.currentHolder(), t, joinPoint, options);
				}
			});
			throw t;
		} finally {
			telemetryProcessorSupport.safe(new TelemetryProcessorSupport.UnsafeRunnable() {
				@Override
				public void run() {
					onInvocationFinishing(telemetryProcessorSupport.currentHolder(), joinPoint, options);
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
			onFlowStarted(opened, parent, joinPoint, options);
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
			onFlowFinishing(opened, joinPoint, options); // mutates holder + notifies
			if (opensRoot) {
				final List<TelemetryHolder> batch = telemetryProcessorSupport.finishBatchAndGet();
				if (batch != null && !batch.isEmpty()) {
					onRootFlowFinished(batch, joinPoint, options);
				}
			}
		} catch (Exception ignored) {
			// Intentionally ignore receiver/side-effect errors on finish
		} finally {
			telemetryProcessorSupport.pop(opened);
		}
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
		// Intentionally empty: subclasses can populate baseline flow attributes
		return new TelemetryHolder.OAttributes(new LinkedHashMap<>());
	}

	protected List<TelemetryHolder.OEvent> buildEvents() {
		// Intentionally empty: events list starts empty and is appended as steps complete
		return new java.util.ArrayList<>();
	}

	protected List<TelemetryHolder.OLink> buildLinks() {
		// Intentionally empty: links are optional and can be added by subclasses
		return new java.util.ArrayList<>();
	}

	protected TelemetryHolder.OStatus buildStatus() {
		// Intentionally empty: status is optional; override to set initial status
		return null;
	}

	protected String resolveServiceId() {
		return System.getProperty("obsinity.serviceId", "obsinity-demo");
	}

	protected void onFlowStarted(final TelemetryHolder opened, final TelemetryHolder parent,
								 , final FlowOptions options) {
		telemetryProcessorSupport.addToBatch(opened);                 // record in execution (start) order
		telemetryProcessorSupport.notifyFlowStarted(receivers, opened);
	}

	protected void onFlowFinishing(final TelemetryHolder opened,
								   , final FlowOptions options) {
		telemetryProcessorSupport.setEndTime(opened, Instant.now());  // mutate the same instance already in the batch
		telemetryProcessorSupport.notifyFlowFinished(receivers, opened);
	}

	protected void onRootFlowFinished(final List<TelemetryHolder> batch,
									  , final FlowOptions options) {
		telemetryProcessorSupport.notifyRootFlowFinished(receivers, batch);
	}

	protected void onInvocationStarted(final TelemetryHolder current,
									   , final FlowOptions options) {
		// Intentionally empty: override for custom pre-invocation behavior
	}

	protected void onInvocationFinishing(final TelemetryHolder current,
										 , final FlowOptions options) {
		// Intentionally empty: override for custom post-invocation behavior
	}

	protected void onSuccess(final TelemetryHolder current, final Object result,
							 , final FlowOptions options) {
		// Intentionally empty: override to observe successful returns
	}

	protected void onError(final TelemetryHolder current, final Throwable error,
						   , final FlowOptions options) {
		// Intentionally empty: override to observe errors
	}

	private void recordStepEvent(final ProceedingJoinPoint joinPoint, final FlowOptions options, final long startNanos, final Throwable error) {
		final TelemetryHolder curr = telemetryProcessorSupport.currentHolder();
		if (curr == null) {
			return;
		}

		final Signature sig = joinPoint.getSignature();
		final String stepName;
		if (options != null && options.name() != null && !options.name().isBlank()) {
			stepName = options.name();
		} else {
			stepName = sig.getName();
		}

		final Instant now = Instant.now();
		final long unixNanos = telemetryProcessorSupport.unixNanos(now);
		final long endNanos = System.nanoTime();
		final long durNanos = startNanos > 0L ? (endNanos - startNanos) : 0L;

		final Map<String, Object> attrs = new LinkedHashMap<>();
		attrs.put("phase", "finish");
		attrs.put("duration.nanos", durNanos);
		attrs.put("class", sig.getDeclaringTypeName());
		attrs.put("method", sig.getName());
		if (error == null) {
			attrs.put("result", "success");
		} else {
			attrs.put("result", "error");
			attrs.put("error.type", error.getClass().getName());
			attrs.put("error.message", String.valueOf(error.getMessage()));
		}

		final TelemetryHolder.OEvent event = new TelemetryHolder.OEvent(
			stepName,
			unixNanos,
			endNanos,
			new TelemetryHolder.OAttributes(attrs),
			0
		);

		telemetryAttributeBinder.bind(event, joinPoint);
		curr.events().add(event); // mutate current holder (no snapshot)
	}
}
