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
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Core execution engine that drives Obsinity's annotation-based telemetry.
 *
 * <p>This component is invoked by the {@code TelemetryAspect} around advice whenever a method
 * annotated with {@code @Flow} or {@code @Step} is executed. It is responsible for:</p>
 *
 * <ul>
 *   <li>Maintaining per-thread flow context as a stack (root flow at the bottom, current on top).</li>
 *   <li>Deriving whether the current invocation starts a new flow or participates in an existing one.</li>
 *   <li>Allocating trace/span identifiers (UUIDv7-based) for new flows and wiring parent/child links.</li>
 *   <li>Capturing timestamps and building {@link TelemetryHolder} instances that mirror OTEL data.</li>
 *   <li>Calling the original method via {@link ProceedingJoinPoint#proceed()} and surfacing exceptions unchanged.</li>
 *   <li>Notifying registered {@link TelemetryReceiver}s of flow lifecycle events (start/finish).</li>
 * </ul>
 *
 * <h2>Flow vs Step</h2>
 * <p>By convention in this processor, a method annotated with {@code @Flow} yields a new "root" flow
 * (or a nested flow if another flow is active). A lone {@code @Step} (i.e., when no flow is active)
 * is <em>promoted</em> to a flow so that all telemetry is always attached to a flow context.
 * This behavior is driven by {@link FlowOptions}:
 * when {@link FlowOptions#autoFlowLevel()} equals {@link StandardLevel#OFF}, it denotes an explicit
 * {@code @Flow}; otherwise it denotes a {@code @Step} (possibly marked with {@code @AutoFlow}).</p>
 *
 * <h2>Threading model</h2>
 * <p>Each thread holds its own stack of {@link TelemetryHolder}s via an {@link InheritableThreadLocal}.
 * The top of the stack represents the current flow. This implementation assumes that a flow does not
 * migrate across threads.</p>
 *
 * <h2>Receivers</h2>
 * <p>Lifecycle notifications are sent to all configured {@link TelemetryReceiver}s:</p>
 * <ul>
 *   <li>{@link TelemetryReceiver#flowStarted(TelemetryHolder)} — called after a new flow is opened.</li>
 *   <li>{@link TelemetryReceiver#flowFinished(TelemetryHolder)} — called right before the flow is closed.
 *       The supplied holder has its {@code endTimestamp} set.</li>
 * </ul>
 *
 * <h2>Exceptions</h2>
 * <p>Any exception thrown by the intercepted method is propagated exactly as-is. Receiver failures are
 * ignored so that telemetry never interferes with application behavior.</p>
 *
 * @see com.obsinity.telemetry.aspects.TelemetryAspect
 * @see FlowOptions
 * @see TelemetryHolder
 * @see TelemetryReceiver
 */
@Component
@RequiredArgsConstructor
public class TelemetryProcessor {

	/**
	 * Per-thread stack of active flows. The top (tail) element is the current flow.
	 * <p><strong>Note:</strong> Uses {@link InheritableThreadLocal} so child threads inherit the
	 * parent value at creation time. This class expects flows to remain on the same thread; if you
	 * dispatch work to other threads, consider copying only the identifiers you need.</p>
	 */
	private static final InheritableThreadLocal<Deque<TelemetryHolder>> CTX =
		new InheritableThreadLocal<>() {
			@Override protected Deque<TelemetryHolder> initialValue() { return new ArrayDeque<>(); }
		};

	/** Immutable list of downstream sinks that receive flow start/finish notifications. */
	private final List<TelemetryReceiver> receivers;

	/**
	 * Entry point called by the aspect for every matched method.
	 *
	 * <p>Algorithm outline:</p>
	 * <ol>
	 *   <li>Determine if a new flow should be opened (explicit {@code @Flow} or no active flow).</li>
	 *   <li>If opening a flow, allocate identifiers and create a {@link TelemetryHolder}; push it.</li>
	 *   <li>Invoke the target method via {@link ProceedingJoinPoint#proceed()}.</li>
	 *   <li>Notify success or error hooks (non-fatal to the application).</li>
	 *   <li>If a flow was opened, set end time, notify receivers, and pop it.</li>
	 * </ol>
	 *
	 * @param joinPoint the join point representing the intercepted method invocation
	 * @param options   resolved options derived from annotations on the method/class
	 * @return the original method's return value
	 * @throws Throwable any exception thrown by the target method; rethrown without wrapping
	 */
	public final Object proceed(ProceedingJoinPoint joinPoint, FlowOptions options) throws Throwable {
		final boolean hasActive = hasActiveFlow();
		final boolean isFlowAnnotation = options != null && options.autoFlowLevel() == StandardLevel.OFF;
		final boolean startsNewFlow = isFlowAnnotation || (!hasActive); // promote lone @Step to a flow

		TelemetryHolder opened = null;
		final TelemetryHolder parent = currentHolder();

		if (startsNewFlow) {
			// timestamps
			Instant now = Instant.now();
			long epochNanos = now.getEpochSecond() * 1_000_000_000L + now.getNano();

			// IDs: trace, span, correlation (correlation == trace for root)
			String traceId;
			String parentSpanId = null;
			String correlationId;

			if (parent == null) {
				var tid = TelemetryIdGenerator.generate();
				traceId = TelemetryIdGenerator.hex128(tid);
				correlationId = traceId; // root: corr = trace
			} else {
				traceId = parent.traceId();              // inherit trace
				parentSpanId = parent.spanId();          // parent is enclosing flow
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

			push(opened);
			try { onFlowStarted(opened, parent, joinPoint, options); } catch (Exception ignore) {}
		}

		safe(() -> onInvocationStarted(currentHolder(), joinPoint, options));

		try {
			Object result = joinPoint.proceed();
			safe(() -> onSuccess(currentHolder(), result, joinPoint, options));
			return result;
		} catch (Throwable t) {
			safe(() -> onError(currentHolder(), t, joinPoint, options));
			throw t; // rethrow original
		} finally {
			try {
				safe(() -> onInvocationFinishing(currentHolder(), joinPoint, options));
			} finally {
				if (opened != null) {
					try { onFlowFinishing(opened, joinPoint, options); } catch (Exception ignore) {}
					pop(opened);
				}
			}
		}
	}

	/* --------------------- building blocks --------------------- */

	/**
	 * Constructs a new {@link TelemetryHolder} representing the flow that is being opened.
	 * Subclasses can override to enrich resource/attributes/events/links/status.
	 *
	 * @param pjp            current join point
	 * @param opts           options resolved from annotations
	 * @param parent         parent flow holder, or {@code null} if this is a root flow
	 * @param traceId        16-byte hex (lowercase) trace id
	 * @param spanId         8-byte hex (lowercase) span id for the new flow
	 * @param parentSpanId   parent span id, or {@code null} for root
	 * @param correlationId  identifier used to correlate flows; equals trace id for root
	 * @param ts             start time as {@link Instant}
	 * @param tsNanos        start time in Unix epoch nanoseconds
	 * @return a non-null {@link TelemetryHolder} snapshot of the opened flow
	 */
	protected TelemetryHolder createFlowHolder(ProceedingJoinPoint pjp, FlowOptions opts, TelemetryHolder parent,
											   String traceId, String spanId, String parentSpanId, String correlationId,
											   Instant ts, long tsNanos) {
		return new TelemetryHolder(
			/* name      */ opts.name(),
			/* timestamp */ ts,
			/* timeUnix  */ tsNanos,
			/* endTime   */ null,
			/* traceId   */ traceId,
			/* spanId    */ spanId,
			/* parentId  */ parentSpanId,
			/* kind      */ opts.spanKind(),               // from @Kind via FlowOptions
			/* resource  */ buildResource(),
			/* attrs     */ buildAttributes(pjp, opts),
			/* events    */ buildEvents(),
			/* links     */ buildLinks(),
			/* status    */ buildStatus(),
			/* serviceId */ resolveServiceId(),
			/* corrId    */ correlationId,
			/* ext       */ Map.of(),
			/* synthetic */ Boolean.FALSE
		);
	}

	/**
	 * Builds a minimal OTEL-like resource wrapper for the flow.
	 * <p>Default includes both {@code service.name} and {@code service.id}, using the value from
	 * {@link #resolveServiceId()} to satisfy Obsinity's requirement that {@code serviceId} is set
	 * at the top level or in {@code resource.service.id}.</p>
	 *
	 * @return resource wrapper to include in the flow snapshot
	 */
	protected TelemetryHolder.OResource buildResource() {
		String sid = resolveServiceId();
		return new TelemetryHolder.OResource(
			new TelemetryHolder.OAttributes(Map.of(
				"service.name", sid,
				"service.id",  sid
			))
		);
	}

	/**
	 * Builds attributes to attach to the flow/span. Default is empty.
	 *
	 * @param pjp  current join point
	 * @param opts resolved options
	 * @return attributes wrapper (possibly empty)
	 */
	protected TelemetryHolder.OAttributes buildAttributes(ProceedingJoinPoint pjp, FlowOptions opts) {
		return new TelemetryHolder.OAttributes(Map.of());
	}

	/**
	 * Builds initial event list for the flow. Default is empty.
	 *
	 * @return event list (possibly empty)
	 */
	protected List<TelemetryHolder.OEvent> buildEvents() { return List.of(); }

	/**
	 * Builds initial link list for the flow. Default is empty.
	 *
	 * @return link list (possibly empty)
	 */
	protected List<TelemetryHolder.OLink> buildLinks() { return List.of(); }

	/**
	 * Builds the status for the flow. Default is {@code null} (unset).
	 *
	 * @return status or {@code null} to let exporters infer
	 */
	protected TelemetryHolder.OStatus buildStatus() { return null; }

	/**
	 * Resolves the service identifier to use at the top level of the holder and inside the resource.
	 * <p>By default reads the {@code obsinity.serviceId} system property, falling back to
	 * {@code "obsinity-demo"}.</p>
	 *
	 * @return non-blank service id
	 */
	protected String resolveServiceId() {
		return System.getProperty("obsinity.serviceId", "obsinity-demo");
	}

	/* --------------------- hooks (notify receivers here) --------------------- */

	/**
	 * Hook invoked immediately after a new flow is opened and pushed on the stack.
	 * Default behavior notifies all receivers via {@link #notifyFlowStarted(TelemetryHolder)}.
	 *
	 * @param opened    the flow that was just opened
	 * @param parent    parent flow or {@code null} if root
	 * @param joinPoint current join point
	 * @param options   flow options
	 */
	protected void onFlowStarted(TelemetryHolder opened, TelemetryHolder parent,
								 ProceedingJoinPoint joinPoint, FlowOptions options) {
		notifyFlowStarted(opened);
	}

	/**
	 * Hook invoked just before a flow is popped from the stack.
	 * Default behavior stamps the {@code endTimestamp} and notifies all receivers via
	 * {@link #notifyFlowFinished(TelemetryHolder)}.
	 *
	 * @param opened    the flow that is about to be finished
	 * @param joinPoint current join point
	 * @param options   flow options
	 */
	protected void onFlowFinishing(TelemetryHolder opened,
								   ProceedingJoinPoint joinPoint, FlowOptions options) {
		TelemetryHolder finished = withEndTime(opened, Instant.now());
		notifyFlowFinished(finished);
	}

	/**
	 * Hook before the intercepted method executes. No-op by default.
	 *
	 * @param current   current flow (top of stack) or {@code null} if none
	 * @param joinPoint current join point
	 * @param options   flow options
	 */
	protected void onInvocationStarted(TelemetryHolder current,
									   ProceedingJoinPoint joinPoint, FlowOptions options) { }

	/**
	 * Hook after the intercepted method completes (success or failure). No-op by default.
	 *
	 * @param current   current flow (top of stack) or {@code null} if none
	 * @param joinPoint current join point
	 * @param options   flow options
	 */
	protected void onInvocationFinishing(TelemetryHolder current,
										 ProceedingJoinPoint joinPoint, FlowOptions options) { }

	/**
	 * Hook after the intercepted method returns normally. No-op by default.
	 *
	 * @param current   current flow (top of stack) or {@code null} if none
	 * @param result    original method's return value
	 * @param joinPoint current join point
	 * @param options   flow options
	 */
	protected void onSuccess(TelemetryHolder current, Object result,
							 ProceedingJoinPoint joinPoint, FlowOptions options) { }

	/**
	 * Hook after the intercepted method throws. No-op by default.
	 *
	 * @param current   current flow (top of stack) or {@code null} if none
	 * @param error     exception thrown by the target method
	 * @param joinPoint current join point
	 * @param options   flow options
	 */
	protected void onError(TelemetryHolder current, Throwable error,
						   ProceedingJoinPoint joinPoint, FlowOptions options) { }

	/* --------------------- receiver helpers --------------------- */

	/**
	 * Notifies all receivers that a flow has started. Receiver failures are swallowed.
	 *
	 * @param holder opened flow snapshot to deliver
	 */
	private void notifyFlowStarted(TelemetryHolder holder) {
		if (receivers == null || receivers.isEmpty() || holder == null) return;
		for (TelemetryReceiver r : receivers) {
			try { r.flowStarted(holder); } catch (Exception ignore) {}
		}
	}

	/**
	 * Notifies all receivers that a flow has finished. Receiver failures are swallowed.
	 *
	 * @param holder finished flow snapshot (with end timestamp)
	 */
	private void notifyFlowFinished(TelemetryHolder holder) {
		if (receivers == null || receivers.isEmpty() || holder == null) return;
		for (TelemetryReceiver r : receivers) {
			try { r.flowFinished(holder); } catch (Exception ignore) {}
		}
	}

	/**
	 * Returns a copy of the given holder with {@code endTimestamp} set.
	 *
	 * @param h   original holder
	 * @param end end time to stamp
	 * @return new holder with end time, or {@code null} if {@code h} is null
	 */
	private TelemetryHolder withEndTime(TelemetryHolder h, Instant end) {
		if (h == null) return null;
		return new TelemetryHolder(
			h.name(), h.timestamp(), h.timeUnixNano(),
			end, // endTimestamp set
			h.traceId(), h.spanId(), h.parentSpanId(), h.kind(),
			h.resource(), h.attributes(), h.events(), h.links(), h.status(),
			h.serviceId(), h.correlationId(), h.extensions(), h.synthetic()
		);
	}

	/* --------------------- Thread-local helpers --------------------- */

	/**
	 * Returns the current flow (top of the stack) for this thread, or {@code null} if none.
	 *
	 * @return current {@link TelemetryHolder} or {@code null}
	 */
	public static TelemetryHolder currentHolder() {
		Deque<TelemetryHolder> d = CTX.get();
		return d.isEmpty() ? null : d.peekLast();
	}

	/**
	 * @return {@code true} if at least one flow is active on this thread
	 */
	public static boolean hasActiveFlow() { return !CTX.get().isEmpty(); }

	/**
	 * Pushes the given holder on this thread's flow stack.
	 *
	 * @param h holder to push
	 */
	private static void push(TelemetryHolder h) { CTX.get().addLast(h); }

	/**
	 * Pops the given holder if it is at the top of the stack; otherwise clears the stack.
	 * <p>This defensive behavior avoids leaving a corrupted stack if unexpected reentrancy occurs.</p>
	 *
	 * @param expectedTop the holder expected to be on top
	 */
	private static void pop(TelemetryHolder expectedTop) {
		Deque<TelemetryHolder> d = CTX.get();
		if (!d.isEmpty() && d.peekLast() == expectedTop) d.removeLast(); else d.clear();
	}

	/* --------------------- utility --------------------- */

	/**
	 * Executes the given runnable and ignores any checked/unchecked exception it throws.
	 * Intended only for non-critical hooks so telemetry never disrupts business logic.
	 *
	 * @param r action to run
	 */
	private interface UnsafeRunnable { void run() throws Exception; }
	private static void safe(UnsafeRunnable r) { try { r.run(); } catch (Exception ignore) {} }
}
