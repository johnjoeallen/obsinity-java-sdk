package com.obsinity.telemetry.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.core.annotation.AliasFor;

import io.opentelemetry.api.trace.SpanKind;
import com.obsinity.telemetry.model.Lifecycle;

/**
 * Declares a component-level filter (scope) for which events are even considered by this handler component.
 *
 * <h2>Semantics</h2>
 *
 * <ul>
 *   <li>A component annotated with {@code @EventScope} will only see events that match <b>all</b> of the configured
 *       criteria (prefix AND lifecycle AND kind AND error mode).
 *   <li>Events outside this scope are invisible to the component: its {@link OnEvent} methods will not match, and its
 *       {@link OnUnMatchedEvent}(COMPONENT) or {@link OnEveryEvent} methods will not fire.
 * </ul>
 *
 * <h3>Name prefixes</h3>
 *
 * <ul>
 *   <li>Each prefix is tested with {@code event.name().startsWith(prefix)}.
 *   <li>If no prefixes are supplied, prefix filtering is not applied ("any").
 *   <li>{@code value()} is an alias for {@code prefixes()} for brevity.
 * </ul>
 *
 * <h3>Lifecycles & kinds</h3>
 *
 * <ul>
 *   <li>Empty arrays mean "any".
 * </ul>
 *
 * <h3>Error mode</h3>
 *
 * <ul>
 *   <li>{@link ErrorMode#ANY}: ignore errors vs non-errors.
 *   <li>{@link ErrorMode#ONLY_ERROR}: only events that have a Throwable.
 *   <li>{@link ErrorMode#ONLY_NON_ERROR}: only events with no Throwable.
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EventScope {

	/** Alias for {@link #prefixes()} so you can write: {@code @EventScope("order.")}. */
	@AliasFor("prefixes")
	String[] value() default {};

	/** One or more name prefixes. An event is in scope if its name starts with any prefix. */
	@AliasFor("value")
	String[] prefixes() default {};

	/** Lifecycles to include. Empty = any. */
	Lifecycle[] lifecycles() default {};

	/** Span kinds to include. Empty = any. */
	SpanKind[] kinds() default {};

	/** Error-mode filter (tri-state). */
	ErrorMode errorMode() default ErrorMode.ANY;

	enum ErrorMode {
		ANY,
		ONLY_ERROR,
		ONLY_NON_ERROR
	}
}
