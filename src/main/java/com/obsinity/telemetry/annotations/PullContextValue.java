package com.obsinity.telemetry.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Consumer-side: bind a context value from the current event into a handler parameter.
 *
 * <p>Usage: public void handle(@PullContextValue("retry") boolean retry) { ... } // or public void
 * handle(@PullContextValue(name = "retry") boolean retry) { ... }
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PullContextValue {
	/** Context key to read. */
	String name() default "";
}
