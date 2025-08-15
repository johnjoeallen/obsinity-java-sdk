package com.obsinity.telemetry.annotations;

/**
 * Declares the dispatch mode for an {@link OnEvent} handler.
 * <p>
 * The dispatch mode controls <b>when</b> the handler is invoked in relation
 * to the presence of an exception in the {@code TelemetryHolder}, and
 * whether it is allowed (or required) to bind that exception via
 * {@link BindEventException}.
 *
 * <h2>Modes</h2>
 * <dl>
 *   <dt>{@link #NORMAL}</dt>
 *   <dd>
 *     Invoked only when there is <b>no</b> exception present for the event.
 *     <ul>
 *       <li>{@link BindEventException} parameters are <b>not allowed</b>.</li>
 *       <li>Use for standard "happy-path" processing.</li>
 *     </ul>
 *   </dd>
 *
 *   <dt>{@link #ERROR}</dt>
 *   <dd>
 *     Invoked only when there <b>is</b> an exception present for the event.
 *     <ul>
 *       <li>Exactly <b>one</b> {@link BindEventException} parameter is <b>required</b>.</li>
 *       <li>The bound parameter type must be {@link Throwable} or a subclass.</li>
 *       <li>Use for exception-specific handling and recovery logic.</li>
 *     </ul>
 *   </dd>
 *
 *   <dt>{@link #ALWAYS}</dt>
 *   <dd>
 *     Invoked regardless of whether an exception is present.
 *     <ul>
 *       <li>{@link BindEventException} is <b>optional</b>.</li>
 *       <li>If present, at most one {@link BindEventException} parameter is allowed,
 *           and it must be a {@link Throwable} type.</li>
 *       <li>Use for cleanup, auditing, or metrics logic that must always run.</li>
 *     </ul>
 *   </dd>
 * </dl>
 *
 * <h2>Validation Rules</h2>
 * The scanner will enforce the following at startup:
 * <ul>
 *   <li><b>NORMAL</b>: no {@link BindEventException} parameters permitted.</li>
 *   <li><b>ERROR</b>: exactly one {@link BindEventException} parameter required.</li>
 *   <li><b>ALWAYS</b>: at most one {@link BindEventException} parameter allowed.</li>
 * </ul>
 *
 * @see OnEvent
 * @see BindEventException
 */
public enum DispatchMode {

	/**
	 * Runs only when there is <b>no</b> exception present.
	 * No {@link BindEventException} parameter allowed.
	 */
	NORMAL,

	/**
	 * Runs only when there <b>is</b> an exception present.
	 * Exactly one {@link BindEventException} parameter is required.
	 */
	ERROR,

	/**
	 * Runs regardless of exception presence.
	 * {@link BindEventException} parameter is optional; if present, at most one.
	 */
	ALWAYS
}
