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
import com.obsinity.telemetry.annotations.EventReceiver;
import com.obsinity.telemetry.annotations.Flow;
import com.obsinity.telemetry.annotations.GlobalFlowFallback;
import com.obsinity.telemetry.annotations.Kind;
import com.obsinity.telemetry.annotations.OnFlow;
import com.obsinity.telemetry.annotations.OnFlowCompleted;
import com.obsinity.telemetry.annotations.OnFlowNotMatched;
import com.obsinity.telemetry.annotations.OnFlowSuccess;

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
	properties = {"spring.main.web-application-type=none"}
)
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
		List<HandlerGroup> handlerGroups(ListableBeanFactory beanFactory, TelemetryProcessorSupport support) {
			return new TelemetryEventHandlerScanner(beanFactory, support).handlerGroups();
		}

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

		@Bean(name = "telemetryAspect_wildcardTest")
		TelemetryAspect telemetryAspect(TelemetryProcessor p) {
			return new TelemetryAspect(p);
		}

		@Bean ErrFlows flows(TelemetryContext ctx) { return new ErrFlows(ctx); }

		// Global fallback component in the new model
		@Bean UnmatchedReceiver unmatchedReceiver() { return new UnmatchedReceiver(); }

		@Bean NormalReceivers normalReceivers() { return new NormalReceivers(); }
	}

	/* ------------ App under test ------------ */

	@Kind(SpanKind.SERVER)
	static class ErrFlows {
		private final TelemetryContext telemetry;
		ErrFlows(TelemetryContext telemetry) { this.telemetry = telemetry; }

		@Flow(name = "err.alpha")
		public void alpha() { throw new IllegalArgumentException("alpha-fail"); }

		@Flow(name = "err.beta")
		public void beta() { throw new IllegalStateException("beta-fail"); }

		@Flow(name = "ok.gamma")
		public void gamma() { /* no-op */ }
	}

	/**
	 * Global fallbacks (new model):
	 * - Annotate the class with @GlobalFlowFallback.
	 * - Methods use @OnFlowNotMatched. No lifecycle element → use param.
	 */
	@GlobalFlowFallback
	static class UnmatchedReceiver {
		final List<TelemetryHolder> anyNotMatchedAllPhases = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> notMatchedAtFinish = new CopyOnWriteArrayList<>();
		final List<Throwable> anyFailures = new CopyOnWriteArrayList<>();

		// Fires on any unmatched (all lifecycles)
		@OnFlowNotMatched
		public void onAnyUnmatched(@BindEventThrowable Throwable ex, TelemetryHolder h) {
			anyFailures.add(ex); // null for success paths
			anyNotMatchedAllPhases.add(h);
		}

		// Filter by phase using Lifecycle param
		@OnFlowNotMatched
		public void onUnmatchedAtFinish(TelemetryHolder h, Lifecycle phase) {
			if (phase == Lifecycle.FLOW_FINISHED) {
				notMatchedAtFinish.add(h);
			}
		}
	}

	/**
	 * Named handler for ok.gamma (success at finish) so global fallback at finish
	 * does NOT fire for that flow.
	 */
	@EventReceiver
	static class NormalReceivers {
		final List<TelemetryHolder> normals = new CopyOnWriteArrayList<>();

		@OnFlowSuccess(name = "ok.gamma")
		public void onGamma(TelemetryHolder h) {
			normals.add(h);
		}

		@OnFlowCompleted(name = "ok.gamma")
		public void onGammaCompleted(TelemetryHolder h) {
			// no-op
		}
	}

	@Autowired ErrFlows flows;
	@Autowired UnmatchedReceiver unmatched;
	@Autowired NormalReceivers normals;

	@Test
	@DisplayName("Global fallback fires for unmatched error flows; finish fallback suppressed by named success")
	void globalFallbacks_workAsExpected_underNewAPI() {
		// err.alpha → exception path
		assertThatThrownBy(() -> flows.alpha())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("alpha-fail");

		// err.beta → exception path
		assertThatThrownBy(() -> flows.beta())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("beta-fail");

		// ok.gamma → normal path with a named SUCCESS handler
		flows.gamma();

		// Global unmatched at finish should capture only the error flows
		assertThat(unmatched.notMatchedAtFinish.stream().map(TelemetryHolder::name).toList())
			.containsExactlyInAnyOrder("err.alpha", "err.beta");

		// Named success handler observed ok.gamma
		assertThat(normals.normals.stream().map(TelemetryHolder::name).toList())
			.contains("ok.gamma");

		// The all-phases unmatched saw both error flows at least once
		assertThat(unmatched.anyNotMatchedAllPhases.stream().map(TelemetryHolder::name).toList())
			.contains("err.alpha", "err.beta");
	}
}
