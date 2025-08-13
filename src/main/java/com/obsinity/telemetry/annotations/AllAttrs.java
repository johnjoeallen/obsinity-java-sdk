package com.obsinity.telemetry.annotations;

import java.lang.annotation.*;

/** Binds a parameter to the full immutable attribute map. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AllAttrs { }
