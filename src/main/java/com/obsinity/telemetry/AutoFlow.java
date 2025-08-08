package com.obsinity.telemetry;

import org.apache.logging.log4j.spi.StandardLevel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker with log level. Absence is equivalent present with level ERROR.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AutoFlow {
	StandardLevel level() default StandardLevel.ERROR; // INFO | WARN | ERROR, etc.
}
