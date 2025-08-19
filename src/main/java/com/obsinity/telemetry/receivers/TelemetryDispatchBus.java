package com.obsinity.telemetry.receivers;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.obsinity.telemetry.annotations.DispatchMode;
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

		boolean anyMatchedByOnEvent = false; // flips only on @OnEvent
		boolean anyComponentUnmatched = false; // flips when a component-level unmatched ran

		// 1) Per-component pass: taps -> matched -> component-unmatched
		for (int i = 0; i < groups.size(); i++) {
			HandlerGroup g = groups.get(i);
			String componentName = safeComponentName(g);
			log.debug("BUS: component[{}]='{}' begin (event='{}', phase={})", i, componentName, eventName, phase);

			// 1.1) Run additive taps (never affect anyMatched flags)
			int tapsCombined = g.taps.combined(phase).size();
			int tapsSuccess = g.taps.success(phase).size();
			int tapsFailure = g.taps.failure(phase).size();
			log.debug(
					"BUS: component[{}]='{}' taps: combined={} success={} failure={}",
					i,
					componentName,
					tapsCombined,
					tapsSuccess,
					tapsFailure);
			runTaps(g, phase, holder, failed, i, componentName);

			// 1.2) Dot-chop match ON EVENT (no phase fallback)
			ModeBuckets chosen = g.findNearestEligibleTier(phase, eventName, holder, failed, error);
			log.debug("BUS: component[{}]='{}' tier found?={}", i, componentName, chosen != null);
			if (chosen != null) {
				log.debug(
						"BUS: component[{}]='{}' tier bucket sizes: combined={} success={} failure={}",
						i,
						componentName,
						(chosen.combined == null ? 0 : chosen.combined.size()),
						(chosen.success == null ? 0 : chosen.success.size()),
						(chosen.failure == null ? 0 : chosen.failure.size()));
			}

			if (!g.isInScope(phase, eventName, holder)) continue;

			if (chosen != null) {
				boolean matchedHere = false;

				if (failed) {
					boolean canRunCombined = hasAnyEligibleFailure(
							chosen.combined, holder, phase, error, i, componentName, "tier.combined");
					boolean canRunFailure = hasAnyEligibleFailure(
							chosen.failure, holder, phase, error, i, componentName, "tier.failure");
					log.debug(
							"BUS: component[{}]='{}' eligible FAILURE? combined={} failureOnly={}",
							i,
							componentName,
							canRunCombined,
							canRunFailure);

					// ADDITIVE: run both if eligible
					if (canRunCombined) {
						runFailure(chosen.combined, holder, phase, error, i, componentName, "tier.combined");
						matchedHere = true;
					}
					if (canRunFailure) {
						runFailure(chosen.failure, holder, phase, error, i, componentName, "tier.failure");
						matchedHere = true;
					}

					if (!canRunCombined && !canRunFailure) {
						boolean invoked = invokeComponentUnmatched(g, phase, holder, true, error, i, componentName);
						anyComponentUnmatched |= invoked;
						log.debug(
								"BUS: component[{}]='{}' no eligible failure handlers -> component-unmatched invoked={}",
								i,
								componentName,
								invoked);
					}
				} else {
					boolean canRunCombined =
							hasAnyEligibleSuccess(chosen.combined, holder, phase, i, componentName, "tier.combined");
					boolean canRunSuccess =
							hasAnyEligibleSuccess(chosen.success, holder, phase, i, componentName, "tier.success");
					log.debug(
							"BUS: component[{}]='{}' eligible SUCCESS? combined={} successOnly={}",
							i,
							componentName,
							canRunCombined,
							canRunSuccess);

					// ADDITIVE: run both if eligible
					if (canRunCombined) {
						runSuccess(chosen.combined, holder, phase, i, componentName, "tier.combined");
						matchedHere = true;
					}
					if (canRunSuccess) {
						runSuccess(chosen.success, holder, phase, i, componentName, "tier.success");
						matchedHere = true;
					}

					if (!canRunCombined && !canRunSuccess) {
						boolean invoked = invokeComponentUnmatched(g, phase, holder, false, null, i, componentName);
						anyComponentUnmatched |= invoked;
						log.debug(
								"BUS: component[{}]='{}' no eligible success handlers -> component-unmatched invoked={}",
								i,
								componentName,
								invoked);
					}
				}

				if (matchedHere) anyMatchedByOnEvent = true;
			} else {
				// No tier -> try component-scoped unmatched for the current phase
				boolean invoked = invokeComponentUnmatched(g, phase, holder, failed, error, i, componentName);
				anyComponentUnmatched |= invoked;
				log.debug(
						"BUS: component[{}]='{}' no tier -> component-unmatched invoked={}", i, componentName, invoked);
			}

			log.debug("BUS: component[{}]='{}' end", i, componentName);
		}

		// 2) GLOBAL unmatched: only if no @OnEvent matched anywhere AND no component unmatched fired
		if (!anyMatchedByOnEvent && !anyComponentUnmatched) {
			boolean invoked = invokeGlobalUnmatchedAcrossAllComponents(phase, holder, failed, error);
			log.debug("BUS: global-unmatched invoked={} (no @OnEvent matched and no component-unmatched)", invoked);
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
		}
	}

	// === Helpers ===

	/** Run @OnEveryEvent taps for this component. */
	private void runTaps(
			HandlerGroup g,
			Lifecycle phase,
			TelemetryHolder holder,
			boolean failed,
			int componentIdx,
			String componentName) {
		for (Handler h : g.taps.combined(phase)) {
			boolean ok = h.accepts(phase, holder, failed, holder.throwable());
			log.debug(
					"BUS: TAP combined accepts?={} component[{}]='{}' handler={} phase={} name='{}'",
					ok,
					componentIdx,
					componentName,
					safeHandlerName(h),
					phase,
					holder.name());
			if (ok) safeInvoke(h, holder, phase);
		}
		if (failed) {
			for (Handler h : g.taps.failure(phase)) {
				boolean ok = h.accepts(phase, holder, true, holder.throwable());
				log.debug(
						"BUS: TAP failure accepts?={} component[{}]='{}' handler={} phase={} name='{}'",
						ok,
						componentIdx,
						componentName,
						safeHandlerName(h),
						phase,
						holder.name());
				if (ok) safeInvoke(h, holder, phase);
			}
		} else {
			for (Handler h : g.taps.success(phase)) {
				boolean ok = h.accepts(phase, holder, false, null);
				log.debug(
						"BUS: TAP success accepts?={} component[{}]='{}' handler={} phase={} name='{}'",
						ok,
						componentIdx,
						componentName,
						safeHandlerName(h),
						phase,
						holder.name());
				if (ok) safeInvoke(h, holder, phase);
			}
		}
	}

	// ---- eligibility (without invoking) ----

	private boolean hasAnyEligibleSuccess(
			List<Handler> hs,
			TelemetryHolder holder,
			Lifecycle phase,
			int componentIdx,
			String componentName,
			String bucketName) {
		if (hs == null || hs.isEmpty()) return false;
		for (Handler h : hs) {
			if (h.getMode() == DispatchMode.FAILURE) continue;
			boolean ok = h.accepts(phase, holder, false, null);
			log.debug(
					"BUS: match-check [{}]='{}' [{}] accepts?={} handler={} mode=SUCCESS phase={} name='{}'",
					componentIdx,
					componentName,
					bucketName,
					ok,
					safeHandlerName(h),
					phase,
					holder.name());
			if (ok) return true;
		}
		return false;
	}

	private boolean hasAnyEligibleFailure(
			List<Handler> hs,
			TelemetryHolder holder,
			Lifecycle phase,
			Throwable error,
			int componentIdx,
			String componentName,
			String bucketName) {
		if (hs == null || hs.isEmpty()) return false;
		for (Handler h : hs) {
			if (h.getMode() == DispatchMode.SUCCESS) continue;
			boolean ok = h.accepts(phase, holder, true, error);
			log.debug(
					"BUS: match-check [{}]='{}' [{}] accepts?={} handler={} mode=FAILURE phase={} name='{}' ex={}",
					componentIdx,
					componentName,
					bucketName,
					ok,
					safeHandlerName(h),
					phase,
					holder.name(),
					(error == null ? "-" : error.getClass().getSimpleName()));
			if (ok) return true;
		}
		return false;
	}

	// ---- invocation paths ----

	private void runSuccess(
			List<Handler> hs,
			TelemetryHolder holder,
			Lifecycle phase,
			int componentIdx,
			String componentName,
			String bucketName) {
		if (hs == null) return;
		for (Handler h : hs) {
			if (h.getMode() == DispatchMode.FAILURE) continue;
			boolean ok = h.accepts(phase, holder, false, null);
			log.debug(
					"BUS: invoke-check [{}]='{}' [{}] accepts?={} handler={} mode=SUCCESS phase={} name='{}'",
					componentIdx,
					componentName,
					bucketName,
					ok,
					safeHandlerName(h),
					phase,
					holder.name());
			if (ok) safeInvoke(h, holder, phase);
		}
	}

	private void runFailure(
			List<Handler> hs,
			TelemetryHolder holder,
			Lifecycle phase,
			Throwable error,
			int componentIdx,
			String componentName,
			String bucketName) {
		if (hs == null) return;
		for (Handler h : hs) {
			if (h.getMode() == DispatchMode.SUCCESS) continue;
			boolean ok = h.accepts(phase, holder, true, error);
			log.debug(
					"BUS: invoke-check [{}]='{}' [{}] accepts?={} handler={} mode=FAILURE phase={} name='{}' ex={}",
					componentIdx,
					componentName,
					bucketName,
					ok,
					safeHandlerName(h),
					phase,
					holder.name(),
					(error == null ? "-" : error.getClass().getSimpleName()));
			if (ok) safeInvoke(h, holder, phase);
		}
	}

	/**
	 * Component-scoped unmatched: @OnUnMatchedEvent(scope=COMPONENT) for this group only. Returns true if any invoked.
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

		// Combined unmatched first
		for (Handler h : g.unmatched.combined(phase)) {
			boolean ok = h.accepts(phase, holder, failed, error);
			log.debug(
					"BUS: component-unmatched [{}]='{}' [combined] accepts?={} handler={} phase={} name='{}' failed={}",
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
		// Mode-specific unmatched
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
			Lifecycle phase, TelemetryHolder holder, boolean failed, Throwable error) {
		boolean invoked = false;
		for (int i = 0; i < groups.size(); i++) {
			HandlerGroup g = groups.get(i);
			String componentName = safeComponentName(g);

			for (Handler h : g.globalUnmatched.combined(phase)) {
				boolean ok = h.accepts(phase, holder, failed, error);
				log.debug(
						"BUS: GLOBAL-unmatched [{}]='{}' [combined] accepts?={} handler={} phase={} name='{}' failed={}",
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

	private void safeInvoke(Handler h, TelemetryHolder holder, Lifecycle phase) {
		try {
			// Dump holder before invoking
			logTelemetryHolder(holder, phase);
			// Preview annotation-driven pulls
			preInvokeBindingPreview(h, holder);

			log.debug(
					"BUS: invoking handler={} mode={} phase={} name='{}' failed={}",
					safeHandlerName(h),
					h.getMode(),
					phase,
					holder.name(),
					holder.throwable() != null);
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

	/* ====== detailed logging helpers ====== */

	private void logTelemetryHolder(TelemetryHolder holder, Lifecycle phase) {
		Map<String, Object> attrs = attrsMap(holder);
		Map<String, Object> ctx = ctxMap(holder);

		log.debug(
				"BUS: holder dump phase={} name='{}' isStep={} failed={} attrs.size={} ctx.size={}",
				phase,
				holder.name(),
				bool(holder.isStep()),
				holder.throwable() != null,
				(attrs == null ? 0 : attrs.size()),
				(ctx == null ? 0 : ctx.size()));

		if (attrs != null && !attrs.isEmpty()) {
			log.debug("BUS: holder attrs -> {}", attrs);
		}
		if (ctx != null && !ctx.isEmpty()) {
			log.debug("BUS: holder ctx -> {}", ctx);
		}
	}

	private void preInvokeBindingPreview(Handler h, TelemetryHolder holder) {
		Method m = tryExtractUnderlyingMethod(h);
		if (m == null) {
			log.debug("BUS: binding preview handler={} method=? (not introspectable)", safeHandlerName(h));
			return;
		}
		Map<String, Object> attrs = attrsMap(holder);
		Map<String, Object> ctx = ctxMap(holder);

		List<String> pullAttrKeys = new java.util.ArrayList<>();
		List<String> pullCtxKeys = new java.util.ArrayList<>();

		Annotation[][] paa = m.getParameterAnnotations();
		for (Annotation[] anns : paa) {
			for (Annotation ann : anns) {
				String at = ann.annotationType().getName();
				if ("com.obsinity.telemetry.annotations.PullAttribute".equals(at)) {
					String key = firstNonBlank(readStringMember(ann, "value"), readStringMember(ann, "name"));
					if (!key.isBlank()) {
						pullAttrKeys.add(key);
						Object val = (attrs == null) ? null : attrs.get(key);
						log.debug(
								"BUS: PREVIEW PullAttribute key='{}' -> {} type={}",
								key,
								(val == null ? "NULL" : "FOUND"),
								(val == null ? "-" : val.getClass().getName()));
					}
				} else if ("com.obsinity.telemetry.annotations.PullContextValue".equals(at)) {
					String key = firstNonBlank(readStringMember(ann, "value"), readStringMember(ann, "name"));
					if (!key.isBlank()) {
						pullCtxKeys.add(key);
						Object val = (ctx == null) ? null : ctx.get(key);
						log.debug(
								"BUS: PREVIEW PullContextValue key='{}' -> {} type={}",
								key,
								(val == null ? "NULL" : "FOUND"),
								(val == null ? "-" : val.getClass().getName()));
					}
				}
			}
		}
		log.debug(
				"BUS: binding preview handler={} pullAttrKeys={} pullCtxKeys={}",
				safeHandlerName(h),
				pullAttrKeys,
				pullCtxKeys);
	}

	// --- name + printing helpers ---

	private static String safeComponentName(HandlerGroup g) {
		try {
			return g.componentName; // public final
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
			Method asMap = attrs.getClass().getMethod("asMap");
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

	@SuppressWarnings("unchecked")
	private static Map<String, Object> attrsMap(TelemetryHolder holder) {
		try {
			Object attrs = holder.attributes();
			if (attrs == null) return null;
			Method asMap = attrs.getClass().getMethod("asMap");
			Object m = asMap.invoke(attrs);
			if (m instanceof Map<?, ?> map) {
				return (Map<String, Object>) map;
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> ctxMap(TelemetryHolder holder) {
		try {
			Method getter;
			try {
				getter = holder.getClass().getMethod("getEventContext");
			} catch (NoSuchMethodException ignored) {
				getter = holder.getClass().getMethod("eventContext");
			}
			Object ctx = getter.invoke(holder);
			if (ctx == null) return null;

			try {
				Method asMap = ctx.getClass().getMethod("asMap");
				Object m = asMap.invoke(ctx);
				if (m instanceof Map<?, ?> map) {
					return (Map<String, Object>) map;
				}
			} catch (NoSuchMethodException ignored) {
				if (ctx instanceof Map<?, ?> map) {
					return (Map<String, Object>) map;
				}
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	private static Method tryExtractUnderlyingMethod(Handler h) {
		for (Class<?> c = h.getClass(); c != null; c = c.getSuperclass()) {
			for (Field f : c.getDeclaredFields()) {
				if (Method.class.equals(f.getType())) {
					try {
						f.setAccessible(true);
						Method m = (Method) f.get(h);
						if (m != null) return m;
					} catch (Throwable ignored) {
					}
				}
			}
			try {
				Method gm = c.getMethod("getMethod");
				Object mv = gm.invoke(h);
				if (mv instanceof Method m) return m;
			} catch (Throwable ignored) {
			}
		}
		return null;
	}

	private static String readStringMember(Annotation ann, String member) {
		try {
			Method m = ann.annotationType().getMethod(member);
			Object v = m.invoke(ann);
			return (v == null) ? "" : String.valueOf(v);
		} catch (Throwable ignored) {
			return "";
		}
	}

	private static String firstNonBlank(String a, String b) {
		if (a != null && !a.isBlank()) return a;
		return (b == null) ? "" : b;
	}
}
