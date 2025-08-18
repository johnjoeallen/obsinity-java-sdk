package com.obsinity.telemetry.receivers;

import com.obsinity.telemetry.annotations.BindEventThrowable;
import com.obsinity.telemetry.annotations.DispatchMode;
import com.obsinity.telemetry.annotations.OnEveryEvent;
import com.obsinity.telemetry.annotations.OnUnMatchedEvent;
import com.obsinity.telemetry.annotations.TelemetryEventHandler;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple logging receiver:
 *  - Logs every event (tap)
 *  - Logs component-scoped unmatched
 *  - Logs global unmatched failures
 */
@TelemetryEventHandler
public class LoggingTelemetryEventHandler {

	private static final Logger log = LoggerFactory.getLogger(LoggingTelemetryEventHandler.class);

	/** Optional: log every event regardless of matching. */
	@OnEveryEvent(mode = DispatchMode.COMBINED) // lifecycle empty = any
	public void onEvery(TelemetryHolder h, Lifecycle phase) {
		log.debug("event={} phase={} traceId={} spanId={} failed={}",
			safe(h.name()), phase, safe(h.traceId()), safe(h.spanId()), h.throwable() != null);
	}

	/** Fires when no @OnEvent matched within this component. */
	@OnUnMatchedEvent(scope = OnUnMatchedEvent.Scope.COMPONENT, mode = DispatchMode.COMBINED)
	public void onComponentUnmatched(TelemetryHolder h, Lifecycle phase) {
		log.info("component-unmatched event={} phase={} traceId={} spanId={}",
			safe(h.name()), phase, safe(h.traceId()), safe(h.spanId()));
	}

	/** Fires only if no @OnEvent matched anywhere (global fallback) and there was a failure. */
	@OnUnMatchedEvent(scope = OnUnMatchedEvent.Scope.GLOBAL, mode = DispatchMode.FAILURE)
	public void onGlobalFailure(@BindEventThrowable Throwable ex, TelemetryHolder h, Lifecycle phase) {
		log.warn("global-unmatched FAILURE event={} phase={} traceId={} spanId={} ex={}",
			safe(h.name()), phase, safe(h.traceId()), safe(h.spanId()), ex == null ? "-" : ex.toString());
	}

	private static String safe(String s) { return s == null ? "-" : s; }
}
