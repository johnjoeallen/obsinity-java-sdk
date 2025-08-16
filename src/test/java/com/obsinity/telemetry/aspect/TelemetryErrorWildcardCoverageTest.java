package com.obsinity.telemetry.aspect;

import com.obsinity.telemetry.annotations.*;
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
import io.opentelemetry.api.trace.SpanKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
	classes = TelemetryErrorWildcardCoverageTest.CoverageConfig.class,
	webEnvironment = SpringBootTest.WebEnvironment.NONE,
	properties = {
		"spring.main.web-application-type=none"
	}
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
		AttributeParamExtractor attributeParamExtractor() { return new AttributeParamExtractor(); }

		@Bean
		TelemetryAttributeBinder telemetryAttributeBinder(AttributeParamExtractor ex) {
			return new TelemetryAttributeBinder(ex);
		}

		@Bean
		TelemetryProcessorSupport telemetryProcessorSupport() { return new TelemetryProcessorSupport(); }

		@Bean
		TelemetryEventHandlerScanner telemetryEventHandlerScanner() { return new TelemetryEventHandlerScanner(); }

		@Bean
		TelemetryDispatchBus telemetryDispatchBus(ListableBeanFactory bf, TelemetryEventHandlerScanner sc) {
			return new TelemetryDispatchBus(bf, sc);
		}

		// ✅ Missing bean added: ErrFlows requires a TelemetryContext
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
						"declaring.method", pjp.getSignature().getName()
					));
				}
			};
		}

		// Unique bean name to avoid collisions in other test contexts
		@Bean(name = "telemetryAspect_wildcardTest")
		TelemetryAspect telemetryAspect(TelemetryProcessor p) { return new TelemetryAspect(p); }

		@Bean ErrFlows flows(TelemetryContext ctx) { return new ErrFlows(ctx); }

		@Bean WildcardReceiver wildcardReceiver() { return new WildcardReceiver(); }

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

	@TelemetryEventHandler
	static class WildcardReceiver {
		final List<TelemetryHolder> errorCalls  = new CopyOnWriteArrayList<>();
		final List<Throwable>       errors      = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> alwaysCalls = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> normalCalls = new CopyOnWriteArrayList<>();

		// BLANK wildcard ERROR — must catch ANY exception from ANY event
		@OnEvent(mode = DispatchMode.ERROR)
		public void onAnyError(@BindEventThrowable Exception ex, TelemetryHolder holder) {
			errorCalls.add(holder);
			errors.add(ex);
		}

		// BLANK ALWAYS — runs in both normal & error paths
		@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED}, mode = DispatchMode.ALWAYS)
		public void onAnyFinishAlways(TelemetryHolder holder) {
			alwaysCalls.add(holder);
		}

		// BLANK NORMAL — must be suppressed on errors, but run on ok flow
		@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED}) // default mode = NORMAL
		public void onAnyFinishNormal(TelemetryHolder holder) {
			normalCalls.add(holder);
		}
	}

	@TelemetryEventHandler
	static class NormalReceivers {
		final List<TelemetryHolder> normals = new CopyOnWriteArrayList<>();

		@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})
		public void onUnhandled(TelemetryHolder h) { normals.add(h); }

		@OnEvent(name = "err.alpha", lifecycle = {Lifecycle.FLOW_FINISHED})
		public void onAlpha(TelemetryHolder h) { normals.add(h); }

		@OnEvent(name = "err.beta", lifecycle = {Lifecycle.FLOW_FINISHED})
		public void onBeta(TelemetryHolder h) { normals.add(h); }

		@OnEvent(name = "ok.gamma", lifecycle = {Lifecycle.FLOW_FINISHED})
		public void onGamma(TelemetryHolder h) { normals.add(h); }
	}

	@Autowired ErrFlows flows;
	@Autowired WildcardReceiver wildcard;

	@Test
	@DisplayName("Blank wildcard ERROR catches all exceptions; NORMAL suppressed; ALWAYS runs")
	void blankWildcardCatchesAllExceptions_andNormalSuppressed() {
		// err.alpha → exception path
		assertThatThrownBy(() -> flows.alpha())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("alpha-fail");

		// err.beta → exception path
		assertThatThrownBy(() -> flows.beta())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("beta-fail");

		// ok.gamma → normal path
		flows.gamma();

		// ERROR wildcard called exactly twice (alpha, beta)
		assertThat(wildcard.errorCalls).hasSize(2);
		assertThat(wildcard.errors).hasSize(2);

		// NORMAL must be suppressed on error flows, but run once for ok.gamma
		assertThat(wildcard.normalCalls.stream().map(TelemetryHolder::name).toList())
			.containsExactly("ok.gamma");

		// ALWAYS runs for all three finished events
		assertThat(wildcard.alwaysCalls).hasSize(3);
	}
}
