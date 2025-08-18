package com.obsinity.telemetry.annotations;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Producer-side: write a method parameter value into the event's saved attributes.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * @Flow(name = "paramFlowExample")
 * public void paramFlowExample(
 *     @PushAttribute("user.id") String userId,               // shorthand
 *     @PushAttribute(name = "flags") Map<String, Object> f)  // explicit
 * { // no-op }
 * }</pre>
 *
 * <p>Notes:
 *
 * <ul>
 *   <li>{@code value()} and {@code name()} are interchangeable via {@link AliasFor}.
 *   <li>If {@code omitIfNull=true} (default), {@code null} values are not written.
 * </ul>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PushAttribute {

	/** Attribute key to save under (shorthand). */
	@AliasFor("name")
	String value() default "";

	/** Same as {@link #value()} — provided for explicitness. */
	@AliasFor("value")
	String name() default "";

	/** If true (default), null values are not written. */
	boolean omitIfNull() default true;
}
