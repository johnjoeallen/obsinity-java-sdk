package com.obsinity.telemetry.processor;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.obsinity.telemetry.model.TelemetryHolder;

/**
 * Provides application code with a simple API for adding telemetry data to the <b>current TelemetryHolder</b> (flow
 * holder or the temporary step holder).
 *
 * <p>What you can write:
 *
 * <ul>
 *   <li><b>Attributes</b> (persisted): {@link #putAttr(String, Object)} and {@link #putAllAttrs(Map)}. Always written
 *       to the current holder's {@link TelemetryHolder#attributes()}.
 *   <li><b>EventContext</b> (ephemeral, non-serialized): {@link #putContext(String, Object)} and
 *       {@link #putAllContext(Map)}. Always written to the current holder's {@link TelemetryHolder#eventContext()}.
 * </ul>
 *
 * <p>For steps, the processor will fold the step holder into a parent event after completion, carrying over both
 * attributes and context into the resulting {@code OEvent}.
 */
@Component
public class TelemetryContext {

	private final TelemetryProcessorSupport support;

	public TelemetryContext(TelemetryProcessorSupport support) {
		this.support = support;
	}

	/* ===================== Attributes (persisted) ===================== */

	/** Back-compat alias for {@link #putAttr(String, Object)}. */
	public Object put(String key, Object value) {
		return putAttr(key, value);
	}

	/** Back-compat alias for {@link #putAllAttrs(Map)}. */
	public void putAll(Map<String, ?> map) {
		putAllAttrs(map);
	}

	/** Adds a single <b>attribute</b> to the current holder. */
	public Object putAttr(String key, Object value) {
		if (key == null || key.isBlank()) return value;
		TelemetryHolder holder = support.currentHolder();
		if (holder != null) {
			holder.attributes().put(key, value);
		}
		return value;
	}

	/** Adds all entries as <b>attributes</b> to the current holder. */
	public void putAllAttrs(Map<String, ?> map) {
		if (map == null || map.isEmpty()) return;
		TelemetryHolder holder = support.currentHolder();
		if (holder == null) return;

		map.forEach((k, v) -> {
			if (k != null && !k.isBlank()) holder.attributes().put(k, v);
		});
	}

	/* ===================== EventContext (ephemeral, non-serialized) ===================== */

	/** Adds a single <b>EventContext</b> entry to the current holder. */
	public Object putContext(String key, Object value) {
		if (key == null || key.isBlank()) return value;
		TelemetryHolder holder = support.currentHolder();
		if (holder != null) {
			holder.eventContext().put(key, value);
		}
		return value;
	}

	/** Adds all entries to the <b>EventContext</b> of the current holder. */
	public void putAllContext(Map<String, ?> map) {
		if (map == null || map.isEmpty()) return;
		TelemetryHolder holder = support.currentHolder();
		if (holder == null) return;

		map.forEach((k, v) -> {
			if (k != null && !k.isBlank()) holder.eventContext().put(k, v);
		});
	}
}
