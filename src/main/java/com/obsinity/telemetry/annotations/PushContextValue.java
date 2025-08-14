package com.obsinity.telemetry.annotations;

import java.lang.annotation.*;

/**
 * Producer-side: write a method parameter value into the event's ephemeral context.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PushContextValue {
	/**
	 * Context key to write.
	 */
	String value();

	/**
	 * If true (default), null values are not written.
	 */
	boolean omitIfNull() default true;
}
