package com.obsinity.telemetry.annotations;

import com.obsinity.telemetry.model.Lifecycle;

import java.lang.annotation.*;

/**
 * Convenience meta-annotation that expands to:
 * <ul>
 *   <li>{@link OnFlowLifecycle}(FLOW_STARTED)</li>
 *   <li>{@link OnFlowLifecycle}(FLOW_FINISHED)</li>
 *   <li>{@link OnFlowLifecycle}(ROOT_FLOW_FINISHED)</li>
 * </ul>
 *
 * Apply at the class level (e.g. on a receiver) to make it eligible for all major phases.
 * Individual methods can still narrow further by declaring their own lifecycle filters.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@OnFlowLifecycle(Lifecycle.FLOW_STARTED)
@OnFlowLifecycle(Lifecycle.FLOW_FINISHED)
@OnFlowLifecycle(Lifecycle.ROOT_FLOW_FINISHED)
public @interface OnAllLifecycles {
}
