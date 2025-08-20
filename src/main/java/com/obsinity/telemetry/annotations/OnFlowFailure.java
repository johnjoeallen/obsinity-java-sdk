package com.obsinity.telemetry.annotations;

import org.springframework.core.annotation.AliasFor;
import java.lang.annotation.*;

/**
 * Handle a failed completion of the named flow.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnFlowFailure {
	@AliasFor("name")
	String value() default "";

	@AliasFor("value")
	String name() default "";
}
