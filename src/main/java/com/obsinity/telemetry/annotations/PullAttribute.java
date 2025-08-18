package com.obsinity.telemetry.annotations;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Consumer-side: bind a saved (persisted) attribute from the current event into a handler parameter.
 *
 * <p>Usage:
 *
 * <pre>
 *   @OnEvent(name = "order.created")
 *   public void handle(@PullAttribute("order.id") String orderId) {
 *       // ...
 *   }
 *
 *   // or explicit:
 *   public void handle(@PullAttribute(name = "order.id") String orderId) { ... }
 * </pre>
 *
 * <p>Notes:
 *
 * <ul>
 *   <li>Supports {@link AliasFor} so {@code value()} and {@code name()} are interchangeable.
 *   <li>Extraction is performed by the parameter binder.
 * </ul>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PullAttribute {

	/** Attribute key to read from the event's saved attributes (shorthand). */
	@AliasFor("name")
	String value() default "";

	/** Same as {@link #value()} — provided for explicitness. */
	@AliasFor("value")
	String name() default "";
}
