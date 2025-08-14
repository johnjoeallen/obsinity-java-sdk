package com.obsinity.telemetry.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Require one or more EventContext keys to be present before invoking the handler.
 *
 * <p>If any required key is absent (or null), the dispatcher should skip/flag the invocation consistent with
 * existing @RequireAttrs behavior.
 *
 * <p>Example:
 *
 * <pre>
 *   @OnEvent
 *   @RequireEventContext({"tenant","correlationId"})
 *   public void handle(...) { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiredEventContext {
	String[] name();
}
