package com.obsinity.telemetry.annotations;

import com.obsinity.telemetry.model.Lifecycle;

import java.lang.annotation.*;

/**
 * Convenience meta-annotation that expands to:
 * <ul>
 *   <li>{@link OnEventLifecycle}(FLOW_STARTED)</li>
 *   <li>{@link OnEventLifecycle}(FLOW_FINISHED)</li>
 *   <li>{@link OnEventLifecycle}(ROOT_FLOW_FINISHED)</li>
 * </ul>
 *
 * Apply at the class level (e.g. on a receiver) to make it eligible for all major phases.
 * Individual methods can still narrow further by declaring their own lifecycle filters.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@OnEventLifecycle(Lifecycle.FLOW_STARTED)
@OnEventLifecycle(Lifecycle.FLOW_FINISHED)
@OnEventLifecycle(Lifecycle.ROOT_FLOW_FINISHED)
public @interface OnAllLifecycles {
}
