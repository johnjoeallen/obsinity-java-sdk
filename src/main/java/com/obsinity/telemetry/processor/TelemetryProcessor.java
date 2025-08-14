package com.obsinity.telemetry.processor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.springframework.stereotype.Component;

import com.obsinity.telemetry.annotations.OrphanAlert;
import com.obsinity.telemetry.aspect.FlowOptions;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.OAttributes;
import com.obsinity.telemetry.model.OEvent;
import com.obsinity.telemetry.model.OLink;
import com.obsinity.telemetry.model.OResource;
import com.obsinity.telemetry.model.OStatus;
import com.obsinity.telemetry.model.TelemetryHolder;
import com.obsinity.telemetry.receivers.TelemetryDispatchBus;
import com.obsinity.telemetry.utils.TelemetryIdGenerator;

/** Orchestrates flow and step telemetry lifecycle. */
@Component
@RequiredArgsConstructor
public class TelemetryProcessor {

	/** Binds attributes/context from the join-point to a holder. */
	private final TelemetryAttributeBinder telemetryAttributeBinder;
	/** Manages per-thread state (holder stack, batches) and utilities. */
	private final TelemetryProcessorSupport telemetryProcessorSupport;
	/** Event dispatcher routing to @OnEvent handlers. */
	private final TelemetryDispatchBus dispatchBus;

	public final Object proceed(final ProceedingJoinPoint joinPoint, final FlowOptions options) throws Throwable {
		final boolean active = telemetryProcessorSupport.hasActiveFlow();
		final boolean isFlowMethod = options != null && options.isFlowMethod();
		final boolean isStepMethod = options != null && options.isStepMethod();

		// Start a new flow for @Flow OR for an orphan @Step (promotion).
		final boolean startsNewFlow = isFlowMethod || (isStepMethod && !active);

		final TelemetryHolder parent = telemetryProcessorSupport.currentHolder();
		final boolean opensRoot = startsNewFlow && parent == null;

		// Nested step (no promotion): @Step invoked while a flow is active.
		final boolean nestedStep = isStepMethod && active && !startsNewFlow;

		// Orphan @Step: log prior to opening the promoted flow.
		if (isStepMethod && !active) {
			final OrphanAlert.Level level = (options != null && options.orphanAlertLevel() != null)
					? options.orphanAlertLevel()
					: OrphanAlert.Level.ERROR; // default
			final String stepName = resolveStepName(joinPoint, options);
			telemetryProcessorSupport.logOrphanStep(stepName, level);
		}

		// Flow entry (@Flow or promoted @Step)
		final TelemetryHolder opened = startsNewFlow ? openFlowIfNeeded(joinPoint, options, parent, opensRoot) : null;

		// Mark origin for flows or promoted steps via attributes only
		if (startsNewFlow && opened != null) {
			if (isStepMethod && !active) {
				// Orphan @Step promoted to flow
				opened.attributes().put("origin", "STEP_FLOW");
				opened.attributes().put("step.origin", "promoted");
				opened.attributes().put("step.name", resolveStepName(joinPoint, options));
			} else {
				// Regular @Flow
				opened.attributes().put("origin", "FLOW");
			}
		}

		// Step entry: create a temporary step-holder so handlers/binders behave like flows; fold later into parent.
		if (nestedStep) {
			final TelemetryHolder currParent = telemetryProcessorSupport.currentHolder();
			if (currParent != null) {
				final Instant now = Instant.now();
				final long epochStart = telemetryProcessorSupport.unixNanos(now);
				final long monoStart = System.nanoTime();

				final Signature sig = joinPoint.getSignature();
				final Map<String, Object> base = new LinkedHashMap<>();
				base.put("class", sig.getDeclaringTypeName());
				base.put("method", sig.getName());

				final String stepName = resolveStepName(joinPoint, options);

				final TelemetryHolder stepHolder = TelemetryHolder.builder()
						.name(stepName)
						.timestamp(now)
						.timeUnixNano(epochStart)
						.traceId(currParent.traceId())
						.spanId(TelemetryIdGenerator.hex64lsb(TelemetryIdGenerator.generate()))
						.parentSpanId(currParent.spanId())
						.kind(options.spanKind())
						.resource(buildResource()) // same service.id/name as flows
						.attributes(new OAttributes(base))
						.events(new java.util.ArrayList<>())
						.links(new java.util.ArrayList<>())
						.status(buildStatus())
						.serviceId(resolveServiceId())
						.correlationId(
								currParent.correlationId() != null ? currParent.correlationId() : currParent.traceId())
						.synthetic(Boolean.FALSE)
						.step(true) // nested temporary step-holder (will be folded into parent)
						.startNanoTime(monoStart) // use for accurate duration on fold
						.build();

				// Mark origin via attributes
				stepHolder.attributes().put("origin", "FLOW_STEP");
				stepHolder.attributes().put("step.origin", "nested");
				stepHolder.attributes().put("step.name", stepName);

				// Enrich from method params onto the step-holder (so handlers see them)
				telemetryAttributeBinder.bind(stepHolder, joinPoint);

				// Make it current and notify handlers (behaves like a real flow)
				telemetryProcessorSupport.push(stepHolder);
				onFlowStarted(stepHolder, currParent, options); // guarded to avoid batching for steps
			}
		}

		try {
			telemetryProcessorSupport.safe(
					() -> onInvocationStarted(telemetryProcessorSupport.currentHolder(), options));

			final Object result = joinPoint.proceed();

			if (nestedStep) {
				finalizeStepAsEvent(null);
			}

			telemetryProcessorSupport.safe(() -> onSuccess(telemetryProcessorSupport.currentHolder(), result, options));
			return result;
		} catch (final Throwable t) {
			if (nestedStep) {
				finalizeStepAsEvent(t);
			}
			telemetryProcessorSupport.safe(() -> onError(telemetryProcessorSupport.currentHolder(), t, options));
			throw t;
		} finally {
			telemetryProcessorSupport.safe(
					() -> onInvocationFinishing(telemetryProcessorSupport.currentHolder(), options));
			finishFlowIfOpened(opened, opensRoot, joinPoint, options);
		}
	}

