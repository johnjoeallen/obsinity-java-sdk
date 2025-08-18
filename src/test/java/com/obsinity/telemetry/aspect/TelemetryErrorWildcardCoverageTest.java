package com.obsinity.telemetry.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import io.opentelemetry.api.trace.SpanKind;
import com.obsinity.telemetry.annotations.BindEventThrowable;
import com.obsinity.telemetry.annotations.DispatchMode;
import com.obsinity.telemetry.annotations.Flow;
import com.obsinity.telemetry.annotations.Kind;
import com.obsinity.telemetry.annotations.OnEvent;
import com.obsinity.telemetry.annotations.OnUnMatchedEvent;
import com.obsinity.telemetry.annotations.TelemetryEventHandler;
import com.obsinity.telemetry.dispatch.HandlerGroup;
import com.obsinity.telemetry.dispatch.TelemetryEventHandlerScanner;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.OAttributes;
import com.obsinity.telemetry.model.TelemetryHolder;
import com.obsinity.telemetry.processor.AttributeParamExtractor;
import com.obsinity.telemetry.processor.TelemetryAttributeBinder;
import com.obsinity.telemetry.processor.TelemetryContext;
import com.obsinity.telemetry.processor.TelemetryProcessor;
import com.obsinity.telemetry.processor.TelemetryProcessorSupport;
import com.obsinity.telemetry.receivers.TelemetryDispatchBus;

@SpringBootTest(
		classes = TelemetryErrorWildcardCoverageTest.CoverageConfig.class,
		webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = {"spring.main.web-application-type=none"})
class TelemetryErrorWildcardCoverageTest {

	@Configuration(proxyBeanMethods = false)
	@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
	static class CoverageConfig {

		@Bean
		com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
			return new com.fasterxml.jackson.databind.ObjectMapper();
		}

		@Bean
		AttributeParamExtractor attributeParamExtractor() {
			return new AttributeParamExtractor();
		}

		@Bean
		TelemetryAttributeBinder telemetryAttributeBinder(AttributeParamExtractor ex) {
			return new TelemetryAttributeBinder(ex);
		}

		@Bean
		TelemetryProcessorSupport telemetryProcessorSupport() {
			return new TelemetryProcessorSupport();
		}

		@Bean
		List<HandlerGroup> handlerGroups(ListableBeanFactory beanFactory,
										 TelemetryProcessorSupport support) {
			return new TelemetryEventHandlerScanner(beanFactory, support).handlerGroups();
		}

		// Bus now only needs the groups
		@Bean
		TelemetryDispatchBus telemetryDispatchBus(List<HandlerGroup> groups) {
			return new TelemetryDispatchBus(groups);
		}

		@Bean
		TelemetryContext telemetryContext(TelemetryProcessorSupport support) {
			return new TelemetryContext(support);
		}

		@Bean
		TelemetryProcessor telemetryProcessor(
				TelemetryAttributeBinder binder, TelemetryProcessorSupport support, TelemetryDispatchBus bus) {
			return new TelemetryProcessor(binder, support, bus) {
				@Override
				protected OAttributes buildAttributes(org.aspectj.lang.ProceedingJoinPoint pjp, FlowOptions opts) {
					return new OAttributes(Map.of(
							"test.flow", opts.name(),
							"declaring.class", pjp.getSignature().getDeclaringTypeName(),
							"declaring.method", pjp.getSignature().getName()));
				}
			};
		}

		// Unique bean name to avoid collisions in other test contexts
		@Bean(name = "telemetryAspect_wildcardTest")
		TelemetryAspect telemetryAspect(TelemetryProcessor p) {
			return new TelemetryAspect(p);
		}

		@Bean
		ErrFlows flows(TelemetryContext ctx) {
			return new ErrFlows(ctx);
		}

		@Bean
		UnmatchedReceiver unmatchedReceiver() {
			return new UnmatchedReceiver();
		}

