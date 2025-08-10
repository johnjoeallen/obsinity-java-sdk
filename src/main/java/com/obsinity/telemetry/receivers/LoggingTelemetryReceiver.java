package com.obsinity.telemetry.receivers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.obsinity.telemetry.model.TelemetryHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default receiver that logs TelemetryHolder snapshots on flow start/finish.
 * - INFO: compact line with key identifiers
 * - DEBUG: pretty JSON payload of the entire holder
 */
@Component
public class LoggingTelemetryReceiver implements TelemetryReceiver {

	private static final Logger log = LoggerFactory.getLogger(LoggingTelemetryReceiver.class);

	private final ObjectMapper mapper;

	public LoggingTelemetryReceiver(ObjectMapper mapper) {
		// Use the Spring Boot mapper if provided; otherwise create a safe default.
		if (mapper != null) {
			this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
		} else {
			this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
		}
	}

	@Override
	public void flowStarted(TelemetryHolder h) {
		if (h == null) return;
		log.info("obsinity flow-start name={} kind={} traceId={} spanId={} parentSpanId={} serviceId={} correlationId={}",
			safe(h.name()), safe(h.kind()), safe(h.traceId()), safe(h.spanId()), safe(h.parentSpanId()),
			safe(h.serviceId()), safe(h.correlationId()));
		if (log.isDebugEnabled()) {
			log.debug("flow-start payload:\n{}", toJson(h));
		}
	}

	@Override
	public void flowFinished(TelemetryHolder h) {
		if (h == null) return;
		log.info("obsinity flow-finish name={} kind={} traceId={} spanId={} parentSpanId={} serviceId={} correlationId={}",
			safe(h.name()), safe(h.kind()), safe(h.traceId()), safe(h.spanId()), safe(h.parentSpanId()),
			safe(h.serviceId()), safe(h.correlationId()));
		if (log.isDebugEnabled()) {
			log.debug("flow-finish payload:\n{}", toJson(h));
		}
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
