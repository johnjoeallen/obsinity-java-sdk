package com.obsinity.telemetry.annotations;

import io.opentelemetry.api.trace.SpanKind;
import java.lang.annotation.*;

/**
 * Declares the OpenTelemetry {@link SpanKind} for a class or method.
 * Precedence: method-level @Kind overrides class-level @Kind.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Kind {
	SpanKind value() default SpanKind.INTERNAL;
}
