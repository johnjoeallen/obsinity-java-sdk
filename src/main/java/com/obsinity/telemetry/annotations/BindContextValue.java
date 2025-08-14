package com.obsinity.telemetry.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bind a handler parameter to a value in the Obsinity EventContext.
 *
 * <p>Resolution rule:
 *
 * <ul>
 *   <li>If a step (event) is active, the value is read from the step-scoped EventContext.
 *   <li>Otherwise, the value is read from the flow-scoped EventContext on the TelemetryHolder.
 * </ul>
 *
 * Values are NOT serialized/exported; EventContext is ephemeral and handler-only.
 *
 * <p>Example:
 *
 * <pre>
 *   @OnEvent
 *   public void handle(@EventContext("tenant") String tenant) { ... }
 * </pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface BindContextValue {
	/** The key to look up in the EventContext. */
	String name();

	/** If true and the key is missing/unconvertible, the dispatcher should skip/flag the handler. */
	boolean required() default false;
}
