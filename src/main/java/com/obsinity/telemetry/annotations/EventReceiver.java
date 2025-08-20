package com.obsinity.telemetry.annotations;

import java.lang.annotation.*;

/**
 * Marks a bean as an event receiver (flow-centric handler container).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EventReceiver { }
