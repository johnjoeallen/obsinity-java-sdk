package com.obsinity.telemetry.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a Throwable from the TelemetryHolder into the parameter. If required=true and the target is missing, it is a
 * binding error.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Error {
	/** If true and Throwable (or cause) is missing, treat as binding error. */
	boolean required() default false;

	/**
	 * Which throwable to bind: - "self" (default): the Throwable on the holder - "cause": the Throwable's immediate
	 * cause
	 */
	String target() default "self";
}
