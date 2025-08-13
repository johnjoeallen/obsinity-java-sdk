package com.obsinity.telemetry.annotations;

import java.lang.annotation.*;

/**
 * Marker for beans that contain @OnEvent handler methods.
 * Only classes annotated with this will be scanned and registered.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TelemetryEventHandler { }
