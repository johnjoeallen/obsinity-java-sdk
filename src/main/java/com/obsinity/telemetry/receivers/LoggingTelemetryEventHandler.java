package com.obsinity.telemetry.receivers;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.obsinity.telemetry.annotations.BindEventThrowable;
import com.obsinity.telemetry.annotations.DispatchMode;
import com.obsinity.telemetry.annotations.OnEvent;
import com.obsinity.telemetry.annotations.TelemetryEventHandler;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.OEvent;
import com.obsinity.telemetry.model.TelemetryHolder;

/**
 * Default handler that logs TelemetryHolder snapshots on flow start/finish. - INFO: compact line with key identifiers -
 * DEBUG: pretty JSON payload of the entire holder
 */
@TelemetryEventHandler
@Component
public class LoggingTelemetryEventHandler {

	private static final Logger log = LoggerFactory.getLogger(LoggingTelemetryEventHandler.class);

	private final ObjectMapper mapper;

	public LoggingTelemetryEventHandler(ObjectMapper mapper) {
		// Use the Spring Boot mapper if provided; otherwise create a safe default.
		if (mapper != null) {
			this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
		} else {
			this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
		}
	}

	/** Catch‑all error handler required by strict scanner validation. */
	@OnEvent(mode = DispatchMode.ERROR)
	public void onAnyError(@BindEventThrowable Exception ex, TelemetryHolder holder) {
		// Keeping it simple; presence satisfies validation. Optional: log the error.
		if (log.isWarnEnabled()) {
			log.warn(
					"Telemetry ERROR event: name={} traceId={} spanId={} ex={}",
					holder.name(),
					holder.traceId(),
					holder.spanId(),
					ex.toString());
		}
	}

	@OnEvent(lifecycle = {Lifecycle.FLOW_STARTED})
	public void onFlowStarted(TelemetryHolder h) {
		if (h == null) return;

		if (log.isInfoEnabled()) {
			log.info(
					"obsinity flow-start name={} kind={} traceId={} spanId={} parentSpanId={} serviceId={} correlationId={}",
					safe(h.name()),
					safe(h.kind()),
					safe(h.traceId()),
					safe(h.spanId()),
					safe(h.parentSpanId()),
					safe(h.serviceId()),
					safe(h.correlationId()));
			if (log.isDebugEnabled()) {
				log.debug("flow-start payload:\n{}", toJson(h));
			}
		}
	}

	@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})
	public void onFlowFinished(TelemetryHolder h) {
		if (h == null) return;

		final List<OEvent> events = (h.events() != null) ? h.events() : List.of();

		log.info(
				"obsinity flow-finish name={} kind={} traceId={} spanId={} parentSpanId={} serviceId={} correlationId={} events={}",
				safe(h.name()),
				safe(h.kind()),
				safe(h.traceId()),
				safe(h.spanId()),
				safe(h.parentSpanId()),
				safe(h.serviceId()),
				safe(h.correlationId()),
				events.size());

		// One line per event (INFO)
		for (OEvent e : events) {
			Long durationMillis = durationMillis(e.epochNanos(), e.endEpochNanos());
			int attrCount = (e.attributes() != null && e.attributes().asMap() != null)
					? e.attributes().asMap().size()
					: 0;

			log.info(
					"obsinity flow-finish event name={} startNanos={} endNanos={} durationMillis={} attributes={}",
					safe(e.name()),
					e.epochNanos(),
					e.endEpochNanos(),
					durationMillis == null ? "-" : durationMillis,
					attrCount);
		}

		if (log.isDebugEnabled()) {
			// full payload (including events) at DEBUG
			log.debug("flow-finish payload:\n{}", toJson(h));
		}
	}

	private static Long durationMillis(Long startNanos, Long endNanos) {
		if (startNanos == null || endNanos == null) return null; // treat unknowns as unknown
		long diffNanos = endNanos - startNanos;
		return Math.floorDiv(diffNanos, 1_000_000L); // nanos → millis (handles negatives correctly)
	}

	private static Long durationMillis(Instant start, Instant end) {
		if (start == null || end == null) return null;
		return java.time.Duration.between(start, end).toMillis();
	}

	private String toJson(TelemetryHolder holder) {
		try {
			return mapper.writeValueAsString(holder);
		} catch (JsonProcessingException e) {
			// Fallback to toString() if serialization fails
			return String.valueOf(holder);
		}
	}

	private static Object safe(Object o) {
		return (o == null) ? "-" : o;
	}
}
