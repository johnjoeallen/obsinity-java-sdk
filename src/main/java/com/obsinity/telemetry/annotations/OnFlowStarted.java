package com.obsinity.telemetry.annotations;

import org.springframework.core.annotation.AliasFor;
import java.lang.annotation.*;

/**
 * Handle a completed flow (any outcome) for a given event name.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnFlowStarted {
	@AliasFor("name")
	String value() default "";

	@AliasFor("value")
	String name() default "";
}
