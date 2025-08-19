package com.obsinity.telemetry.annotations;

import com.obsinity.telemetry.model.Lifecycle;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Container for repeatable {@link OnEventLifecycle}. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnEventLifecycles {
	OnEventLifecycle[] value();
}
