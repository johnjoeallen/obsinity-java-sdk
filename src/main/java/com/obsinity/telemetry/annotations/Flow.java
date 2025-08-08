package com.obsinity.telemetry.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a {@code Flow}, representing a root execution context for telemetry tracking.
 * <p>
 * A Flow marks the beginning of a traceable operation. All {@link Step} annotations
 * encountered during its execution will inherit the same correlation and trace identifiers.
 * <p>
 * This annotation is intended for use on methods only. It should be applied to
 * top-level service or controller methods that initiate a business process,
 * request flow, or any standalone unit of work to be traced.
 *
 * <h4>Usage example:</h4>
 * <pre>{@code
 * @Flow(name = "checkout")
 * public void beginCheckout() {
 *     ...
 * }
 * }</pre>
 *
 * @see Step
 * @see AutoFlow
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Flow {

	/**
	 * A descriptive name for the flow, used in telemetry systems and logs.
	 * This name should be unique within the context of the application and
	 * remain stable for analytics and observability purposes.
	 *
	 * @return the flow name
	 */
	String name();
}
