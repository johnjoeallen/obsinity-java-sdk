package com.obsinity.telemetry.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Consumer-side: bind a persisted attribute from the current event into a handler parameter.
 *
 * <p>Usage: @OnEvent(...) public void handle(@PullAttribute("order.id") String orderId) { ... } // or public void
 * handle(@PullAttribute(name = "order.id") String orderId) { ... }
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PullAttribute {
	/** Attribute key to read from the event's persisted attributes. */
	String name() default "";
}
