package com.obsinity.telemetry.aspect;

import io.opentelemetry.api.trace.SpanKind;
import com.obsinity.telemetry.annotations.OrphanAlert;

/**
 * Options extracted from telemetry annotations present on the invoked method.
 *
 * <ul>
 *   <li>For {@code @Flow}: {@code annotation=FLOW}, {@code name} populated, {@code orphanAlertLevel=null}.
 *   <li>For {@code @Step}: {@code annotation=STEP}, {@code name} populated, {@code orphanAlertLevel} set if
 *       {@code @OrphanAlert} present (else {@code null}).
 * </ul>
 */
public record FlowOptions(
		FlowType annotation,
		String name, // From @Flow/@Step
		OrphanAlert.Level orphanAlertLevel, // From @OrphanAlert on @Step (null if absent / not a step)
		SpanKind spanKind // Resolved from @Kind (method > class > INTERNAL)
		) {
	/** @return true if the intercepted method is annotated with @Flow. */
	public boolean isFlowMethod() {
		return annotation == FlowType.FLOW;
	}

	/** @return true if the intercepted method is annotated with @Step. */
	public boolean isStepMethod() {
		return annotation == FlowType.STEP;
	}
}
