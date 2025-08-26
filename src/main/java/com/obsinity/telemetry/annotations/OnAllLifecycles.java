package com.obsinity.telemetry.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.obsinity.telemetry.model.Lifecycle;

/**
 * Convenience meta-annotation that expands to:
 *
 * <ul>
 *   <li>{@link OnFlowLifecycle}(FLOW_STARTED)
 *   <li>{@link OnFlowLifecycle}(FLOW_FINISHED)
 *   <li>{@link OnFlowLifecycle}(ROOT_FLOW_FINISHED)
 * </ul>
 *
 * Apply at the class level (e.g. on a receiver) to make it eligible for all major phases. Individual methods can still
 * narrow further by declaring their own lifecycle filters.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@OnFlowLifecycle(Lifecycle.FLOW_STARTED)
@OnFlowLifecycle(Lifecycle.FLOW_FINISHED)
@OnFlowLifecycle(Lifecycle.ROOT_FLOW_FINISHED)
public @interface OnAllLifecycles {}
