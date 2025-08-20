package com.obsinity.telemetry.annotations;

import org.springframework.core.annotation.AliasFor;
import java.lang.annotation.*;

/**
 * Handle a successful completion of the named flow.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnFlowSuccess {
	@AliasFor("name")
	String value() default "";

	@AliasFor("value")
	String name() default "";
}
