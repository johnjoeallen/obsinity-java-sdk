package com.obsinity.telemetry.annotations;

import java.lang.annotation.*;

/** Marks a List<TelemetryHolder> parameter to receive a batch on ROOT_FLOW_FINISHED. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Batch { }
