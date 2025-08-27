package com.obsinity.telemetry.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an <strong>event receiver</strong> within the Obsinity telemetry framework.
 *
 * <p>An {@code @EventReceiver} is a flow-centric handler container: it declares one or more methods that react to flow
 * lifecycle events (such as start or completion). The framework discovers these beans at runtime and wires them into
 * the telemetry dispatch bus.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @EventReceiver
 * @OnEventScope("order")
 * public class FlowHandlers {
 *
 *     @OnFlowCompleted("order")
 *     @OnOutcome(FAILURE)
 *     public void onFailure(FlowContext ctx, Throwable error) {
 *         // runs only when the flow completes with outcome "FAILURE"
 *     }
 *
 *     @OnFlowSuccess("order")
 *     public void onSuccess(FlowContext ctx) {
 *         // runs only when the flow completes successfully
 *     }
 * }
 * }</pre>
 *
 * <p>Outcome filters like {@link OnFlowSuccess} or {@link OnOutcome} can also be placed at the <em>class level</em> to
 * act as defaults for all handlers in the receiver.
 *
 * @see OnFlowCompleted
 * @see OnOutcome
 * @see OnFlowSuccess
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EventReceiver {}
