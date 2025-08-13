package com.obsinity.telemetry.annotations;

import java.lang.annotation.*;

/** Bind a Throwable (or its cause) to a method parameter. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Err {
	boolean required() default false;
	/** "self" (default) or "cause" */
	String target() default "self";
}
