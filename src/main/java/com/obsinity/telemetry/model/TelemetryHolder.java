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
import java.util.ArrayList;
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
 *   <li>{@link OEvent} (wraps {@link EventData} + adds {@code endEpochNanos})</li>
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
	private OResource resource;          // wrapper
	private OAttributes attributes;        // wrapper
	private List<OEvent> events;           // mutable
	private List<OLink> links;            // mutable
	private OStatus status;           // wrapper

	/* ── Obsinity-native ─────────────────────────────────────────── */
	private String serviceId;     // required here OR in resource["service.id"]
	private String correlationId;
	private Map<String, Object> extensions;    // mutable free-form
	private Boolean synthetic;

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
	public String name() {
		return name;
	}

	public Instant timestamp() {
		return timestamp;
	}

	public Long timeUnixNano() {
		return timeUnixNano;
	}

	public Instant endTimestamp() {
		return endTimestamp;
	}

	public String traceId() {
		return traceId;
	}

	public String spanId() {
		return spanId;
	}

	public String parentSpanId() {
		return parentSpanId;
	}

	public SpanKind kind() {
		return kind;
	}

	public OResource resource() {
		return resource;
	}

	public OAttributes attributes() {
		return attributes;
	}

	public List<OEvent> events() {
		return events;
	}           // MUTABLE

	public List<OLink> links() {
		return links;
	}              // MUTABLE

	public OStatus status() {
		return status;
	}

	public String serviceId() {
		return serviceId;
	}

	public String correlationId() {
		return correlationId;
	}

	public Map<String, Object> extensions() {
		return extensions;
	} // MUTABLE

	public Boolean synthetic() {
		return synthetic;
	}

	/* Minimal mutators we actually use from the processor */
	public void setEndTimestamp(Instant endTimestamp) {
		this.endTimestamp = endTimestamp;
	}

	/* ========================= Behavior ========================= */

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

		public OAttributes attributes() {
			return attributes;
		}

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

		public Map<String, Object> asMap() {
			return asMap;
		}

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
	 * Wrapper around OTEL {@link EventData}, with an extra {@code endEpochNanos}.
	 * Use {@link #toOtel()} when you need an OTEL-compatible view.
	 */
	@JsonInclude(Include.NON_NULL)
	public static final class OEvent {
		private final String name;
		private final long epochNanos;           // start
		private final Long endEpochNanos;        // end (optional)
		private final OAttributes attributes;
		private final Integer droppedAttributesCount; // optional, contributes to total count

		public OEvent(String name, long epochNanos, Long endEpochNanos, OAttributes attributes, Integer droppedAttributesCount) {
			this.name = Objects.requireNonNull(name, "name");
			this.epochNanos = epochNanos;
			this.endEpochNanos = endEpochNanos;
			this.attributes = attributes == null ? new OAttributes(new LinkedHashMap<>()) : attributes;
			this.droppedAttributesCount = droppedAttributesCount;
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

		/**
		 * OTEL view (no end time in the interface; exporter can still use {@link #endEpochNanos()}).
		 */
		public EventData toOtel() {
			final Attributes otelAttrs = attributes.toOtel();
			final int total = (droppedAttributesCount == null ? otelAttrs.size() : otelAttrs.size() + droppedAttributesCount);
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

		public StatusCode code() {
			return code;
		}

		public String message() {
			return message;
		}

		public StatusData toOtel() {
			return StatusData.create(code, message);
		}

		public static OStatus fromOtel(StatusData sd) {
			if (sd == null) return null;
			return new OStatus(sd.getStatusCode(), sd.getDescription());
		}
	}
}
