package com.obsinity.telemetry.annotations;

import com.obsinity.telemetry.model.Lifecycle;

/**
 * Fallback handlers when no @OnEvent matched.
 *
 * scope:
 *  - COMPONENT: runs when this component had no @OnEvent match for the in-scope event.
 *  - GLOBAL: runs only if no @OnEvent matched in any component.
 *
 * lifecycle: empty = any
 * mode: SUCCESS / FAILURE / COMBINED
 */
@java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@java.lang.annotation.Documented
public @interface OnUnMatchedEvent {
	enum Scope { COMPONENT, GLOBAL }

	Scope scope() default Scope.COMPONENT;

	/** Lifecycle phases this handler accepts. Empty means "any". */
	Lifecycle[] lifecycle() default {};

	/** Dispatch mode; default is COMBINED. */
	DispatchMode mode() default DispatchMode.COMBINED;
}
