package com.obsinity.telemetry.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.core.annotation.AliasFor;

/**
 * Component-level filter restricting which event names are visible to this receiver. Multiple scopes act as OR of
 * prefixes.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(OnEventScopes.class)
@Documented
public @interface OnEventScope {
	/** Alias of {@link #prefix()}. */
	@AliasFor("prefix")
	String value() default "";

	/** Alias of {@link #value()}. */
	@AliasFor("value")
	String prefix() default "";
}
