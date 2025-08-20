package com.obsinity.telemetry.annotations;

import org.springframework.core.annotation.AliasFor;
import java.lang.annotation.*;

/**
 * Component-level filter restricting which event names are visible to this receiver.
 * Multiple scopes act as OR of prefixes.
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
