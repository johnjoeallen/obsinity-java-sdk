// src/test/java/com/obsinity/telemetry/aspect/EventScopeSelectionTest.java
package com.obsinity.telemetry.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import com.obsinity.telemetry.annotations.EventReceiver;
import com.obsinity.telemetry.annotations.Flow;
import com.obsinity.telemetry.annotations.OnEventLifecycle;
import com.obsinity.telemetry.annotations.OnEventScope;
import com.obsinity.telemetry.annotations.OnFlowCompleted;
import com.obsinity.telemetry.annotations.OnFlowFailure;
import com.obsinity.telemetry.annotations.OnFlowNotMatched;
import com.obsinity.telemetry.annotations.OnFlowSuccess;
import com.obsinity.telemetry.annotations.OnOutcome;
import com.obsinity.telemetry.annotations.Outcome;

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

@SpringBootTest(classes = EventScopeSelectionTest.Config.class)
class EventScopeSelectionTest {

	@Autowired
	Config.Flows flows;

	@Autowired
	@Qualifier("EventScopeSelectionTest.ordersOnlyFinished") OrdersOnlyFinished ordersOnlyFinished;

	@Autowired
	@Qualifier("EventScopeSelectionTest.ordersOrPaymentsFinished") OrdersOrPaymentsFinished ordersOrPaymentsFinished;

	@Autowired
	@Qualifier("EventScopeSelectionTest.componentScopedUnmatched") ComponentScopedUnmatched componentScopedUnmatched;

	@Autowired
	@Qualifier("EventScopeSelectionTest.unscopedControl") UnscopedControl unscopedControl;

	@BeforeEach
	void reset() {
		ordersOnlyFinished.reset();
		ordersOrPaymentsFinished.reset();
		componentScopedUnmatched.reset();
		unscopedControl.reset();
	}

	@Test
	@DisplayName("@OnEventScope(prefix='orders.') → only orders.* seen")
	void prefix_orders_only() {
		flows.ordersCreateOk();     // in-scope
		flows.paymentsChargeOk();   // out-of-scope

		assertThat(ordersOnlyFinished.completedCalls).hasSize(1);
		assertThat(ordersOnlyFinished.failureCalls).isEmpty();

		// Control sees both successes
		assertThat(unscopedControl.successCalls).hasSize(2);
	}

	@Test
	@DisplayName("Multiple prefixes are OR'ed (orders.* OR payments.*)")
	void multiple_prefixes() {
		flows.ordersCreateOk();
		flows.paymentsChargeOk();

		assertThat(ordersOrPaymentsFinished.successCalls).hasSize(2);
		assertThat(ordersOrPaymentsFinished.failureCalls).isEmpty();
	}

	@Test
	@DisplayName("Success vs failure handlers behave as expected")
	void success_vs_failure() {
		flows.ordersCreateOk();
		assertThatThrownBy(flows::ordersCreateFail)
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("create-fail");

		// Scoped component sees success via @OnFlowCompleted(SUCCESS) and failure via @OnFlowFailure
		assertThat(ordersOnlyFinished.completedCalls).hasSize(1); // was 2; now SUCCESS-only to avoid slot overlap
		assertThat(ordersOnlyFinished.failureCalls).hasSize(1);

		// Control sees both success and failure
		assertThat(unscopedControl.successCalls).hasSize(1);
		assertThat(unscopedControl.failureCalls).hasSize(1);
	}

	@Test
	@DisplayName("Out-of-scope events are invisible (even to component unmatched)")
	void out_of_scope_is_invisible() {
		// Component is scoped to orders.*, we emit payments.*
		flows.paymentsChargeOk();

		// Component-scoped unmatched should NOT fire (event never enters the component)
		assertThat(componentScopedUnmatched.componentUnmatched).isEmpty();

		// Control proves the event existed
		assertThat(unscopedControl.successCalls).hasSize(1);
	}

	/* =============================== Test wiring =============================== */

	@Configuration
	@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
	static class Config {
		@Bean com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
			return new com.fasterxml.jackson.databind.ObjectMapper();
		}

		@Bean HandlerScopeValidator handlerScopeValidator(ListableBeanFactory lbf) {
			return new HandlerScopeValidator(lbf);
		}

		@Bean TelemetryContext telemetryContext(TelemetryProcessorSupport support) {
			return new TelemetryContext(support);
		}

		@Bean AttributeParamExtractor attributeParamExtractor() { return new AttributeParamExtractor(); }

		@Bean TelemetryAttributeBinder telemetryAttributeBinder(AttributeParamExtractor ex) {
			return new TelemetryAttributeBinder(ex);
		}

		@Bean TelemetryProcessorSupport telemetryProcessorSupport() { return new TelemetryProcessorSupport(); }

		@Bean List<HandlerGroup> handlerGroups(ListableBeanFactory bf, TelemetryProcessorSupport support) {
			return new TelemetryEventHandlerScanner(bf, support).handlerGroups();
		}

