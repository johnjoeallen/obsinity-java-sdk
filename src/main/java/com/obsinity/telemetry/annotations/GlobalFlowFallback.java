package com.obsinity.telemetry.annotations;

import java.lang.annotation.*;

/**
 * Marks a dedicated receiver that gets called only when NO receiver in the application
 * matched a flow. Methods should be annotated with {@link OnFlowNotMatched}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface GlobalFlowFallback { }
