package com.obsinity.telemetry.annotations;

/**
 * Dispatch mode for {@link OnEvent} handlers.
 *
 * <p>Controls when a handler is invoked relative to the outcome of the event:
 *
 * <ul>
 *   <li>{@link #COMBINED} — invoked for both <strong>success</strong> and <strong>failure</strong> outcomes. A handler
 *       in this mode may declare exactly one parameter of type {@link Throwable} (or a subtype) to receive the error
 *       when present. On success, the parameter is passed as {@code null}.
 *   <li>{@link #SUCCESS} — invoked only when the event completes <strong>without</strong> an exception. Handlers in
 *       this mode <em>must not</em> declare a {@code Throwable} parameter; startup validation will fail otherwise.
 *   <li>{@link #FAILURE} — invoked only when the event completes <strong>with</strong> an exception. A handler in this
 *       mode may declare exactly one parameter of type {@link Throwable} (or a subtype) to receive the thrown
 *       exception. If declared, the exception type must be assignable from the actual error, otherwise the handler is
 *       skipped.
 * </ul>
 *
 * <h3>Authoring rules (enforced at startup)</h3>
 *
 * <ul>
 *   <li>If a name has a {@code COMBINED} handler, it must not also have {@code SUCCESS} or {@code FAILURE} handlers.
 *   <li>If a name has a {@code SUCCESS} handler, it must also have a {@code FAILURE} handler for the same name (and
 *       vice versa).
 * </ul>
 *
 * <h3>Name matching</h3>
 *
 * <p>Handlers are registered under dot-separated names. Matching is done with a simple "dot-chop" algorithm: try the
 * full event name, then iteratively remove the last segment until a match is found. There are no wildcards or
 * empty-string catch-alls.
 */
public enum DispatchMode {
	/** Runs on both success and failure. May bind exactly one {@link Throwable} parameter. */
	COMBINED,

	/** Runs only when the event completes successfully. Must not bind a {@link Throwable}. */
	SUCCESS,

	/** Runs only when the event fails with an exception. May bind exactly one {@link Throwable} parameter. */
	FAILURE
}