		@Bean TelemetryDispatchBus telemetryDispatchBus(List<HandlerGroup> groups) {
			return new TelemetryDispatchBus(groups);
		}

		@Bean TelemetryAspect telemetryAspect(TelemetryProcessor p) { return new TelemetryAspect(p); }

		@Bean TelemetryProcessor telemetryProcessor(
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

		@Bean(name = "EventScopeSelectionTest.flows")
		Flows flows() { return new Flows(); }

		@Bean(name = "EventScopeSelectionTest.ordersOnlyFinished")
		OrdersOnlyFinished ordersOnlyFinished() { return new OrdersOnlyFinished(); }

		@Bean(name = "EventScopeSelectionTest.ordersOrPaymentsFinished")
		OrdersOrPaymentsFinished ordersOrPaymentsFinished() { return new OrdersOrPaymentsFinished(); }

		@Bean(name = "EventScopeSelectionTest.componentScopedUnmatched")
		ComponentScopedUnmatched componentScopedUnmatched() { return new ComponentScopedUnmatched(); }

		@Bean(name = "EventScopeSelectionTest.unscopedControl")
		UnscopedControl unscopedControl() { return new UnscopedControl(); }

		/** Emits distinct flows for names and success/failure paths. */
		static class Flows {
			@Flow(name = "orders.create")
			public void ordersCreateOk() { /* success */ }

			@Flow(name = "orders.create")
			public void ordersCreateFail() { throw new IllegalArgumentException("create-fail"); }

			@Flow(name = "payments.charge")
			public void paymentsChargeOk() { /* success */ }
		}
	}

	/* ============================= Scoped receivers ============================= */

	/** Prefix-only scope to orders.*, lifecycle pinned to FLOW_FINISHED. */
	@EventReceiver
	@OnEventScope("orders.")
	@OnEventLifecycle(Lifecycle.FLOW_FINISHED)
	static class OrdersOnlyFinished {
		final List<TelemetryHolder> completedCalls = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> failureCalls = new CopyOnWriteArrayList<>();

		// Runs on SUCCESS completion for orders.create (narrowed to avoid overlap with @OnFlowFailure)
		@OnFlowCompleted(name = "orders.create")
		@OnOutcome(Outcome.SUCCESS)
		public void completed(TelemetryHolder h) {
			completedCalls.add(h);
		}

		// Runs only on failure for orders.create
		@OnFlowFailure(name = "orders.create")
		public void failure(Throwable t, TelemetryHolder h) {
			failureCalls.add(h);
		}

		void reset() {
			completedCalls.clear();
			failureCalls.clear();
		}
	}

	/** Two prefixes: orders.* OR payments.*; provide a handler for each name. */
	@EventReceiver
	@OnEventScope(prefix = "orders.")  // repeatable handled by adding another annotation
	@OnEventScope(prefix = "payments.")
	@OnEventLifecycle(Lifecycle.FLOW_FINISHED)
	static class OrdersOrPaymentsFinished {
		final List<TelemetryHolder> successCalls = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> failureCalls = new CopyOnWriteArrayList<>();

		@OnFlowSuccess(name = "orders.create")
		public void ordersCreateOk(TelemetryHolder h) { successCalls.add(h); }

		@OnFlowSuccess(name = "payments.charge")
		public void paymentsChargeOk(TelemetryHolder h) { successCalls.add(h); }

		@OnFlowFailure(name = "orders.create")
		public void ordersCreateFail(Throwable t, TelemetryHolder h) { failureCalls.add(h); }

		void reset() {
			successCalls.clear();
			failureCalls.clear();
		}
	}

	/**
	 * Proves that out-of-scope events are invisible: even a component-scoped unmatched handler
	 * does not fire when the event lies outside the component's @OnEventScope.
	 */
	@EventReceiver
	@OnEventScope("orders.")
	@OnEventLifecycle(Lifecycle.FLOW_FINISHED)
	static class ComponentScopedUnmatched {
		final List<TelemetryHolder> componentUnmatched = new CopyOnWriteArrayList<>();

		// Component-scoped fallback in the new API
		@OnFlowNotMatched
		public void onUnmatchedAtComponent(TelemetryHolder holder) {
			componentUnmatched.add(holder);
		}

		void reset() { componentUnmatched.clear(); }
	}

	/** Control group: no scope → sees everything. */
	@EventReceiver
	@OnEventLifecycle(Lifecycle.FLOW_FINISHED)
	static class UnscopedControl {
		final List<TelemetryHolder> successCalls = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> failureCalls = new CopyOnWriteArrayList<>();

		@OnFlowSuccess(name = "orders.create")
		public void sOrdersCreate(TelemetryHolder h) { successCalls.add(h); }

		@OnFlowSuccess(name = "payments.charge")
		public void sPaymentsCharge(TelemetryHolder h) { successCalls.add(h); }

		@OnFlowFailure(name = "orders.create")
		public void fOrdersCreate(Throwable t, TelemetryHolder h) { failureCalls.add(h); }

		void reset() {
			successCalls.clear();
			failureCalls.clear();
		}
	}
}
