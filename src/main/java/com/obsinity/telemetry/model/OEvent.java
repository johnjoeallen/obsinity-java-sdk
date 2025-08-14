package com.obsinity.telemetry.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.trace.data.EventData;

@JsonInclude(Include.NON_NULL)
public final class OEvent {
	private final String name;
	private final long epochNanos; // start (wall clock)
	private final Long endEpochNanos; // end (wall clock)
	private final OAttributes attributes;
	private final Integer droppedAttributesCount; // contributes to total count

	// monotonic start for accurate duration; not serialized
	private final transient long startNanoTime;

	// step-scoped EventContext; not serialized
	@JsonIgnore
	private final transient Map<String, Object> eventContext;

	public OEvent(
			String name,
			long epochNanos,
			Long endEpochNanos,
			OAttributes attributes,
			Integer droppedAttributesCount,
			long startNanoTime) {
		this(name, epochNanos, endEpochNanos, attributes, droppedAttributesCount, startNanoTime, new LinkedHashMap<>());
	}

	public OEvent(
			String name,
			long epochNanos,
			Long endEpochNanos,
			OAttributes attributes,
			Integer droppedAttributesCount,
			long startNanoTime,
			Map<String, Object> eventContext) {
		this.name = Objects.requireNonNull(name, "name");
		this.epochNanos = epochNanos;
		this.endEpochNanos = endEpochNanos;
		this.attributes = (attributes == null ? new OAttributes(new LinkedHashMap<>()) : attributes);
		this.droppedAttributesCount = droppedAttributesCount;
		this.startNanoTime = startNanoTime;
		this.eventContext = (eventContext != null ? eventContext : new LinkedHashMap<>());
	}

	public String name() {
		return name;
	}

	public long epochNanos() {
		return epochNanos;
	}

	public Long endEpochNanos() {
		return endEpochNanos;
	}

	public OAttributes attributes() {
		return attributes;
	}

	public Integer droppedAttributesCount() {
		return droppedAttributesCount;
	}

	public long getStartNanoTime() {
		return startNanoTime;
	}

	/** Step-scoped EventContext (non-serialized, mutable). */
	@JsonIgnore
	public Map<String, Object> eventContext() {
		return eventContext;
	}

	/* ========================= EventContext helpers ========================= */

	/** Put a step-scoped context entry (ignored if key is null/blank). */
	public void eventContextPut(final String key, final Object value) {
		if (key == null || key.isBlank()) return;
		eventContext.put(key, value);
	}

	/** Get a step-scoped context value by key (may return null). */
	public Object eventContextGet(final String key) {
		return (key == null) ? null : eventContext.get(key);
	}

	/** @return true if the step context contains the key. */
	public boolean hasEventContextKey(final String key) {
		return key != null && eventContext.containsKey(key);
	}

	/** Optional: read-only view if you need to expose without risking mutation. */
	@JsonIgnore
	public Map<String, Object> eventContextView() {
		return Collections.unmodifiableMap(eventContext);
	}

	/** OTEL view (no end time in the interface; exporter may consult {@link #endEpochNanos()}). */
	public EventData toOtel() {
		final Attributes otelAttrs = attributes.toOtel();
		final int total =
				(droppedAttributesCount == null ? otelAttrs.size() : otelAttrs.size() + droppedAttributesCount);
		return new EventData() {
			@Override
			public long getEpochNanos() {
				return epochNanos;
			}

			@Override
			public String getName() {
				return name;
			}

			@Override
			public Attributes getAttributes() {
				return otelAttrs;
			}

			@Override
			public int getTotalAttributeCount() {
				return total;
			}
		};
	}
}
