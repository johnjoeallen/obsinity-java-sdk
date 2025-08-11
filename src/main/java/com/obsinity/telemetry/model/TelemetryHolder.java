package com.obsinity.telemetry.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.StatusData;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * OTEL-shaped telemetry container with Obsinity-native fields.
 *
 * <p><strong>Service ID requirement:</strong> You MUST provide a service identifier either at the
 * top level {@link #serviceId} or in {@code resource.attributes["service.id"]}. If both exist they must match.
 * Use {@link #effectiveServiceId()} to read the resolved value.</p>
 *
 * <p>Wrapped (non-enum) OTEL concepts exposed via Obsinity wrappers:
 * <ul>
 *   <li>{@link OResource} (wraps {@link Resource})</li>
 *   <li>{@link OAttributes} (wraps {@link Attributes})</li>
 *   <li>{@link OEvent} (wraps {@link EventData} + adds {@code endEpochNanos} and monotonic start)</li>
 *   <li>{@link OLink} (wraps {@link LinkData})</li>
 *   <li>{@link OStatus} (wraps {@link StatusData})</li>
 * </ul>
 * Enums like {@link SpanKind} and {@link StatusCode} are used directly from OTEL.</p>
 */
@JsonInclude(Include.NON_NULL)
public class TelemetryHolder {

	public static final String SERVICE_ID_ATTR = "service.id";

	/* ── OTEL-ish core ───────────────────────────────────────────── */
	private String name;
	private Instant timestamp;
	private Long timeUnixNano;
	private Instant endTimestamp;
	private String traceId;
	private String spanId;
	private String parentSpanId;
	private SpanKind kind;                 // OTEL enum
	private OResource resource;            // wrapper
	private OAttributes attributes;        // wrapper
	private List<OEvent> events;           // mutable
	private List<OLink> links;             // mutable
	private OStatus status;                // wrapper

	/* ── Obsinity-native ─────────────────────────────────────────── */
	private String serviceId;              // required here OR in resource["service.id"]
	private String correlationId;
	private Map<String, Object> extensions;    // mutable free-form
	private Boolean synthetic;

	/* ── Event context cursor (for nested steps) ──────────────────── */
	private final Deque<OEvent> eventStack = new ArrayDeque<>();

	/**
	 * Full constructor (validates service id consistency).
	 */
	public TelemetryHolder(
		String name,
		Instant timestamp,
		Long timeUnixNano,
		Instant endTimestamp,
		String traceId,
		String spanId,
		String parentSpanId,
		SpanKind kind,
		OResource resource,
		OAttributes attributes,
		List<OEvent> events,
		List<OLink> links,
		OStatus status,
		String serviceId,
		String correlationId,
		Map<String, Object> extensions,
		Boolean synthetic
	) {
		this.name = name;
		this.timestamp = timestamp;
		this.timeUnixNano = timeUnixNano;
		this.endTimestamp = endTimestamp;
		this.traceId = traceId;
		this.spanId = spanId;
		this.parentSpanId = parentSpanId;
		this.kind = kind;
		this.resource = resource;
		this.attributes = attributes != null ? attributes : new OAttributes(new LinkedHashMap<>());
		this.events = events != null ? events : new ArrayList<>();
		this.links = links != null ? links : new ArrayList<>();
		this.status = status;
		this.serviceId = serviceId;
		this.correlationId = correlationId;
		this.extensions = (extensions != null ? extensions : new LinkedHashMap<>());
		this.synthetic = synthetic;

		validateServiceIdConsistency();
	}

	/* ========================= Accessors (record-like) ========================= */
	public String name() { return name; }
	public Instant timestamp() { return timestamp; }
	public Long timeUnixNano() { return timeUnixNano; }
	public Instant endTimestamp() { return endTimestamp; }
	public String traceId() { return traceId; }
	public String spanId() { return spanId; }
	public String parentSpanId() { return parentSpanId; }
	public SpanKind kind() { return kind; }
	public OResource resource() { return resource; }
	public OAttributes attributes() { return attributes; }
	public List<OEvent> events() { return events; }           // MUTABLE
	public List<OLink> links() { return links; }              // MUTABLE
	public OStatus status() { return status; }
	public String serviceId() { return serviceId; }
	public String correlationId() { return correlationId; }
	public Map<String, Object> extensions() { return extensions; } // MUTABLE
	public Boolean synthetic() { return synthetic; }

	/* Minimal mutators we actually use from the processor */
	public void setEndTimestamp(Instant endTimestamp) {
		this.endTimestamp = endTimestamp;
	}

	/* ========================= Behavior ========================= */

	/**
	 * Application-facing: write to the current context
	 * (top event if present, else flow attributes).
	 */
	public void contextPut(final String key, final Object value) {
		if (key == null || key.isBlank()) {
			return;
		}
		final OEvent currentEvent = eventStack.peekLast();
		if (currentEvent != null) {
			currentEvent.attributes().put(key, value);
		} else {
			attributes().put(key, value);
		}
	}

	/**
	 * Processor-facing: step entry — append an event and push it on the stack so
	 * app code can {@link #contextPut(String, Object)} into it immediately.
	 *
	 * @param name          event name
	 * @param epochNanos    wall-clock start time (for OTEL/export)
	 * @param startNanoTime monotonic start time (for accurate duration; not serialized)
	 * @param initialAttrs  base attributes to seed the event with (nullable)
	 * @return the newly created event (already appended)
	 */
	public OEvent beginStepEvent(final String name,
								 final long epochNanos,
								 final long startNanoTime,
								 final OAttributes initialAttrs) {
		final OAttributes attrs = (initialAttrs != null)
			? initialAttrs
			: new OAttributes(new LinkedHashMap<>());
		final OEvent ev = new OEvent(name, epochNanos, 0L, attrs, 0, startNanoTime);
		events().add(ev);
		eventStack.addLast(ev);
		return ev;
	}

	/**
	 * Processor-facing: step exit — finalize the current event.
	 * Sets phase=finish, merges updates, sets duration from monotonic nanos,
	 * and replaces the last list element with a copy carrying endEpochNanos.
	 */
	public void endStepEvent(final long endEpochNanos,
							 final long endNanoTime,
							 final Map<String, Object> updates) {
		final OEvent ev = eventStack.pollLast();
		if (ev == null) {
			// Intentionally empty: no active event to end
			return;
		}

		final OAttributes attrs = ev.attributes();
		if (updates != null && !updates.isEmpty()) {
			for (Map.Entry<String, Object> e : updates.entrySet()) {
				attrs.put(e.getKey(), e.getValue());
			}
		}

		final long start = ev.getStartNanoTime();
		final long duration = (start > 0L && endNanoTime > 0L) ? (endNanoTime - start) : 0L;
		attrs.put("duration.nanos", duration);
		attrs.put("phase", "finish");

		final List<OEvent> list = events();
		final int lastIdx = list.isEmpty() ? -1 : list.size() - 1;
		if (lastIdx >= 0 && list.get(lastIdx) == ev) {
			list.set(lastIdx, new OEvent(
				ev.name(),
				ev.epochNanos(),
				endEpochNanos,
				attrs,
				ev.droppedAttributesCount(),
				ev.getStartNanoTime()
			));
		} else {
			// Intentionally empty: unexpected ordering (should not happen)
		}
	}

	/**
	 * Resolve service id with top-level preference, fallback to resource["service.id"].
	 */
	public String effectiveServiceId() {
		if (serviceId != null && !serviceId.isBlank()) return serviceId;
		if (resource == null || resource.attributes() == null) return null;
		Object v = resource.attributes().asMap().get(SERVICE_ID_ATTR);
		return v == null ? null : v.toString();
	}

	/**
	 * Validate presence/equality of service id (top-level vs resource).
	 */
	private void validateServiceIdConsistency() {
		String top = (serviceId == null || serviceId.isBlank()) ? null : serviceId;
		String fromRes = null;
		if (resource != null && resource.attributes() != null) {
			Object v = resource.attributes().asMap().get(SERVICE_ID_ATTR);
			if (v != null && !v.toString().isBlank()) fromRes = v.toString();
		}
		if (top == null && fromRes == null) {
			throw new IllegalArgumentException(
				"Missing service identifier: set top-level 'serviceId' or resource.attributes[\"service.id\"]");
		}
		if (top != null && fromRes != null && !top.equals(fromRes)) {
			throw new IllegalArgumentException(
				"Conflicting service identifiers: top-level 'serviceId' != resource.attributes[\"service.id\"]");
		}
	}

	/* ========================= Wrappers ========================= */

	/**
	 * Wrapper around OTEL {@link Resource}.
	 */
	@JsonInclude(Include.NON_NULL)
	public static final class OResource {
		private final OAttributes attributes;

		public OResource(OAttributes attributes) {
			this.attributes = attributes;
		}

		public OAttributes attributes() { return attributes; }

		/* Converters */
		public Resource toOtel() {
			return Resource.create(attributes != null ? attributes.toOtel() : Attributes.empty());
		}

		public static OResource fromOtel(Resource r) {
			return new OResource(OAttributes.fromOtel(r == null ? Attributes.empty() : r.getAttributes()));
		}
	}

	/**
	 * Wrapper around OTEL {@link Attributes} with a mutable String→Object view for JSON.
	 */
	@JsonInclude(Include.NON_NULL)
	public static final class OAttributes {
		private final Map<String, Object> asMap;

		public OAttributes(Map<String, Object> asMap) {
			this.asMap = (asMap != null ? asMap : new LinkedHashMap<>());
		}

		public Map<String, Object> asMap() { return asMap; }

		/**
		 * Q: Should be ObjectMapper things that are not basic types?
		 * @param key
		 * @param value
		 */
		public void put(String key, Object value) {
			if (key != null) {
				asMap.put(key, value);
			}
		}

		/* Converters */
		public Attributes toOtel() {
			AttributesBuilder b = Attributes.builder();
			if (asMap != null) asMap.forEach((k, v) -> putBestEffort(b, k, v));
			return b.build();
		}

		public static OAttributes fromOtel(Attributes attrs) {
			if (attrs == null) return new OAttributes(new LinkedHashMap<>());
			Map<String, Object> m = attrs.asMap().entrySet().stream()
				.collect(Collectors.toMap(
					e -> e.getKey().getKey(),
					Map.Entry::getValue,
					(a, b) -> a,
					LinkedHashMap::new));
			return new OAttributes(m);
		}

		private static void putBestEffort(AttributesBuilder b, String k, Object v) {
			if (v == null) return;
			if (v instanceof String s) b.put(AttributeKey.stringKey(k), s);
			else if (v instanceof Boolean bo) b.put(AttributeKey.booleanKey(k), bo);
			else if (v instanceof Integer i) b.put(AttributeKey.longKey(k), i.longValue());
			else if (v instanceof Long l) b.put(AttributeKey.longKey(k), l);
			else if (v instanceof Float f) b.put(AttributeKey.doubleKey(k), f.doubleValue());
			else if (v instanceof Double d) b.put(AttributeKey.doubleKey(k), d);
			else if (v instanceof List<?> list && list.stream().allMatch(x -> x instanceof String)) {
				@SuppressWarnings("unchecked") List<String> ss = (List<String>) list;
				b.put(AttributeKey.stringArrayKey(k), ss);
			} else {
				b.put(AttributeKey.stringKey(k), v.toString()); // last resort
			}
		}
	}

	/**
	 * Wrapper around OTEL {@link EventData}, with extra fields:
	 * - {@code endEpochNanos} (absolute end time for exporters)
	 * - {@code startNanoTime} (transient, monotonic for accurate duration)
	 * Use {@link #toOtel()} when you need an OTEL-compatible view.
	 */
	@JsonInclude(Include.NON_NULL)
	public static final class OEvent {
		private final String name;
		private final long epochNanos;                 // start (wall clock)
		private final Long endEpochNanos;              // end (wall clock)
		private final OAttributes attributes;
		private final Integer droppedAttributesCount;  // optional, contributes to total count

		// NEW: monotonic start for accurate duration; not serialized
		private final transient long startNanoTime;

		public OEvent(String name,
					  long epochNanos,
					  Long endEpochNanos,
					  OAttributes attributes,
					  Integer droppedAttributesCount,
					  long startNanoTime) {
			this.name = Objects.requireNonNull(name, "name");
			this.epochNanos = epochNanos;
			this.endEpochNanos = endEpochNanos;
			this.attributes = attributes == null ? new OAttributes(new LinkedHashMap<>()) : attributes;
			this.droppedAttributesCount = droppedAttributesCount;
			this.startNanoTime = startNanoTime;
		}

		public String name() { return name; }
		public long epochNanos() { return epochNanos; }
		public Long endEpochNanos() { return endEpochNanos; }
		public OAttributes attributes() { return attributes; }
		public Integer droppedAttributesCount() { return droppedAttributesCount; }
		public long getStartNanoTime() { return startNanoTime; }

		/**
		 * OTEL view (no end time in the interface; exporter can still use {@link #endEpochNanos()}).
		 */
		public EventData toOtel() {
			final Attributes otelAttrs = attributes.toOtel();
			final int total = (droppedAttributesCount == null ? otelAttrs.size() : otelAttrs.size() + droppedAttributesCount);
			return new EventData() {
				@Override
				public long getEpochNanos() { return epochNanos; }
				@Override
				public String getName() { return name; }
				@Override
				public Attributes getAttributes() { return otelAttrs; }
				@Override
				public int getTotalAttributeCount() { return total; }
			};
		}
	}

	/**
	 * Wrapper around OTEL {@link LinkData}.
	 */
	@JsonInclude(Include.NON_NULL)
	public static final class OLink {
		private final String traceId;
		private final String spanId;
		private final OAttributes attributes;

		public OLink(String traceId, String spanId, OAttributes attributes) {
			this.traceId = Objects.requireNonNull(traceId, "traceId");
			this.spanId = Objects.requireNonNull(spanId, "spanId");
			this.attributes = attributes == null ? new OAttributes(new LinkedHashMap<>()) : attributes;
		}

		public String traceId() { return traceId; }
		public String spanId() { return spanId; }
		public OAttributes attributes() { return attributes; }

		public LinkData toOtel() {
			SpanContext ctx = SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());
			return LinkData.create(ctx, attributes.toOtel());
		}

		public static OLink fromOtel(LinkData ld) {
			if (ld == null) return null;
			return new OLink(ld.getSpanContext().getTraceId(), ld.getSpanContext().getSpanId(), OAttributes.fromOtel(ld.getAttributes()));
		}
	}

	/**
	 * Wrapper around OTEL {@link StatusData}. Uses OTEL {@link StatusCode} enum directly.
	 */
	@JsonInclude(Include.NON_NULL)
	public static final class OStatus {
		private final StatusCode code;
		private final String message;

		public OStatus(StatusCode code, String message) {
			this.code = code;
			this.message = message;
		}

		public StatusCode code() { return code; }
		public String message() { return message; }

		public StatusData toOtel() {
			return StatusData.create(code, message);
		}

		public static OStatus fromOtel(StatusData sd) {
			if (sd == null) return null;
			return new OStatus(sd.getStatusCode(), sd.getDescription());
		}
	}
}