	/* ===================== localized helpers (documented) ===================== */

	private TelemetryHolder openFlowIfNeeded(
			final ProceedingJoinPoint joinPoint,
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
				createFlowHolder(
						joinPoint, options, parent, traceId, spanId, parentSpanId, correlationId, now, epochNanos),
				"createFlowHolder() must not return null when starting a flow");

		// Bind method params to the flow (baseline attributes/context).
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

	private void finishFlowIfOpened(
			final TelemetryHolder opened,
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

	/**
	 * Finish a temporary step-holder: dispatch FLOW_FINISHED to handlers, fold into the parent as an OEvent, then
	 * discard the step-holder (no batching).
	 */
	private void finalizeStepAsEvent(final Throwable errorOrNull) {
		final TelemetryHolder stepHolder = telemetryProcessorSupport.currentHolder();
		if (stepHolder == null || !stepHolder.isStep()) {
			return;
		}

		final Instant now = Instant.now();
		final long epochEnd = telemetryProcessorSupport.unixNanos(now);
		final long monoEnd = System.nanoTime();

		// decorate step attributes with result/error
		if (errorOrNull == null) {
			stepHolder.attributes().put("result", "success");
		} else {
			stepHolder.attributes().put("result", "error");
			stepHolder.attributes().put("error.type", errorOrNull.getClass().getName());
			stepHolder.attributes().put("error.message", String.valueOf(errorOrNull.getMessage()));
			stepHolder.setThrowable(errorOrNull);
		}
		stepHolder.setEndTimestamp(now);

		// Notify handlers that this "flow-like step" has finished
		dispatchBus.dispatch(Lifecycle.FLOW_FINISHED, stepHolder);

		// Fold into parent
		final TelemetryHolder parent = telemetryProcessorSupport.currentHolderBelowTop();
		if (parent != null) {
			final long duration = (stepHolder.getStartNanoTime() > 0L) ? (monoEnd - stepHolder.getStartNanoTime()) : 0L;
			stepHolder.attributes().put("duration.nanos", duration);
			stepHolder.attributes().put("phase", "finish");

			final long epochStart = stepHolder.timeUnixNano() != null
					? stepHolder.timeUnixNano()
					: telemetryProcessorSupport.unixNanos(stepHolder.timestamp());

			final OEvent folded = new OEvent(
					stepHolder.name(),
					epochStart,
					epochEnd,
					stepHolder.attributes(),
					0,
					stepHolder.getStartNanoTime(),
					stepHolder.getEventContext() // carry ephemeral step context into the event (still non-serialized)
					);

			parent.events().add(folded);
		}

		// Discard: do not batch step holders
		telemetryProcessorSupport.pop(stepHolder);
	}

	/* ===================== overridable building blocks (documented) ===================== */

	protected TelemetryHolder createFlowHolder(
			final ProceedingJoinPoint pjp,
			final FlowOptions opts,
			final TelemetryHolder parent,
			final String traceId,
			final String spanId,
			final String parentSpanId,
			final String correlationId,
			final Instant ts,
			final long tsNanos) {

		final OResource resource = buildResource();
		final OAttributes attributes = buildAttributes(pjp, opts);
		final List<OEvent> events = buildEvents();
		final List<OLink> links = buildLinks();
		final OStatus status = buildStatus();

		return TelemetryHolder.builder()
				.name(opts.name())
				.timestamp(ts)
				.timeUnixNano(tsNanos)
				.traceId(traceId)
				.spanId(spanId)
				.parentSpanId(parentSpanId)
				.kind(opts.spanKind())
				.resource(resource)
				.attributes(attributes != null ? attributes : new OAttributes(new LinkedHashMap<>()))
				.events(events)
				.links(links)
				.status(status)
				.serviceId(resolveServiceId())
				.correlationId(correlationId)
				.synthetic(Boolean.FALSE)
				.build();
	}

	protected OResource buildResource() {
		final String sid = resolveServiceId();
		final Map<String, Object> resAttrs = new LinkedHashMap<>();
		resAttrs.put("service.name", sid);
		resAttrs.put("service.id", sid);
		return new OResource(new OAttributes(resAttrs));
	}

	protected OAttributes buildAttributes(final ProceedingJoinPoint pjp, final FlowOptions opts) {
		return new OAttributes(new LinkedHashMap<>());
	}

	protected List<OEvent> buildEvents() {
		return new java.util.ArrayList<>();
	}

	protected List<OLink> buildLinks() {
		return new java.util.ArrayList<>();
	}

	protected OStatus buildStatus() {
		return null;
	}

	protected String resolveServiceId() {
		return System.getProperty("obsinity.serviceId", "obsinity-demo");
	}

	/* ===================== lifecycle hooks (dispatch via bus) ===================== */

	/** Flow just started: add to batch (if root/flow) and dispatch START. */
	protected void onFlowStarted(
			final TelemetryHolder opened, final TelemetryHolder parent, final FlowOptions options) {
		// Do NOT add step-holders to batch
		if (opened != null && !opened.isStep()) {
			telemetryProcessorSupport.addToBatch(opened);
		}
		dispatchBus.dispatch(Lifecycle.FLOW_STARTED, opened);
	}

	/** Flow is finishing now: set end time and dispatch FINISH. (Not used for step-holders.) */
	protected void onFlowFinishing(final TelemetryHolder opened, final FlowOptions options) {
		telemetryProcessorSupport.setEndTime(opened, Instant.now());
		dispatchBus.dispatch(Lifecycle.FLOW_FINISHED, opened);
	}

	/** Root flow finished: dispatch the whole batch en masse. */
	protected void onRootFlowFinished(final List<TelemetryHolder> batch, final FlowOptions options) {
		dispatchBus.dispatchRootFinished(batch);
	}

	/* ===================== optional per-invocation hooks (no-op by default) ===================== */

	protected void onInvocationStarted(final TelemetryHolder current, final FlowOptions options) {}

	protected void onInvocationFinishing(final TelemetryHolder current, final FlowOptions options) {}

	protected void onSuccess(final TelemetryHolder current, final Object result, final FlowOptions options) {}

	protected void onError(final TelemetryHolder current, final Throwable error, final FlowOptions options) {}
}
