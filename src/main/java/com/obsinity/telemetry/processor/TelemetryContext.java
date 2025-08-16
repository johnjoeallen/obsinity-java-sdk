package com.obsinity.telemetry.processor;

import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.obsinity.telemetry.model.TelemetryHolder;

/**
 * Facade for writing to the <b>current TelemetryHolder</b> (flow or step).
 *
 * <ul>
 *   <li><b>Attributes</b> (persisted): {@link #putAttr(String, Object)} / {@link #putAllAttrs(Map)} — written to
 *       {@link TelemetryHolder#attributes()}.
 *   <li><b>EventContext</b> (ephemeral): {@link #putContext(String, Object)} / {@link #putAllContext(Map)} — written to
 *       {@link TelemetryHolder#getEventContext()}.
 * </ul>
 */
@Component
public class TelemetryContext {

	private final TelemetryProcessorSupport support;

	public TelemetryContext(TelemetryProcessorSupport support) {
		this.support = Objects.requireNonNull(support, "TelemetryProcessorSupport must not be null");
	}

	/* ===================== Attributes (persisted) ===================== */

	/** Back-compat alias for {@link #putAttr(String, Object)} that returns the same typed value. */
	public <T> T put(String key, T value) {
		return putAttr(key, value);
	}

	/** Back-compat alias for {@link #putAllAttrs(Map)}. */
	public void putAll(Map<String, ?> map) {
		putAllAttrs(map);
	}

	/** Adds a single <b>attribute</b> to the current holder and returns the same typed value. */
	public <T> T putAttr(String key, T value) {
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
			if (k != null && !k.isBlank()) {
				holder.attributes().put(k, v);
			}
		});
	}

	/* ===================== EventContext (ephemeral) ===================== */

	/** Adds a single <b>EventContext</b> entry to the current holder and returns the same typed value. */
	public <T> T putContext(String key, T value) {
		if (key == null || key.isBlank()) return value;
		TelemetryHolder holder = support.currentHolder();
		if (holder != null) {
			holder.getEventContext().put(key, value);
		}
		return value;
	}

	/** Adds all entries to the <b>EventContext</b> of the current holder. */
	public void putAllContext(Map<String, ?> map) {
		if (map == null || map.isEmpty()) return;
		TelemetryHolder holder = support.currentHolder();
		if (holder == null) return;

		map.forEach((k, v) -> {
			if (k != null && !k.isBlank()) {
				holder.getEventContext().put(k, v);
			}
		});
	}
}
