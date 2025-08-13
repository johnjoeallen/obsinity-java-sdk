package com.obsinity.telemetry.annotations;

import com.obsinity.telemetry.model.Lifecycle;
import io.opentelemetry.api.trace.SpanKind;

import java.lang.annotation.*;

/**
 * Declares a handler method for telemetry events.
 * You can match by exact name or regex (choose one), lifecycle(s), span kind(s),
 * and optional throwable filters.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnEvent {

	/** Exact event name. Use empty when providing nameRegex. */
	String name() default "";

	/** Regex for event name (java.util.regex). Use empty when providing exact name. */
	String nameRegex() default "";

	/** Lifecycle phases this handler accepts. Empty means “any”. */
	Lifecycle[] lifecycle() default {};

	/** Span kinds this handler accepts. Empty means “any”. */
	SpanKind[] kinds() default {};

	/** Require a Throwable to be present to invoke this handler. */
	boolean requireThrowable() default false;

	/** Throwable types to match against. Empty means “any type”. */
	Class<? extends Throwable>[] throwableTypes() default {};

	/** If true, subclasses of the throwableTypes also match (default true). */
	boolean includeSubclasses() default true;

	/** Optional regex to match Throwable.getMessage(). */
	String messageRegex() default "";

	/**
	 * Fully-qualified class name for the expected cause type.
	 * Leave empty to ignore cause type matching.
	 */
	String causeType() default "";
}
