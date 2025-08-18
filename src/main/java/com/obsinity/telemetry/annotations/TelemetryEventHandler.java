package com.obsinity.telemetry.annotations;

import java.lang.annotation.*;

/**
 * Marks a class as a telemetry event handler component.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>Only beans annotated with {@code @TelemetryEventHandler} are scanned for
 *       {@link OnEvent}, {@link OnEveryEvent}, and {@link OnUnMatchedEvent} methods.</li>
 *   <li>May optionally be combined with {@link EventScope} to restrict the
 *       domain of events considered for this component.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @TelemetryEventHandler
 * public class OrderHandlers {
 *   @OnEvent(name = "orders.checkout")
 *   void handleCheckout(...) { ... }
 * }
 *
 * @TelemetryEventHandler
 * @EventScope("order.")
 * public class OrderScopedFallback {
 *   @OnUnMatchedEvent(scope = OnUnMatchedEvent.Scope.COMPONENT)
 *   void onUnmatched(OEvent e) { ... }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TelemetryEventHandler {
}
