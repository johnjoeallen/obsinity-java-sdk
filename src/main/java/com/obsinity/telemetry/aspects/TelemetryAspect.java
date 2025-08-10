package com.obsinity.telemetry.aspects;

import com.obsinity.telemetry.annotations.Flow;
import com.obsinity.telemetry.annotations.Step;
import com.obsinity.telemetry.processors.TelemetryProcessor;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * # TelemetryAspect
 * <p>
 * Spring AOP aspect that intercepts methods annotated with {@link Flow} and {@link Step}
 * and delegates to {@link TelemetryProcessor} to record application-specific telemetry
 * for Obsinity.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Match and wrap calls to methods annotated with {@code @Flow} or {@code @Step}.</li>
 *   <li>Build a {@link FlowOptions} from the current {@link ProceedingJoinPoint} via
 *       {@link FlowOptionsFactory} (this inspects {@code @Flow}, {@code @Step},
 *       optional {@code @AutoFlow}, and {@code @Kind} if present).</li>
 *   <li>Delegate to {@link TelemetryProcessor#proceed(ProceedingJoinPoint, FlowOptions)}
 *       which creates/links spans (flows), manages per-thread context, and notifies receivers.</li>
 * </ul>
 *
 * <p>Notes:</p>
 * <ul>
 *   <li>This aspect is deliberately stateless and thread-safe; all state is managed inside
 *       {@link TelemetryProcessor} using thread-local context.</li>
 *   <li>Pointcuts use {@code execution(* *(..)) && @annotation(X)} so that any method
 *       (any name, any arguments) with the target annotation is matched and the annotation
 *       instance is bound into the advice method.</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @Component
 * class OrderService {
 *   @Flow(name = "placeOrder")
 *   public OrderId placeOrder(Cart cart) {
 *     validate();
 *     charge();
 *     return persist();
 *   }
 *
 *   @Step(name = "validate")
 *   void validate() { ... }
 * }
 * }</pre>
 *
 * @see Flow
 * @see Step
 * @see FlowOptions
 * @see FlowOptionsFactory
 * @see TelemetryProcessor
 * @since 0.1.0
 */
@Aspect
@RequiredArgsConstructor
@Component
public class TelemetryAspect {

	/**
	 * Strategy that performs all telemetry handling (creation, nesting, notifications).
	 */
	private final TelemetryProcessor telemetryProcessor;

	/**
	 * Around advice for methods annotated with {@link Flow}.
	 *
	 * <p>Pointcut explanation:
	 * {@code execution(* *(..))} matches any method execution (any name/args/return),
	 * and {@code @annotation(flow)} restricts matches to methods annotated with {@link Flow},
	 * binding the concrete annotation instance to the {@code flow} parameter.</p>
	 *
	 * <p>The advice constructs a {@link FlowOptions} from the join point (which determines
	 * whether this is a root Flow or a promoted Step) and delegates to the
	 * {@link TelemetryProcessor}.</p>
	 *
	 * @param joinPoint the current method invocation context (target, args, signature)
	 * @param flow      the {@link Flow} annotation present on the method being invoked
	 * @return the original method's return value
	 * @throws Throwable any exception thrown by the original method; rethrown unchanged
	 */
	@Around(value = "execution(* *(..)) && @annotation(flow)", argNames = "joinPoint,flow")
	public Object interceptFlow(ProceedingJoinPoint joinPoint, Flow flow) throws Throwable { // NOSONAR
		FlowOptions opts = FlowOptionsFactory.fromJoinPoint(joinPoint);
		return telemetryProcessor.proceed(joinPoint, opts);
	}

	/**
	 * Around advice for methods annotated with {@link Step}.
	 *
	 * <p>Pointcut explanation:
	 * {@code execution(* *(..))} matches any method execution; {@code @annotation(step)}
	 * limits matches to methods annotated with {@link Step}, binding the annotation
	 * instance to {@code step}.</p>
	 *
	 * <p>The advice builds a {@link FlowOptions} from the join point and delegates to
	 * {@link TelemetryProcessor}. If no Flow is active when this Step is invoked,
	 * the processor may promote the Step to a Flow depending on configuration
	 * (e.g., presence/absence of {@code @AutoFlow}).</p>
	 *
	 * @param joinPoint the current method invocation context (target, args, signature)
	 * @param step      the {@link Step} annotation present on the method being invoked
	 * @return the original method's return value
	 * @throws Throwable any exception thrown by the original method; rethrown unchanged
	 */
	@Around(value = "execution(* *(..)) && @annotation(step)", argNames = "joinPoint,step")
	public Object interceptStep(ProceedingJoinPoint joinPoint, Step step) throws Throwable { // NOSONAR
		FlowOptions opts = FlowOptionsFactory.fromJoinPoint(joinPoint);
		return telemetryProcessor.proceed(joinPoint, opts);
	}
}
