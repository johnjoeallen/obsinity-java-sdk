package com.obsinity.telemetry.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Producer-side: write a method parameter value into the event's ephemeral context. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PushContextValue {
	/** Context key to write. */
	String value();

	/** If true (default), null values are not written. */
	boolean omitIfNull() default true;
}
