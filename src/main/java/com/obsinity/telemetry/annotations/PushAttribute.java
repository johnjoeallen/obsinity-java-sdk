package com.obsinity.telemetry.annotations;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

/**
 * Producer-side: write a method parameter value into the event's persisted attributes.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PushAttribute {
	/**
	 */
	String name() default "";

	/**
	 * If true (default), null values are not written.
	 */
	boolean omitIfNull() default true;
}
