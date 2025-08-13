package com.obsinity.telemetry.annotations;

import java.lang.annotation.*;

/** Declares attributes that MUST be present before a handler is invoked. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireAttrs {
	String[] value();
}
