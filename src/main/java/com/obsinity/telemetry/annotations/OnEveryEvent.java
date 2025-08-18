package com.obsinity.telemetry.annotations;

import com.obsinity.telemetry.model.Lifecycle;
import io.opentelemetry.api.trace.SpanKind;

import java.lang.annotation.*;

/**
 * Additive "tap" that sees every event (subject to filters) regardless of whether any @OnEvent matched.
 * Never affects unmatched logic.
 *
 * Honors @EventScope on the component if present.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnEveryEvent {
	/** Lifecycles to include. Empty = any. */
	Lifecycle[] lifecycle() default {};

	/** Span kinds to include. Empty = any. Null kinds treated as INTERNAL. */
	SpanKind[] kinds() default {};

	/** When to run relative to exception presence. Default: both. */
	DispatchMode mode() default DispatchMode.COMBINED;
}
