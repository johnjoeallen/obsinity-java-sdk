// src/test/java/com/obsinity/telemetry/aspect/EventScopeSelectionTest.java
package com.obsinity.telemetry.aspect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import com.obsinity.telemetry.annotations.EventReceiver;
import com.obsinity.telemetry.annotations.Flow;
import com.obsinity.telemetry.annotations.OnEventScope;
import com.obsinity.telemetry.annotations.OnFlowFailure;
import com.obsinity.telemetry.annotations.OnFlowLifecycle;
import com.obsinity.telemetry.annotations.OnFlowSuccess;
import com.obsinity.telemetry.dispatch.HandlerGroup;
import com.obsinity.telemetry.dispatch.HandlerScopeValidator;
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

/**
 * Trimmed to validate only the multi-prefix (OR) scope behavior: @OnEventScope("orders.") OR @OnEventScope("payments.")
 *
 * <p>All other scenarios (success/failure split, out-of-scope unmatched, etc.) are now covered by
 * TelemetryRulesCoverageTest.
 */
@TelemetryBootSuite(classes = EventScopeSelectionTest.Config.class)
class EventScopeSelectionTest {

	@Autowired
	Config.Flows flows;

	@Autowired
	OrdersOrPaymentsFinished ordersOrPaymentsFinished;

	@BeforeEach
	void reset() {
		ordersOrPaymentsFinished.reset();
	}

	@Test
	@DisplayName("Multiple prefixes are OR'ed (orders.* OR payments.*)")
	void multiple_prefixes() {
		flows.ordersCreateOk();
		flows.paymentsChargeOk();

		assertThat(ordersOrPaymentsFinished.successCalls).hasSize(2);
		assertThat(ordersOrPaymentsFinished.failureCalls).isEmpty();
	}

	/* =============================== Test wiring =============================== */

	@Configuration
	@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
	static class Config {
		@Bean
		com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
			return new com.fasterxml.jackson.databind.ObjectMapper();
		}

		@Bean
		HandlerScopeValidator handlerScopeValidator(ListableBeanFactory lbf) {
			return new HandlerScopeValidator(lbf);
		}

		@Bean
		TelemetryContext telemetryContext(TelemetryProcessorSupport support) {
			return new TelemetryContext(support);
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
		List<HandlerGroup> handlerGroups(ListableBeanFactory bf, TelemetryProcessorSupport support) {
			return new TelemetryEventHandlerScanner(bf, support).handlerGroups();
		}

		@Bean
		TelemetryDispatchBus telemetryDispatchBus(List<HandlerGroup> groups) {
			return new TelemetryDispatchBus(groups);
		}

		@Bean
		TelemetryAspect telemetryAspect(TelemetryProcessor p) {
			return new TelemetryAspect(p);
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

		/** Emits distinct flows for names (success paths only used here). */
		static class Flows {
			@Flow(name = "orders.create")
			public void ordersCreateOk() {
				/* success */
			}

			@Flow(name = "payments.charge")
			public void paymentsChargeOk() {
				/* success */
			}
		}

		@Bean(name = "EventScopeSelectionTest.flows")
		Flows flows() {
			return new Flows();
		}

		@Bean(name = "EventScopeSelectionTest.ordersOrPaymentsFinished")
		OrdersOrPaymentsFinished ordersOrPaymentsFinished() {
			return new OrdersOrPaymentsFinished();
		}
	}

	/* ============================= Scoped receiver ============================= */

	/** Two prefixes: orders.* OR payments.*; handle success for both names. */
	@EventReceiver
	@OnEventScope(prefix = "orders.") // repeatable — OR semantics with the next one
	@OnEventScope(prefix = "payments.")
	@OnFlowLifecycle(Lifecycle.FLOW_FINISHED)
	static class OrdersOrPaymentsFinished {
		final List<TelemetryHolder> successCalls = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> failureCalls = new CopyOnWriteArrayList<>();

		@OnFlowSuccess(name = "orders.create")
		public void ordersCreateOk(TelemetryHolder h) {
			successCalls.add(h);
		}

		@OnFlowSuccess(name = "payments.charge")
		public void paymentsChargeOk(TelemetryHolder h) {
			successCalls.add(h);
		}

		// Present for completeness; not used by the trimmed test but kept harmlessly
		@OnFlowFailure(name = "orders.create")
		public void ordersCreateFail(Throwable t, TelemetryHolder h) {
			failureCalls.add(h);
		}

		void reset() {
			successCalls.clear();
			failureCalls.clear();
		}
	}
}
