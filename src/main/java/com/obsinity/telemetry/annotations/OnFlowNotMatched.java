package com.obsinity.telemetry.annotations;

import java.lang.annotation.*;

/**
 * Method-level fallback invoked when NO @OnFlow*, or @OnFlowCompleted methods in the
 * same receiver matched the emitted flow.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnFlowNotMatched { }
