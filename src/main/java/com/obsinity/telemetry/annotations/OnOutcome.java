package com.obsinity.telemetry.annotations;

import java.lang.annotation.*;

/**
 * Optional qualifier for {@link OnFlowCompleted} to restrict by outcome.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(OnOutcomes.class)
@Documented
public @interface OnOutcome {
	Outcome value();
}
