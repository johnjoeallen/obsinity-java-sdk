package com.obsinity.telemetry.receivers;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.obsinity.telemetry.dispatch.Handler;
import com.obsinity.telemetry.dispatch.HandlerGroup;
import com.obsinity.telemetry.dispatch.HandlerGroup.ModeBuckets;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;

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

	/**
	 * Call when a root flow finishes with all completed flows (root + nested). We dispatch ONLY the root holder with
	 * phase ROOT_FLOW_FINISHED. Any handler that declares List&lt;TelemetryHolder&gt; will receive the batch via
	 * binding from TelemetryProcessorSupport (in the scanner’s binder).
	 */
	public void rootFlowFinished(List<TelemetryHolder> completed) {
		log.debug(
				"BUS: rootFlowFinished batch size={} names={}",
				(completed == null ? 0 : completed.size()),
				(completed == null)
						? "[]"
						: completed.stream().map(TelemetryHolder::name).toList());

		if (completed == null || completed.isEmpty()) return;

		// Prefer the holder with no parentSpanId; fall back to first element.
		TelemetryHolder root = null;
		for (TelemetryHolder h : completed) {
			if (h != null && h.parentSpanId() == null) {
				root = h;
				break;
			}
		}
		if (root == null) root = completed.get(0);

		if (root != null) {
			dispatch(root, Lifecycle.ROOT_FLOW_FINISHED);
		}
	}

	// === Core dispatch ===

	private void dispatch(TelemetryHolder holder, Lifecycle phase) {
		if (holder == null) {
			log.debug("BUS: dispatch called with null holder (phase={})", phase);
			return;
		}

		final String eventName = holder.name();
		final Throwable error = holder.throwable();
		final boolean failed = (error != null);

		log.debug(
				"BUS: dispatch phase={} name='{}' failed={} isStep={} traceId={} spanId={} attrKeys={} ctxKeys={} groups={}",
				phase,
				eventName,
				failed,
				bool(holder.isStep()),
				safe(holder.traceId()),
				safe(holder.spanId()),
				attrKeys(holder),
				ctxKeys(holder),
				(groups == null ? 0 : groups.size()));

		boolean anyMatched = false; // flips when any named handler matched
		boolean anyComponentUnmatched = false; // flips when a component-level unmatched ran
		boolean anyGroupEligibleForPhase = false; // lifecycle+scope+outcome presence

		// Per-component pass: group-level gates -> named handlers -> component-unmatched
		for (int i = 0; i < groups.size(); i++) {
			HandlerGroup g = groups.get(i);
			String componentName = safeComponentName(g);
			log.debug("BUS: component[{}]='{}' begin (event='{}', phase={})", i, componentName, eventName, phase);

			// 1) Lifecycle gating
			try {
				if (g.getScope() != null && !g.supportsLifecycle(phase)) {
					log.debug("BUS: component[{}]='{}' skipped by lifecycle (phase={})", i, componentName, phase);
					continue;
				}
			} catch (NoSuchMethodError | Exception ignored) {
				// if supportsLifecycle() doesn't exist on this build, skip this optimization
			}

			// 2) Static scope (prefix/name/etc.)
			if (!g.isInScope(phase, eventName, holder)) {
				log.debug("BUS: component[{}]='{}' out-of-scope -> skip", i, componentName);
				continue;
			}

			// 3) Outcome availability (fast check to avoid useless handler probes)
			try {
				if (!g.hasAnyHandlersFor(phase, failed)) {
					log.debug(
							"BUS: component[{}]='{}' has no handlers for outcome={}, phase={}",
							i,
							componentName,
							failed,
							phase);
					continue;
				}
			} catch (NoSuchMethodError | Exception ignored) {
				// if hasAnyHandlersFor() doesn't exist on this build, proceed as before
			}

			anyGroupEligibleForPhase = true;

			// 4) Resolve nearest tier (dot-chop) AFTER group-level gates
			ModeBuckets chosen = g.findNearestEligibleTier(phase, eventName, holder, failed, error);
			log.debug("BUS: component[{}]='{}' tier found?={}", i, componentName, chosen != null);
			if (chosen != null) {
				log.debug(
						"BUS: component[{}]='{}' tier bucket sizes: completed={} success={} failure={}",
						i,
						componentName,
						(chosen.completed == null ? 0 : chosen.completed.size()),
						(chosen.success == null ? 0 : chosen.success.size()),
						(chosen.failure == null ? 0 : chosen.failure.size()));
			}

			if (chosen != null) {
				boolean matchedHere = false;

				// completed (both outcomes) first
				if (hasAnyEligible(
						chosen.completed, holder, phase, failed, error, i, componentName, "tier.completed")) {
					runAll(chosen.completed, holder, phase, failed, error, i, componentName, "tier.completed");
					matchedHere = true;
				}

				if (failed) {
					// Most-specific failure selection
					if (chosen.failure != null && !chosen.failure.isEmpty()) {
						List<Handler> selected = selectMostSpecificFailureHandlers(
								chosen.failure, holder, phase, error, i, componentName);
						if (!selected.isEmpty()) {
							runAll(selected, holder, phase, true, error, i, componentName, "tier.failure[selected]");
							matchedHere = true;
						}
					}
					if (!matchedHere) {
						boolean invoked = invokeComponentUnmatched(g, phase, holder, true, error, i, componentName);
						anyComponentUnmatched |= invoked;
						log.debug(
								"BUS: component[{}]='{}' no eligible failure handlers -> component-unmatched invoked={}",
								i,
								componentName,
								invoked);
					}
				} else {
					if (hasAnyEligible(chosen.success, holder, phase, false, null, i, componentName, "tier.success")) {
						runAll(chosen.success, holder, phase, false, null, i, componentName, "tier.success");
						matchedHere = true;
					}
					if (!matchedHere) {
						boolean invoked = invokeComponentUnmatched(g, phase, holder, false, null, i, componentName);
						anyComponentUnmatched |= invoked;
						log.debug(
								"BUS: component[{}]='{}' no eligible success handlers -> component-unmatched invoked={}",
								i,
								componentName,
								invoked);
					}
				}

				if (matchedHere) anyMatched = true;
			} else {
				// No tier -> try component-scoped unmatched for the current phase
				boolean invoked = invokeComponentUnmatched(g, phase, holder, failed, error, i, componentName);
				anyComponentUnmatched |= invoked;
				log.debug(
						"BUS: component[{}]='{}' no tier -> component-unmatched invoked={}", i, componentName, invoked);
			}

			log.debug("BUS: component[{}]='{}' end", i, componentName);
		}

		// GLOBAL unmatched: only if nothing matched anywhere and no component-unmatched fired
		if (!anyMatched && !anyComponentUnmatched) {
			if (anyGroupEligibleForPhase) {

				// --- NEW: rich diagnostics before global-unmatched path ---
				logGlobalUnmatchedDiagnostics(phase, holder, failed, error);

				boolean invoked = invokeGlobalUnmatchedAcrossAllComponents(phase, holder, failed, error, eventName);
				log.debug(
						"BUS: global-unmatched invoked={} (no named handlers matched and no component-unmatched)",
						invoked);
				if (!invoked) {
					log.error(
							"Unhandled {} event name='{}' phase={} traceId={} spanId={} ex={}",
							failed ? "failure" : "success",
							holder.name(),
							phase,
							safe(holder.traceId()),
							safe(holder.spanId()),
							String.valueOf(error));
				}
			} else {
				// Nothing in the system declared interest in this phase/outcome/name -> suppress noise
				log.debug("BUS: suppress unhandled (no lifecycle/scope/outcome-eligible groups for phase={})", phase);
			}
		}
	}

	// ---- most-specific failure selection ----

	private List<Handler> selectMostSpecificFailureHandlers(
			List<Handler> failureHandlers,
			TelemetryHolder holder,
			Lifecycle phase,
			Throwable error,
			int componentIdx,
			String componentName) {
		if (failureHandlers == null || failureHandlers.isEmpty() || error == null) return List.of();

		// 1) Filter to handlers that accept
		List<Handler> elig = new ArrayList<>();
		for (Handler h : failureHandlers) {
			boolean ok = h.accepts(phase, holder, true, error);
			log.debug(
					"BUS: failure-candidate [{}]='{}' accepts?={} handler={} errClass={}",
					componentIdx,
					componentName,
					ok,
					safeHandlerName(h),
					error.getClass().getName());
			if (ok) elig.add(h);
		}
		if (elig.isEmpty()) return List.of();

		// 2) Partition specific vs generic
		List<Handler> specifics = new ArrayList<>();
		List<Handler> generics = new ArrayList<>();
		for (Handler h : elig) {
			Class<? extends Throwable> tt = firstThrowableType(h);
			if (isGenericThrowable(tt)) generics.add(h);
			else specifics.add(h);
		}

		// 3) If there are specific handlers, choose those with the minimum distance
		if (!specifics.isEmpty()) {
			int best = Integer.MAX_VALUE;
			List<Handler> winners = new ArrayList<>();
			for (Handler h : specifics) {
				Class<? extends Throwable> tt = firstThrowableType(h);
				int d = distanceTo(error.getClass(), tt);
				if (d >= 0) {
					if (d < best) {
						best = d;
						winners.clear();
						winners.add(h);
					} else if (d == best) {
						winners.add(h);
					}
				}
			}
			log.debug(
					"BUS: specific failure selection winners={} bestDistance={}",
					winners.stream().map(TelemetryDispatchBus::safeHandlerName).toList(),
					best);
			return winners;
		}

		// 4) Otherwise fall back to all generic matches
		log.debug("BUS: no specific failure handlers matched; falling back to GENERIC");
		return generics;
	}

	private static Class<? extends Throwable> firstThrowableType(Handler h) {
		try {
			List<Class<? extends Throwable>> tts = h.throwableTypes();
			return (tts == null || tts.isEmpty()) ? null : tts.get(0);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static boolean isGenericThrowable(Class<? extends Throwable> t) {
		return t == null || t == Throwable.class || t == Exception.class;
	}

	/** return number of super steps from fromClass to target (inclusive), or -1 if not assignable */
	private static int distanceTo(Class<?> fromClass, Class<?> target) {
		if (target == null) return Integer.MAX_VALUE;
		if (!target.isAssignableFrom(fromClass)) return -1;
		int d = 0;
		Class<?> cur = fromClass;
		while (cur != null && !cur.equals(target)) {
			cur = cur.getSuperclass();
			d++;
		}
		return d;
	}

	// ---- eligibility (without invoking) ----

	private boolean hasAnyEligible(
			List<Handler> hs,
			TelemetryHolder holder,
			Lifecycle phase,
			boolean failed,
			Throwable error,
			int componentIdx,
			String componentName,
			String bucketName) {
		if (hs == null || hs.isEmpty()) return false;
		for (Handler h : hs) {
			boolean ok = h.accepts(phase, holder, failed, error);
			log.debug(
					"BUS: match-check [{}]='{}' [{}] accepts?={} handler={} phase={} name='{}' failed={}",
					componentIdx,
					componentName,
					bucketName,
					ok,
					safeHandlerName(h),
					phase,
					holder.name(),
					failed);
			if (ok) return true;
		}
		return false;
	}

	// ---- invocation paths ----

	private void runAll(
			List<Handler> hs,
			TelemetryHolder holder,
			Lifecycle phase,
			boolean failed,
			Throwable error,
			int componentIdx,
			String componentName,
			String bucketName) {
		if (hs == null) return;
		for (Handler h : hs) {
			boolean ok = h.accepts(phase, holder, failed, error);
			log.debug(
					"BUS: invoke-check [{}]='{}' [{}] accepts?={} handler={} phase={} name='{}' failed={}",
					componentIdx,
					componentName,
					bucketName,
					ok,
					safeHandlerName(h),
					phase,
					holder.name(),
					failed);
			if (ok) safeInvoke(h, holder, phase);
		}
	}

	/**
	 * Component-scoped unmatched built from @OnFlowNotMatched registrations for this group only. Returns true if any
	 * invoked.
	 */
	private boolean invokeComponentUnmatched(
			HandlerGroup g,
			Lifecycle phase,
			TelemetryHolder holder,
			boolean failed,
			Throwable error,
			int componentIdx,
			String componentName) {
		boolean invokedAny = false;

		// Completed (both outcomes) first
		for (Handler h : g.unmatched.combined(phase)) { // alias to completed
			boolean ok = h.accepts(phase, holder, failed, error);
			log.debug(
					"BUS: component-unmatched [{}]='{}' [completed] accepts?={} handler={} phase={} name='{}' failed={}",
					componentIdx,
					componentName,
					ok,
					safeHandlerName(h),
					phase,
					holder.name(),
					failed);
			if (ok) {
				log.debug("BUS: component-unmatched invoking handler={}", safeHandlerName(h));
				safeInvoke(h, holder, phase);
				invokedAny = true;
			}
		}
		// Outcome-specific unmatched
		List<Handler> bucket = failed ? g.unmatched.failure(phase) : g.unmatched.success(phase);
		String modeName = failed ? "failure" : "success";
		for (Handler h : bucket) {
			boolean ok = h.accepts(phase, holder, failed, error);
			log.debug(
					"BUS: component-unmatched [{}]='{}' [{}] accepts?={} handler={} phase={} name='{}' failed={}",
					componentIdx,
					componentName,
					modeName,
					ok,
					safeHandlerName(h),
					phase,
					holder.name(),
					failed);
			if (ok) {
				log.debug("BUS: component-unmatched invoking handler={}", safeHandlerName(h));
				safeInvoke(h, holder, phase);
				invokedAny = true;
			}
		}
		return invokedAny;
	}

	/** Global unmatched across all components: returns true if any invoked (no phase fallback). */
	private boolean invokeGlobalUnmatchedAcrossAllComponents(
			Lifecycle phase, TelemetryHolder holder, boolean failed, Throwable error, String eventName) {

		boolean invoked = false;
		for (int i = 0; i < groups.size(); i++) {
			HandlerGroup g = groups.get(i);

			// Respect lifecycle and scope even for global unmatched to avoid unrelated receivers
			try {
				if ((g.getScope() != null && !g.supportsLifecycle(phase)) || !g.isInScope(phase, eventName, holder)) {
					continue;
				}
			} catch (NoSuchMethodError | Exception ignored) {
				if (!g.isInScope(phase, eventName, holder)) continue;
			}

			String componentName = safeComponentName(g);

			for (Handler h : g.globalUnmatched.combined(phase)) { // alias to completed
				boolean ok = h.accepts(phase, holder, failed, error);
				log.debug(
						"BUS: GLOBAL-unmatched [{}]='{}' [completed] accepts?={} handler={} phase={} name='{}' failed={}",
						i,
						componentName,
						ok,
						safeHandlerName(h),
						phase,
						holder.name(),
						failed);
				if (ok) {
					log.debug("BUS: GLOBAL-unmatched invoking handler={}", safeHandlerName(h));
					safeInvoke(h, holder, phase);
					invoked = true;
				}
			}
			List<Handler> global = failed ? g.globalUnmatched.failure(phase) : g.globalUnmatched.success(phase);
			String modeName = failed ? "failure" : "success";
			for (Handler h : global) {
				boolean ok = h.accepts(phase, holder, failed, error);
				log.debug(
						"BUS: GLOBAL-unmatched [{}]='{}' [{}] accepts?={} handler={} phase={} name='{}' failed={}",
						i,
						componentName,
						modeName,
						ok,
						safeHandlerName(h),
						phase,
						holder.name(),
						failed);
				if (ok) {
					log.debug("BUS: GLOBAL-unmatched invoking handler={}", safeHandlerName(h));
					safeInvoke(h, holder, phase);
					invoked = true;
				}
			}
		}
		return invoked;
	}

	/* =========================
	NEW: rich diagnostics before global-unmatched
	========================= */
	private void logGlobalUnmatchedDiagnostics(
			Lifecycle phase, TelemetryHolder holder, boolean failed, Throwable error) {
		try {
			log.warn(
					"BUS: GLOBAL-UNMATCHED DIAGNOSTICS → event='{}' phase={} failed={} traceId={} spanId={} exClass={} exMsg={}",
					holder.name(),
					phase,
					failed,
					safe(holder.traceId()),
					safe(holder.spanId()),
					(error == null ? "-" : error.getClass().getName()),
					(error == null ? "-" : String.valueOf(error.getMessage())));

			for (int i = 0; i < groups.size(); i++) {
				HandlerGroup g = groups.get(i);
				String componentName = safeComponentName(g);

				// lifecycle gate
				boolean lifecycleOk;
				try {
					lifecycleOk = (g.getScope() == null) || g.supportsLifecycle(phase);
				} catch (Throwable t) {
					lifecycleOk = true; // be permissive if older API
				}
				if (!lifecycleOk) {
					log.warn("  ↳ [{}]='{}' REASON=lifecycle_blocked phase={}", i, componentName, phase);
					continue;
				}

				// scope gate
				boolean scopeOk = false;
				try {
					scopeOk = g.isInScope(phase, holder.name(), holder);
				} catch (Throwable t) {
					scopeOk = false;
				}
				if (!scopeOk) {
					log.warn("  ↳ [{}]='{}' REASON=out_of_scope name='{}'", i, componentName, holder.name());
					continue;
				}

				// outcome availability
				boolean hasOutcome = true;
				try {
					hasOutcome = g.hasAnyHandlersFor(phase, failed);
				} catch (Throwable ignored) {
				}
				if (!hasOutcome) {
					log.warn(
							"  ↳ [{}]='{}' REASON=no_handlers_for_outcome outcome={} phase={}",
							i,
							componentName,
							(failed ? "FAILURE" : "SUCCESS"),
							phase);
					continue;
				}

				// nearest tier
				ModeBuckets chosen = null;
				try {
					chosen = g.findNearestEligibleTier(phase, holder.name(), holder, failed, error);
				} catch (Throwable t) {
					// ignore
				}
				if (chosen == null) {
					log.warn("  ↳ [{}]='{}' REASON=no_tier_by_dot_chop name='{}'", i, componentName, holder.name());
					continue;
				}

				int completedCount = (chosen.completed == null ? 0 : chosen.completed.size());
				int successCount = (chosen.success == null ? 0 : chosen.success.size());
				int failureCount = (chosen.failure == null ? 0 : chosen.failure.size());

				int completedElig = countEligible(chosen.completed, holder, phase, failed, error);
				int successElig = countEligible(chosen.success, holder, phase, false, null);
				int failureElig = countEligible(chosen.failure, holder, phase, true, error);

				log.warn(
						"  ↳ [{}]='{}' tier: completed(size={}, eligible={}) success(size={}, eligible={}) failure(size={}, eligible={})",
						i,
						componentName,
						completedCount,
						completedElig,
						successCount,
						successElig,
						failureCount,
						failureElig);

				if (completedElig + (failed ? failureElig : successElig) == 0) {
					log.warn(
							"    → REASON=handlers_present_but_none_accepted (check required attrs/context, kind, throwable filters)");
				} else {
					// If we ever land here with eligibles > 0, it would mean anyMatched bookkeeping is wrong
					log.warn(
							"    → NOTE: eligible handlers exist; if global unmatched still fires, investigate anyMatched bookkeeping.");
				}
			}
		} catch (Throwable t) {
			log.warn("BUS: GLOBAL-UNMATCHED diagnostics failed: {}", t.toString(), t);
		}
	}

	private int countEligible(
			List<Handler> hs, TelemetryHolder holder, Lifecycle phase, boolean failed, Throwable error) {
		if (hs == null || hs.isEmpty()) return 0;
		int n = 0;
		for (Handler h : hs) {
			try {
				if (h.accepts(phase, holder, failed, error)) n++;
			} catch (Throwable ignored) {
			}
		}
		return n;
	}

	private void safeInvoke(Handler h, TelemetryHolder holder, Lifecycle phase) {
		try {
			// (Optional) preview of parameter binding could be re-added if needed
			h.invoke(holder, phase);
		} catch (Throwable t) {
			log.error(
					"Handler error in {} for event name='{}' phase={} traceId={} spanId={}: {}",
					safeHandlerName(h),
					holder.name(),
					phase,
					safe(holder.traceId()),
					safe(holder.spanId()),
					t.toString(),
					t);
		}
	}

	// === helpers ===

	private static String safeComponentName(HandlerGroup g) {
		try {
			return g.getComponentName();
		} catch (Throwable ignored) {
			return g.getClass().getSimpleName();
		}
	}

	private static String safeHandlerName(Handler h) {
		try {
			return h.debugName();
		} catch (Throwable ignored) {
			return h.getClass().getSimpleName();
		}
	}

	private static String safe(String s) {
		return s == null ? "-" : s;
	}

	private static boolean bool(Boolean b) {
		return b != null && b;
	}

	private static String attrKeys(TelemetryHolder holder) {
		try {
			Object attrs = holder.attributes();
			if (attrs == null) return "[]";
			Method asMap = attrs.getClass().getMethod("map");
			Object m = asMap.invoke(attrs);
			if (m instanceof Map<?, ?> map) {
				return map.keySet().toString();
			}
		} catch (Throwable ignored) {
		}
		return "[]";
	}

	private static String ctxKeys(TelemetryHolder holder) {
		try {
			Method getter;
			try {
				getter = holder.getClass().getMethod("getEventContext");
			} catch (NoSuchMethodException ignored) {
				getter = holder.getClass().getMethod("eventContext");
			}
			Object ctx = getter.invoke(holder);
			if (ctx == null) return "[]";
			try {
				Method asMap = ctx.getClass().getMethod("asMap");
				Object m = asMap.invoke(ctx);
				if (m instanceof Map<?, ?> map) {
					return map.keySet().toString();
				}
			} catch (NoSuchMethodException ignored) {
				if (ctx instanceof Map<?, ?> map) {
					return map.keySet().toString();
				}
			}
		} catch (Throwable ignored) {
		}
		return "[]";
	}
}
