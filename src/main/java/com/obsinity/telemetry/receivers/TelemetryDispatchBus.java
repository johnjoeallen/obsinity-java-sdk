package com.obsinity.telemetry.receivers;

import com.obsinity.telemetry.annotations.DispatchMode;
import com.obsinity.telemetry.dispatch.Handler;
import com.obsinity.telemetry.dispatch.HandlerGroup;
import com.obsinity.telemetry.dispatch.HandlerGroup.ModeBuckets;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class TelemetryDispatchBus {

	private static final Logger log = LoggerFactory.getLogger(TelemetryDispatchBus.class);

	private final List<HandlerGroup> groups;

	public TelemetryDispatchBus(List<HandlerGroup> groups) {
		this.groups = Objects.requireNonNull(groups);
	}

	/** Call when any flow (root or nested) is started. */
	public void flowStarted(TelemetryHolder holder) {
		dispatch(holder, Lifecycle.FLOW_STARTED);
	}

	/** Call when any flow (root or nested) is finished. */
	public void flowFinished(TelemetryHolder holder) {
		dispatch(holder, Lifecycle.FLOW_FINISHED);
	}

	/** Call when a root flow finishes with all completed flows (root + nested). */
	public void rootFlowFinished(List<TelemetryHolder> completed) {
		for (TelemetryHolder h : completed) {
			dispatch(h, Lifecycle.FLOW_FINISHED);
		}
	}

	// === Core dispatch ===

	private void dispatch(TelemetryHolder holder, Lifecycle phase) {
		final String eventName = holder.name();
		final Throwable error = holder.throwable();
		final boolean failed = (error != null);

		boolean anyMatchedByOnEvent = false;   // flips only on @OnEvent
		boolean anyComponentUnmatched = false; // flips when a component-level unmatched ran

		// 1) Per-component pass: taps -> matched -> component-unmatched
		for (HandlerGroup g : groups) {
			// 1.1) Run additive taps (never affect anyMatched flags)
			runTaps(g, phase, holder, failed);

			// 1.2) Dot-chop match ON EVENT
			ModeBuckets chosen = g.findNearestEligibleTier(phase, eventName, holder, failed, error);
			if (chosen != null) {
				if (failed) {
					if (hasAnyEligibleFailure(chosen.combined, holder, phase, error)) {
						runFailure(chosen.combined, holder, phase, error);
						anyMatchedByOnEvent = true;
					} else if (hasAnyEligibleFailure(chosen.failure, holder, phase, error)) {
						runFailure(chosen.failure, holder, phase, error);
						anyMatchedByOnEvent = true;
					} else {
						// No eligible handler in this tier -> component-scoped unmatched
						anyComponentUnmatched |= invokeComponentUnmatched(g, phase, holder, true, error);
					}
				} else {
					if (hasAnyEligibleSuccess(chosen.combined, holder, phase)) {
						runSuccess(chosen.combined, holder, phase);
						anyMatchedByOnEvent = true;
					} else if (hasAnyEligibleSuccess(chosen.success, holder, phase)) {
						runSuccess(chosen.success, holder, phase);
						anyMatchedByOnEvent = true;
					} else {
						anyComponentUnmatched |= invokeComponentUnmatched(g, phase, holder, false, null);
					}
				}
			} else {
				// No dot-chop tier in this component -> try its component-scoped unmatched
				anyComponentUnmatched |= invokeComponentUnmatched(g, phase, holder, failed, error);
			}
		}

		// 2) GLOBAL unmatched: only if no @OnEvent matched anywhere AND no component unmatched fired
		if (!anyMatchedByOnEvent && !anyComponentUnmatched) {
			boolean invoked = invokeGlobalUnmatchedAcrossAllComponents(phase, holder, failed, error);
			if (!invoked) {
				log.error("Unhandled {} event name='{}' phase={} traceId={} spanId={} ex={}",
					failed ? "failure" : "success",
					holder.name(), phase,
					safe(holder.traceId()), safe(holder.spanId()),
					String.valueOf(error));
			}
		}
	}

	// === Helpers ===

	/** Run @OnEveryEvent taps for this component. */
	private void runTaps(HandlerGroup g, Lifecycle phase, TelemetryHolder holder, boolean failed) {
		for (Handler h : g.taps.combined(phase)) {
			if (h.accepts(phase, holder, failed, holder.throwable())) {
				safeInvoke(h, holder, phase);
			}
		}
		if (failed) {
			for (Handler h : g.taps.failure(phase)) {
				if (h.accepts(phase, holder, true, holder.throwable())) {
					safeInvoke(h, holder, phase);
				}
			}
		} else {
			for (Handler h : g.taps.success(phase)) {
				if (h.accepts(phase, holder, false, null)) {
					safeInvoke(h, holder, phase);
				}
			}
		}
	}

	private boolean hasAnyEligibleSuccess(List<Handler> hs, TelemetryHolder holder, Lifecycle phase) {
		for (Handler h : hs) {
			if (h.getMode() == DispatchMode.FAILURE) continue;
			if (h.accepts(phase, holder, false, null)) return true;
		}
		return false;
	}

	private boolean hasAnyEligibleFailure(List<Handler> hs, TelemetryHolder holder, Lifecycle phase, Throwable error) {
		for (Handler h : hs) {
			if (h.getMode() == DispatchMode.SUCCESS) continue;
			if (h.accepts(phase, holder, true, error)) return true;
		}
		return false;
	}

	private void runSuccess(List<Handler> hs, TelemetryHolder holder, Lifecycle phase) {
		for (Handler h : hs) {
			if (h.getMode() == DispatchMode.FAILURE) continue;
			if (h.accepts(phase, holder, false, null)) {
				safeInvoke(h, holder, phase);
			}
		}
	}

	private void runFailure(List<Handler> hs, TelemetryHolder holder, Lifecycle phase, Throwable error) {
		for (Handler h : hs) {
			if (h.getMode() == DispatchMode.SUCCESS) continue;
			if (h.accepts(phase, holder, true, error)) {
				safeInvoke(h, holder, phase);
			}
		}
	}

	/** Component-scoped unmatched: @OnUnMatchedEvent(scope=COMPONENT) for this group only. Returns true if any invoked. */
	private boolean invokeComponentUnmatched(HandlerGroup g, Lifecycle phase, TelemetryHolder holder,
											 boolean failed, Throwable error) {
		boolean invokedAny = false;

		// Combined unmatched first
		for (Handler h : g.unmatched.combined(phase)) {
			if (h.accepts(phase, holder, failed, error)) {
				safeInvoke(h, holder, phase);
				invokedAny = true;
			}
		}
		// Mode-specific unmatched
		List<Handler> bucket = failed ? g.unmatched.failure(phase) : g.unmatched.success(phase);
		for (Handler h : bucket) {
			if (h.accepts(phase, holder, failed, error)) {
				safeInvoke(h, holder, phase);
				invokedAny = true;
			}
		}
		return invokedAny;
	}

	/** Global unmatched across all components: returns true if any invoked. */
	private boolean invokeGlobalUnmatchedAcrossAllComponents(Lifecycle phase, TelemetryHolder holder,
															 boolean failed, Throwable error) {
		boolean invoked = false;
		for (HandlerGroup g : groups) {
			for (Handler h : g.globalUnmatched.combined(phase)) {
				if (h.accepts(phase, holder, failed, error)) {
					safeInvoke(h, holder, phase);
					invoked = true;
				}
			}
			List<Handler> global = failed ? g.globalUnmatched.failure(phase) : g.globalUnmatched.success(phase);
			for (Handler h : global) {
				if (h.accepts(phase, holder, failed, error)) {
					safeInvoke(h, holder, phase);
					invoked = true;
				}
			}
		}
		return invoked;
	}

	private void safeInvoke(Handler h, TelemetryHolder holder, Lifecycle phase) {
		try {
			h.invoke(holder, phase);
		} catch (Throwable t) {
			log.error("Handler error in {} for event name='{}' phase={} traceId={} spanId={}: {}",
				h.debugName(), holder.name(), phase,
				safe(holder.traceId()), safe(holder.spanId()), t.toString(), t);
		}
	}

	private static String safe(String s) { return s == null ? "-" : s; }
}
