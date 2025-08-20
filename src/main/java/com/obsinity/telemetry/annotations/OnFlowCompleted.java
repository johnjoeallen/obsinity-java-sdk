package com.obsinity.telemetry.annotations;

import org.springframework.core.annotation.AliasFor;
import java.lang.annotation.*;

/**
 * Handle a flow after it completes (any outcome). Combine with {@link OnOutcome} to
 * restrict to SUCCESS or FAILURE.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnFlowCompleted {
	@AliasFor("name")
	String value() default "";

	@AliasFor("value")
	String name() default "";
}
