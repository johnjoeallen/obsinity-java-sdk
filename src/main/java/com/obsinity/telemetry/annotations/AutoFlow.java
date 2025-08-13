package com.obsinity.telemetry.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.apache.logging.log4j.spi.StandardLevel;

/**
 * Indicates that a {@link Step} may auto-start a {@link Flow} if none is active.
 *
 * <p>This annotation allows a Step to be executed independently, automatically creating a Flow with the same name as
 * the Step if no Flow is already active. The provided {@link StandardLevel} controls the severity level at which this
 * behavior is logged.
 *
 * <p>If this annotation is absent on a Step that is executed outside of a Flow, the runtime behaves as if
 * {@code @AutoFlow(level = StandardLevel.ERROR)} were present by default.
 *
 * <h4>Usage example:</h4>
 *
 * <pre>{@code
 * @AutoFlow(level = StandardLevel.WARN)
 * @Step(name = "healthcheck")
 * public void heartbeat() {
 *     ...
 * }
 * }</pre>
 *
 * @see Flow
 * @see Step
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AutoFlow {

	/**
	 * The severity level to use when logging that a Step was auto-promoted to a Flow due to the absence of an active
	 * Flow context.
	 *
	 * @return the log level at which the promotion will be recorded
	 */
	StandardLevel level() default StandardLevel.ERROR;
}
