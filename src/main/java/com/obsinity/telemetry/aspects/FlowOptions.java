package com.obsinity.telemetry.aspects;

import io.opentelemetry.api.trace.SpanKind;
import org.apache.logging.log4j.spi.StandardLevel;

/**
 * Options extracted from telemetry annotations present on the invoked method.
 * <ul>
 *   <li>For {@code @Flow}: {@code annotation=FLOW}, {@code name} populated, {@code autoFlowLevel=null}.</li>
 *   <li>For {@code @Step}: {@code annotation=STEP}, {@code name} populated, {@code autoFlowLevel}
 *       set if {@code @AutoFlow} is present on the same method (else {@code null}).</li>
 *   <li>For {@code @AutoFlow}-only scenarios (rare to advise directly): {@code annotation=AUTOFLOW},
 *       {@code autoFlowLevel} populated, {@code name=null}.</li>
 * </ul>
 */
public record FlowOptions(
	FlowType annotation,
	String name,                      // From @Flow/@Step; may be null for AUTOFLOW
	StandardLevel autoFlowLevel,       // From @AutoFlow; null if not present
	SpanKind spanKind                    // resolved from @Kind (method > class > INTERNAL)
) {
}
