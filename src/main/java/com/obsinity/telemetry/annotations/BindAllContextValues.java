package com.obsinity.telemetry.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Inject the entire (read-only) EventContext map into a handler parameter.
 *
 * <p>The parameter type should be {@code Map<String, Object>} (or a compatible supertype). The injected map should be
 * an unmodifiable view.
 *
 * <p>Example:
 *
 * <pre>
 *   @OnEvent
 *   public void audit(@AllEventContext Map&lt;String,Object&gt; ctx) { ... }
 * </pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface BindAllContextValues {}
