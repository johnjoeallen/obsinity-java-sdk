package com.obsinity.telemetry.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controls the log level used when a {@link Step} is invoked without an active {@link Flow} and is therefore
 * auto-promoted to a Flow.
 *
 * <p>Apply to a Step method to override the default promotion log level.
 *
 * <pre>
 *   @Step(name = "checkout.validate")
 *   @PromotionAlert(level = PromotionAlert.Level.ERROR)
 *   public void validate(...) { ... }
 * </pre>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OrphanAlert {

	/** The logging level for the promotion alert. */
	Level level() default Level.ERROR;

	/** Simple log level enum decoupled from specific logging frameworks. */
	enum Level {
		NONE,
		ERROR,
		WARN,
		INFO,
		DEBUG
	}
}
