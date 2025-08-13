package com.obsinity.telemetry.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a {@code Step}, representing a named unit of work within a {@link Flow}.
 *
 * <p>Steps are used to segment and observe key execution points inside a flow. Each Step emits a structured telemetry
 * signal, which may appear as a span event or a standalone event depending on the exporter configuration (e.g.,
 * OpenTelemetry, native).
 *
 * <p>This annotation is intended for use on methods only. When invoked within an active {@code @Flow}, the Step is
 * tracked as part of that flow. If no Flow is active, and no {@link AutoFlow} annotation is present, the runtime
 * behaves as if {@code @AutoFlow(level = ERROR)} were declared: a Flow will be auto-started and the incident logged at
 * error level.
 *
 * <h4>Usage example:</h4>
 *
 * <pre>{@code
 * @Step(name = "authorizePayment")
 * public void authorize() {
 *     ...
 * }
 * }</pre>
 *
 * @see Flow
 * @see AutoFlow
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Step {

	/**
	 * A descriptive name for the step, used in telemetry systems and logs. This name should remain stable across
	 * versions for consistent analytics.
	 *
	 * @return the step name
	 */
	String name();
}
