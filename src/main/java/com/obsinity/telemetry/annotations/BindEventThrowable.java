package com.obsinity.telemetry.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a {@link Throwable} from the {@code TelemetryHolder} into the parameter. If {@code required=true} and the
 * selected throwable source is missing, the binding fails.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface BindEventThrowable {
	/** If true and the selected throwable (per {@link #source()}) is missing, treat this as a binding error. */
	boolean required() default false;
	/**
	 * Which throwable to bind.
	 *
	 * <ul>
	 *   <li>{@link Source#SELF} — bind the throwable attached to the event/holder itself (default).
	 *   <li>{@link Source#CAUSE} — bind the immediate cause of the event throwable.
	 *   <li>{@link Source#ROOT_CAUSE} — bind the deepest (root) cause in the chain, if any.
	 * </ul>
	 */
	Source source() default Source.SELF;

	/** Source selector for which throwable to bind. */
	enum Source {
		SELF,
		CAUSE,
		ROOT_CAUSE
	}
}
