package com.obsinity.telemetry.annotations;

import java.lang.annotation.*;

/**
 * Marks a method parameter whose value should be recorded as a telemetry attribute.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Attribute {
	/**
	 * The attribute name to record on the TelemetryHolder.
	 */
	String name();
}
