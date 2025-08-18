package com.obsinity.telemetry.annotations;

import io.opentelemetry.api.trace.SpanKind;
import com.obsinity.telemetry.model.Lifecycle;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a handler method for telemetry events discovered by the Obsinity dispatcher.
 *
 * <h2>Name matching (dot-chop)</h2>
 *
 * Use {@link #name()} for a dot-separated event name. Matching is: try the full event name; if not present, chop the
 * last segment and try again. There are no wildcards and the empty string is never matched. If an exact tier has
 * eligible handlers, broader ancestors are not considered.
 *
 * <ul>
 *   <li><b>Exact:</b> {@code name="orders.checkout"}
 *   <li><b>Descendants:</b> the same handler also matches {@code orders.checkout.*} (e.g.,
 *       {@code orders.checkout.details}) via dot-chop.
 * </ul>
 *
 * <h2>Other filters</h2>
 *
 * <ul>
 *   <li>{@link #lifecycle()} – restricts accepted {@link Lifecycle} phases (empty = any).
 *   <li>{@link #kinds()} – restricts accepted {@link SpanKind}s (empty = any). Null kinds are treated as INTERNAL.
 *   <li>{@link #throwableTypes()} / {@link #includeSubclasses()} – optional exception-type filters for failure paths.
 *   <li>{@link #messageRegex()} – optional regex evaluated against {@code Throwable.getMessage()}.
 *   <li>{@link #causeType()} – optional fully-qualified class for {@code throwable.getCause()}.
 * </ul>
 *
 * <h2>Dispatch mode</h2>
 *
 * {@link #mode()} controls when the handler runs:
 *
 * <ul>
 *   <li>{@link DispatchMode#COMBINED}: runs on both success and failure; may declare exactly one
 *       {@code Throwable}-typed parameter (it will be {@code null} on success).
 *   <li>{@link DispatchMode#SUCCESS}: runs only when no exception is present; must not declare a {@code Throwable}
 *       parameter.
 *   <li>{@link DispatchMode#FAILURE}: runs only when an exception is present; may declare exactly one
 *       {@code Throwable}-typed parameter whose type must be assignable from the thrown exception.
 * </ul>
 *
 * <h2>Authoring rules (validated at startup)</h2>
 *
 * <ul>
 *   <li>For a given name, {@code COMBINED} cannot coexist with {@code SUCCESS} or {@code FAILURE}.
 *   <li>If a name has {@code SUCCESS}, it must also have {@code FAILURE} (and vice-versa).
 * </ul>
 *
 * <h2>Examples</h2>
 *
 * <pre>{@code
 * @OnEvent(name = "orders.checkout") // exact; also matches orders.checkout.*
 * void onEither(TelemetryHolder h) { ... }
 *
 * @OnEvent(name = "orders.checkout", mode = DispatchMode.SUCCESS)
 * void onOk(@PullAttribute("order.id") String id) { ... }
 *
 * @OnEvent(name = "orders.checkout", mode = DispatchMode.FAILURE)
 * void onFail(@BindEventException Throwable ex,
 *             @PullAttribute("order.id") String id) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnEvent {

	/** Exact event name used for dot-chop matching. Must match {@code ^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*$}. */
	String name();

	/** Lifecycle phases this handler accepts. Empty means "any". */
	Lifecycle[] lifecycle() default {};

	/** Dispatch mode; default is {@link DispatchMode#COMBINED}. */
	DispatchMode mode() default DispatchMode.COMBINED;

	/** Span kinds this handler accepts. Empty means "any". Null kinds treated as {@link SpanKind#INTERNAL}. */
	SpanKind[] kinds() default {};

	/** Throwable types to match (optional). Empty means "any type". */
	Class<? extends Throwable>[] throwableTypes() default {};

	/** If true (default), subclasses of {@link #throwableTypes()} also match. */
	boolean includeSubclasses() default true;

	/** Optional regex matched against {@link Throwable#getMessage()} when present. */
	String messageRegex() default "";

	/** Fully-qualified class name that {@code throwable.getCause()} must be an instance of (optional). */
	String causeType() default "";
}
