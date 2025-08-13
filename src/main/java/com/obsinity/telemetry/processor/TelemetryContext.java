package com.obsinity.telemetry.processor;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.obsinity.telemetry.model.TelemetryHolder;

/**
 * Provides application code with a simple, type-safe API for adding attributes to the "current telemetry context".
 *
 * <p>The "current telemetry context" is resolved from {@link TelemetryProcessorSupport} and can represent either:
 *
 * <ul>
 *   <li>The active {@code @Flow} – in which case attributes are stored on the flow's
 *       {@link TelemetryHolder.OAttributes}.
 *   <li>The active {@code @Step} event – in which case attributes are stored on the currently executing
 *       {@link TelemetryHolder.OEvent}'s attributes.
 * </ul>
 *
 * Callers do not need to know which scope they are in; {@link TelemetryHolder#contextPut(String, Object)} automatically
 * routes the attribute to the correct location.
 *
 * <p>Attributes set here will be included in the emitted telemetry data for the current flow/step and are visible to
 * {@link com.obsinity.telemetry.receivers.TelemetryReceiver} implementations.
 *
 * <p>This component is typically injected into application services and called from code executing inside an annotated
 * {@code @Flow} or {@code @Step} method.
 *
 * <pre>{@code
 * @Service
 * public class PaymentService {
 *
 *     private final TelemetryContext telemetry;
 *
 *     public PaymentService(TelemetryContext telemetry) {
 *         this.telemetry = telemetry;
 *     }
 *
 *     @Step(name = "charge")
 *     public void charge(String userId, long amountCents) {
 *         telemetry.put("user.id", userId);
 *         telemetry.put("amount.cents", amountCents);
 *         // business logic...
 *     }
 * }
 * }</pre>
 */
@Component
public class TelemetryContext {

	private final TelemetryProcessorSupport support;

	public TelemetryContext(TelemetryProcessorSupport support) {
		this.support = support;
	}

	/**
	 * Adds a single attribute to the current telemetry context.
	 *
	 * <p>If a {@link TelemetryHolder} is active (inside a flow or step), delegates to
	 * {@link TelemetryHolder#contextPut(String, Object)}.
	 *
	 * @param key attribute key; must not be {@code null}
	 * @param value attribute value; may be {@code null}
	 */
	public Object put(String key, Object value) {
		TelemetryHolder holder = support.currentHolder();
		if (holder != null) {
			holder.contextPut(key, value);
		}

		return value;
	}

	/**
	 * Adds all entries from the provided map as attributes to the current telemetry context.
	 *
	 * <p>If a {@link TelemetryHolder} is active (inside a flow or step), each entry is delegated to
	 * {@link TelemetryHolder#contextPut(String, Object)}.
	 *
	 * @param map map of attributes to add; {@code null} or empty maps are ignored
	 */
	public void putAll(Map<String, ?> map) {
		if (map == null || map.isEmpty()) {
			return;
		}
		TelemetryHolder holder = support.currentHolder();
		if (holder != null) {
			for (Map.Entry<String, ?> e : map.entrySet()) {
				holder.contextPut(e.getKey(), e.getValue());
			}
		}
	}
}
