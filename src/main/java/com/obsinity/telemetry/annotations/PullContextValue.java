package com.obsinity.telemetry.annotations;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

/**
 * Consumer-side: bind a value from the current event's ephemeral context into a handler parameter.
 *
 * <p>Usage:</p>
 * <pre>
 *   @OnEvent(name = "step.finished")
 *   public void onStep(@PullContextValue("request.id") String requestId) {
 *       // ...
 *   }
 *
 *   // or explicit:
 *   public void onStep(@PullContextValue(name = "request.id") String requestId) { ... }
 * </pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PullContextValue {

	/**
	 * Context key to read (shorthand).
	 */
	@AliasFor("name")
	String value() default "";

	/**
	 * Same as {@link #value()} — provided for explicitness.
	 */
	@AliasFor("value")
	String name() default "";
}
