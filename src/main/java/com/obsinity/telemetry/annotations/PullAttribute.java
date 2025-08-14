package com.obsinity.telemetry.annotations;

import java.lang.annotation.*;

import org.springframework.core.annotation.AliasFor;

/**
 * Consumer-side: bind a persisted attribute from the current event into a handler parameter.
 *
 * Usage:
 *   @OnEvent(...)
 *   public void handle(@PullAttribute("order.id") String orderId) { ... }
 *   // or
 *   public void handle(@PullAttribute(name = "order.id") String orderId) { ... }
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PullAttribute {
	/**
	 * Attribute key to read from the event's persisted attributes.
	 */
	String name() default "";
}
