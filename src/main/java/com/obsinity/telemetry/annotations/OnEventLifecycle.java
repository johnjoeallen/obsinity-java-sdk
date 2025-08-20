package com.obsinity.telemetry.annotations;

import com.obsinity.telemetry.model.Lifecycle;
import java.lang.annotation.*;

/**
 * Component-level filter restricting visible lifecycles (e.g. FLOW_FINISHED).
 * Repeat to allow multiple lifecycles.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(OnEventLifecycles.class)
@Documented
public @interface OnEventLifecycle {
	Lifecycle value();
}
