package com.obsinity.telemetry.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.obsinity.telemetry.model.Lifecycle;

/** Component-level filter restricting visible lifecycles (e.g. FLOW_FINISHED). Repeat to allow multiple lifecycles. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(OnFlowLifecycles.class)
@Documented
public @interface OnFlowLifecycle {
	Lifecycle value();
}
