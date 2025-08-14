package com.obsinity.telemetry.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.opentelemetry.api.trace.SpanKind;
import com.obsinity.telemetry.model.Lifecycle;
import org.springframework.core.annotation.AliasFor;

/**
 * Declares a handler method for telemetry events discovered by the Obsinity dispatcher.
 *
 * <h2>Name matching</h2>
 * <ul>
 *   <li><b>Exact name:</b> set {@link #name()} (or {@link #value()}), e.g. {@code name="auth.login"}.</li>
 *   <li><b>Prefix match:</b> set {@link #namePrefix()}, e.g. {@code namePrefix="db.query."} to match
 *       {@code db.query.select}, {@code db.query.insert}, etc.</li>
 *   <li>If both are provided, <b>exact</b> takes precedence over <b>prefix</b>.</li>
 *   <li>{@link #nameRegex()} is deprecated and ignored by new scanners.</li>
 * </ul>
 *
 * <h2>Other filters</h2>
 * <ul>
 *   <li>{@link #lifecycle()} – restricts which {@link Lifecycle} phases the handler accepts (empty = any).</li>
 *   <li>{@link #kinds()} – restricts accepted {@link SpanKind}s (empty = any). Null span kinds are treated as INTERNAL.</li>
 *   <li>{@link #requireThrowable()} / {@link #throwableTypes()} / {@link #includeSubclasses()} – control invocation
 *       when a {@code Throwable} is present on the event.</li>
 *   <li>{@link #messageRegex()} – optional regex matched against {@code Throwable.getMessage()} (if present).</li>
 *   <li>{@link #causeType()} – optional fully-qualified class name; the throwable's {@code getCause()} must
 *       be an instance of this type for the handler to run.</li>
 * </ul>
 *
 * <h2>Examples</h2>
 * <pre>{@code
 * @OnEvent("orders.create") // exact name (via value() alias)
 * public void onCreate(TelemetryHolder h) { ... }
 *
 * @OnEvent(namePrefix = "http.server.")
 * public void onHttp(TelemetryHolder h) { ... }
 *
 * @OnEvent(
 *   name = "db.error",
 *   lifecycle = {Lifecycle.FLOW_FINISHED},
 *   kinds = {SpanKind.CLIENT},
 *   requireThrowable = true,
 *   throwableTypes = {java.sql.SQLException.class},
 *   includeSubclasses = true,
 *   messageRegex = "timeout|deadlock",
 *   causeType = "java.net.SocketTimeoutException"
 * )
 * public void onDbError(@PullAttribute("db.instance") String db) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnEvent {

	/**
	 * Exact event name (alias of {@link #name()}).
	 * <p>Use this for concise declarations: {@code @OnEvent("orders.create")}</p>
	 */
	@AliasFor("name")
	String value() default "";

	/**
	 * Exact event name.
	 * <p>If both {@code name} and {@link #namePrefix()} are provided, {@code name} wins.</p>
	 */
	@AliasFor("value")
	String name() default "";

	/**
	 * Event name <b>prefix</b>.
	 * <p>Matches any event whose name starts with this prefix. Ignored if {@link #name()} is non-empty.</p>
	 */
	String namePrefix() default "";

	/**
	 * <b>Deprecated:</b> regex name matching is no longer used.
	 * <p>Kept for binary compatibility with older code. New scanners ignore this value.</p>
	 */
	@Deprecated
	String nameRegex() default "";

	/**
	 * Lifecycle phases this handler accepts. Empty means "any".
	 */
	Lifecycle[] lifecycle() default {};

	/**
	 * Span kinds this handler accepts. Empty means "any".
	 * <p>Null kinds are treated as {@link SpanKind#INTERNAL} by the dispatcher.</p>
	 */
	SpanKind[] kinds() default {};

	/**
	 * If true, a {@link Throwable} must be present on the event for the handler to be invoked.
	 */
	boolean requireThrowable() default false;

	/**
	 * Throwable types to match. Empty means "any type".
	 * <p>Combined with {@link #includeSubclasses()} to control exact vs. instanceof matching.</p>
	 */
	Class<? extends Throwable>[] throwableTypes() default {};

	/**
	 * If true (default), subclasses of {@link #throwableTypes()} also match; if false, requires exact class match.
	 */
	boolean includeSubclasses() default true;

	/**
	 * Optional <b>regex</b> applied to {@link Throwable#getMessage()} (when a throwable is present).
	 * <p>If provided, the message must contain a match for the handler to run.</p>
	 */
	String messageRegex() default "";

	/**
	 * Fully-qualified class name for the expected {@code getCause()} type.
	 * <p>Leave empty to ignore cause-type matching.</p>
	 */
	String causeType() default "";
}
