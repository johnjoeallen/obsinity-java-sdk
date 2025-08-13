package com.obsinity.telemetry.annotations;

import java.lang.annotation.*;

/** Bind a method parameter to an attribute key (with optional 'required'). */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Attr {
	String value();
	boolean required() default true;
}
