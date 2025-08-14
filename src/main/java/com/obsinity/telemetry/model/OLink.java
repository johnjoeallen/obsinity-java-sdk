package com.obsinity.telemetry.model;

import java.util.LinkedHashMap;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.trace.data.LinkData;

@JsonInclude(Include.NON_NULL)
public final class OLink {
	private final String traceId;
	private final String spanId;
	private final OAttributes attributes;

	public OLink(String traceId, String spanId, OAttributes attributes) {
		this.traceId = Objects.requireNonNull(traceId, "traceId");
		this.spanId = Objects.requireNonNull(spanId, "spanId");
		this.attributes = (attributes == null ? new OAttributes(new LinkedHashMap<>()) : attributes);
	}

	public String traceId() {
		return traceId;
	}

	public String spanId() {
		return spanId;
	}

	public OAttributes attributes() {
		return attributes;
	}

	public LinkData toOtel() {
		SpanContext ctx = SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());
		return LinkData.create(ctx, attributes.toOtel());
	}

	public static OLink fromOtel(LinkData ld) {
		if (ld == null) return null;
		return new OLink(
				ld.getSpanContext().getTraceId(),
				ld.getSpanContext().getSpanId(),
				OAttributes.fromOtel(ld.getAttributes()));
	}
}