		@Bean
		NormalReceivers normalReceivers() {
			return new NormalReceivers();
		}
	}

	/* ------------ App under test ------------ */

	@Kind(SpanKind.SERVER)
	static class ErrFlows {
		private final TelemetryContext telemetry;

		ErrFlows(TelemetryContext telemetry) {
			this.telemetry = telemetry;
		}

		@Flow(name = "err.alpha")
		public void alpha() {
			throw new IllegalArgumentException("alpha-fail");
		}

		@Flow(name = "err.beta")
		public void beta() {
			throw new IllegalStateException("beta-fail");
		}

		@Flow(name = "ok.gamma")
		public void gamma() {
			/* no-op */
		}
	}

	/**
	 * Global fallbacks: run when no named handlers matched across any component. We capture three kinds: - FAILURE
	 * (with Throwable) - COMBINED at FLOW_FINISHED - SUCCESS at FLOW_FINISHED (should NOT fire when a named success
	 * handler exists)
	 */
	@TelemetryEventHandler
	static class UnmatchedReceiver {
		final List<TelemetryHolder> failureCalls = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> failureCallsAllLifeCycles = new CopyOnWriteArrayList<>();
		final List<Throwable> failures = new CopyOnWriteArrayList<>();
		final List<Throwable> failuresAllLifeCycles = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> combinedFinishCalls = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> successFinishCalls = new CopyOnWriteArrayList<>();

		@OnUnMatchedEvent(scope = OnUnMatchedEvent.Scope.GLOBAL, mode = DispatchMode.FAILURE)
		public void onAnyFailure(@BindEventThrowable Throwable ex, TelemetryHolder holder) {
			failuresAllLifeCycles.add(ex);
			failureCallsAllLifeCycles.add(holder);
		}

		@OnUnMatchedEvent(
				scope = OnUnMatchedEvent.Scope.GLOBAL,
				lifecycle = {Lifecycle.FLOW_FINISHED},
				mode = DispatchMode.FAILURE)
		public void onAnyFlowFinishedFailure(@BindEventThrowable Throwable ex, TelemetryHolder holder) {
			failures.add(ex);
			failureCalls.add(holder);
		}

		@OnUnMatchedEvent(
				scope = OnUnMatchedEvent.Scope.GLOBAL,
				lifecycle = {Lifecycle.FLOW_FINISHED},
				mode = DispatchMode.COMBINED)
		public void onAnyFinishCombined(TelemetryHolder holder) {
			combinedFinishCalls.add(holder);
		}

		@OnUnMatchedEvent(
				scope = OnUnMatchedEvent.Scope.GLOBAL,
				lifecycle = {Lifecycle.FLOW_FINISHED},
				mode = DispatchMode.SUCCESS)
		public void onAnyFinishSuccess(TelemetryHolder holder) {
			successFinishCalls.add(holder);
		}
	}

	/**
	 * Named (non-fallback) handlers. Only a SUCCESS handler for ok.gamma to prove: - failure fallbacks still fire for
	 * err.* (no eligible named failure) - combined fallback at finish fires for err.* but not for ok.gamma
	 */
	@TelemetryEventHandler
	static class NormalReceivers {
		final List<TelemetryHolder> normals = new CopyOnWriteArrayList<>();

		@OnEvent(
				name = "ok.gamma",
				lifecycle = {Lifecycle.FLOW_FINISHED},
				mode = DispatchMode.SUCCESS)
		public void onGamma(TelemetryHolder h) {
			normals.add(h);
		}
	}

	@Autowired
	ErrFlows flows;

	@Autowired
	UnmatchedReceiver unmatched;

	@Test
	@DisplayName(
			"Global fallbacks: FAILURE fires for err.*, COMBINED finish fires for err.* only, SUCCESS finish suppressed by named")
	void globalFallbacks_workAsExpected() {
		// err.alpha → exception path
		assertThatThrownBy(() -> flows.alpha())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("alpha-fail");

		// err.beta → exception path
		assertThatThrownBy(() -> flows.beta())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("beta-fail");

		// ok.gamma → normal path with a named SUCCESS handler at FLOW_FINISHED
		flows.gamma();

		// FAILURE global unmatched called exactly twice (alpha, beta)
		assertThat(unmatched.failureCallsAllLifeCycles).hasSize(4);
		assertThat(unmatched.failureCalls).hasSize(2);
		assertThat(unmatched.failuresAllLifeCycles).hasSize(4);
		assertThat(unmatched.failures).hasSize(2);

		// COMBINED global unmatched at finish: fires for error flows (2), NOT for ok.gamma (named handler exists)
		assertThat(unmatched.combinedFinishCalls.stream()
						.map(TelemetryHolder::name)
						.toList())
				.containsExactlyInAnyOrder("err.alpha", "err.beta");

		// SUCCESS global unmatched at finish should be suppressed because ok.gamma had a named SUCCESS handler
		assertThat(unmatched.successFinishCalls).isEmpty();
	}
}
