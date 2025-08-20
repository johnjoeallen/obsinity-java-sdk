// src/main/java/com/obsinity/telemetry/receivers/LoggingTelemetryEventHandler.java
package com.obsinity.telemetry.receivers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.obsinity.telemetry.annotations.EventReceiver;
import com.obsinity.telemetry.annotations.OnFlowNotMatched;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;

/**
 * Simple logging receiver for the new flow-centric model:
 *  - Logs component-scoped unmatched events via @OnFlowNotMatched.
 */
@EventReceiver
public class LoggingTelemetryEventHandler {

	private static final Logger log = LoggerFactory.getLogger(LoggingTelemetryEventHandler.class);

	/** Fires when no named @OnFlow*/ // (success/failure/completed) matched within this component.
	@OnFlowNotMatched
	public void onComponentUnmatched(TelemetryHolder h, Lifecycle phase) {
		if (h == null) {
			log.info("component-unmatched: holder=null phase={}", phase);
			return;
		}
		log.info(
			"component-unmatched event={} phase={} traceId={} spanId={} failed={}",
			safe(h.name()),
			phase,
			safe(h.traceId()),
			safe(h.spanId()),
			h.throwable() != null
		);
	}

	private static String safe(String s) {
		return s == null ? "-" : s;
	}
}
