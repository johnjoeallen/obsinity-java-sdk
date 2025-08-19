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

import io.opentelemetry.api.trace.SpanKind;
import com.obsinity.telemetry.annotations.BindEventThrowable;
import com.obsinity.telemetry.annotations.DispatchMode;
import com.obsinity.telemetry.annotations.EventScope;
import com.obsinity.telemetry.annotations.Flow;
import com.obsinity.telemetry.annotations.Kind;
import com.obsinity.telemetry.annotations.OnEvent;
import com.obsinity.telemetry.annotations.OnUnMatchedEvent;
import com.obsinity.telemetry.annotations.TelemetryEventHandler;
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
 * Validates component-level filtering via @EventScope. Notes: - @OnEvent requires a 'name' parameter -> each handler
 * method binds to a specific event name. - Throwable binding uses @BindEventThrowable.
 */
@SpringBootTest(classes = EventScopeSelectionTest.Config.class)
class EventScopeSelectionTest {

	@Autowired
	Config.Flows flows;

	@Autowired
	@Qualifier("EventScopeSelectionTest.ordersOnlyFinished") OrdersOnlyFinished ordersOnlyFinished;

	@Autowired
	OrdersOrPaymentsFinished ordersOrPaymentsFinished;

	@Autowired
	ErrorsOnlyFinished errorsOnlyFinished;

	@Autowired
	NonErrorsOnlyFinished nonErrorsOnlyFinished;

	@Autowired
	ClientKindOnly clientKindOnly;

	@Autowired
	ComponentScopedUnmatched componentScopedUnmatched;

	@Autowired
	UnscopedControl unscopedControl;

	@BeforeEach
	void reset() {
		ordersOnlyFinished.reset();
		ordersOrPaymentsFinished.reset();
		errorsOnlyFinished.reset();
		nonErrorsOnlyFinished.reset();
		clientKindOnly.reset();
		componentScopedUnmatched.reset();
		unscopedControl.reset();
	}

	@Test
	@DisplayName("@EventScope(prefix='orders.') → only orders.* seen")
	void prefix_orders_only() {
		flows.ordersCreateOk(); // in-scope
		flows.paymentsChargeOk(); // out-of-scope

		assertThat(ordersOnlyFinished.successCalls).hasSize(1);
		assertThat(ordersOnlyFinished.failureCalls).isEmpty();

		// Control sees both
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
	@DisplayName("ErrorMode tri-state: ONLY_ERROR vs ONLY_NON_ERROR")
	void error_mode_only_error_vs_non_error() {
		flows.ordersCreateOk();
		assertThatThrownBy(flows::ordersCreateFail)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("create-fail");

		assertThat(errorsOnlyFinished.failureCalls).hasSize(1);
		assertThat(errorsOnlyFinished.successCalls).isEmpty();

		assertThat(nonErrorsOnlyFinished.successCalls).hasSize(1);
		assertThat(nonErrorsOnlyFinished.failureCalls).isEmpty();
	}

	@Test
	@DisplayName("Kind filter: kinds={CLIENT} allows only CLIENT spans")
	void kind_filter_client_only() {
		flows.ordersQueryClient(); // CLIENT
		flows.ordersQueryServer(); // SERVER

		// Component has two handlers (client/server). EventScope should allow only the CLIENT one.
		assertThat(clientKindOnly.calls).hasSize(1);
		assertThat(clientKindOnly.calls.get(0).kind()).isEqualTo(SpanKind.CLIENT);

		// Control still sees both successes.
		assertThat(unscopedControl.successCalls).hasSize(2);
	}

	@Test
	@DisplayName("Out-of-scope events are invisible (even to COMPONENT unmatched)")
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
		@Bean
		com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
			return new com.fasterxml.jackson.databind.ObjectMapper();
		}

		@Bean
		HandlerScopeValidator handlerScopeValidator(ListableBeanFactory listableBeanFactory) {
			return new HandlerScopeValidator(listableBeanFactory);
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
		TelemetryAttributeBinder telemetryAttributeBinder(AttributeParamExtractor extractor) {
			return new TelemetryAttributeBinder(extractor);
		}

		@Bean
		TelemetryProcessorSupport telemetryProcessorSupport() {
			return new TelemetryProcessorSupport();
		}

		@Bean
		List<HandlerGroup> handlerGroups(ListableBeanFactory beanFactory, TelemetryProcessorSupport support) {
			return new TelemetryEventHandlerScanner(beanFactory, support).handlerGroups();
		}

		/** Build the dispatch bus that routes to handlers. */
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

		@Bean(name = "EventScopeSelectionTest.flows")
		Flows flows() {
			return new Flows();
		}

		@Bean(name = "EventScopeSelectionTest.ordersOnlyFinished")
		OrdersOnlyFinished ordersOnlyFinished() {
			return new OrdersOnlyFinished();
		}

		@Bean(name = "EventScopeSelectionTest.ordersOrPaymentsFinished")
		OrdersOrPaymentsFinished ordersOrPaymentsFinished() {
			return new OrdersOrPaymentsFinished();
		}

		@Bean(name = "EventScopeSelectionTest.errorsOnlyFinished")
		ErrorsOnlyFinished errorsOnlyFinished() {
			return new ErrorsOnlyFinished();
		}

		@Bean(name = "EventScopeSelectionTest.nonErrorsOnlyFinished")
		NonErrorsOnlyFinished nonErrorsOnlyFinished() {
			return new NonErrorsOnlyFinished();
		}

		@Bean(name = "EventScopeSelectionTest.clientKindOnly")
		ClientKindOnly clientKindOnly() {
			return new ClientKindOnly();
		}

		@Bean(name = "EventScopeSelectionTest.componentScopedUnmatched")
		ComponentScopedUnmatched componentScopedUnmatched() {
			return new ComponentScopedUnmatched();
		}

		@Bean(name = "EventScopeSelectionTest.unscopedControl")
		UnscopedControl unscopedControl() {
			return new UnscopedControl();
		}

		/** Emits distinct flows for names, kinds, and success/failure paths. */
		@TelemetryEventHandler
		static class Flows {

			@Flow(name = "orders.create")
			@Kind(SpanKind.INTERNAL)
			public void ordersCreateOk() {
				/* success */
			}

			@Flow(name = "orders.create")
			@Kind(SpanKind.INTERNAL)
			public void ordersCreateFail() {
				throw new IllegalArgumentException("create-fail");
			}

			@Flow(name = "payments.charge")
			@Kind(SpanKind.INTERNAL)
			public void paymentsChargeOk() {
				/* success */
			}

			@Flow(name = "orders.query.client")
			@Kind(SpanKind.CLIENT)
			public void ordersQueryClient() {
				/* success */
			}

			@Flow(name = "orders.query.server")
			@Kind(SpanKind.SERVER)
			public void ordersQueryServer() {
				/* success */
			}
		}
	}

	/* ============================= Scoped handlers ============================= */

	/** Prefix-only scope to orders.*, lifecycle pinned to FLOW_FINISHED (common terminal). */
	@TelemetryEventHandler
	@EventScope(
			value = "orders.",
			lifecycles = {Lifecycle.FLOW_FINISHED})
	static class OrdersOnlyFinished {
		final List<TelemetryHolder> successCalls = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> failureCalls = new CopyOnWriteArrayList<>();

		@OnEvent(name = "orders.create", mode = DispatchMode.COMBINED)
		public void success(TelemetryHolder h) {
			successCalls.add(h);
		}

		@OnEvent(name = "orders.create", mode = DispatchMode.FAILURE)
		public void failure(@BindEventThrowable Throwable t, TelemetryHolder h) {
			failureCalls.add(h);
		}

		void reset() {
			successCalls.clear();
			failureCalls.clear();
		}
	}

	/** Two prefixes: orders.* OR payments.*; provide a handler for each name. */
	@TelemetryEventHandler
	@EventScope(
			prefixes = {"orders.", "payments."},
			lifecycles = {Lifecycle.FLOW_FINISHED})
	static class OrdersOrPaymentsFinished {
		final List<TelemetryHolder> successCalls = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> failureCalls = new CopyOnWriteArrayList<>();

		@OnEvent(name = "orders.create", mode = DispatchMode.SUCCESS)
		public void ordersCreateOk(TelemetryHolder h) {
			successCalls.add(h);
		}

		@OnEvent(name = "payments.charge", mode = DispatchMode.SUCCESS)
		public void paymentsChargeOk(TelemetryHolder h) {
			successCalls.add(h);
		}

		void reset() {
			successCalls.clear();
			failureCalls.clear();
		}
	}

	/** ErrorMode=ONLY_ERROR within orders.* at FLOW_FINISHED. */
	@TelemetryEventHandler
	@EventScope(
			value = "orders.",
			lifecycles = {Lifecycle.FLOW_FINISHED},
			errorMode = EventScope.ErrorMode.ONLY_ERROR)
	static class ErrorsOnlyFinished {
		final List<TelemetryHolder> successCalls = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> failureCalls = new CopyOnWriteArrayList<>();

		@OnEvent(name = "orders.create", mode = DispatchMode.SUCCESS)
		public void success(TelemetryHolder h) {
			successCalls.add(h);
		}

		@OnEvent(name = "orders.create", mode = DispatchMode.FAILURE)
		public void failure(@BindEventThrowable Throwable t, TelemetryHolder h) {
			failureCalls.add(h);
		}

		void reset() {
			successCalls.clear();
			failureCalls.clear();
		}
	}

	/** ErrorMode=ONLY_NON_ERROR complement. */
	@TelemetryEventHandler
	@EventScope(
			value = "orders.",
			lifecycles = {Lifecycle.FLOW_FINISHED},
			errorMode = EventScope.ErrorMode.ONLY_NON_ERROR)
	static class NonErrorsOnlyFinished {
		final List<TelemetryHolder> successCalls = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> failureCalls = new CopyOnWriteArrayList<>();

		@OnEvent(name = "orders.create", mode = DispatchMode.SUCCESS)
		public void success(TelemetryHolder h) {
			successCalls.add(h);
		}

		@OnEvent(name = "orders.create", mode = DispatchMode.FAILURE)
		public void failure(@BindEventThrowable Throwable t, TelemetryHolder h) {
			failureCalls.add(h);
		}

		void reset() {
			successCalls.clear();
			failureCalls.clear();
		}
	}

	/**
	 * Kind filter: only CLIENT spans under orders.* at FLOW_FINISHED. We deliberately register handlers for both
	 * names; @EventScope(kinds={CLIENT}) must prevent the SERVER one from seeing events.
	 */
	@TelemetryEventHandler
	@EventScope(
			value = "orders.",
			lifecycles = {Lifecycle.FLOW_FINISHED},
			kinds = {SpanKind.CLIENT})
	static class ClientKindOnly {
		final List<TelemetryHolder> calls = new CopyOnWriteArrayList<>();

		@OnEvent(name = "orders.query.client", mode = DispatchMode.SUCCESS)
		public void clientOk(TelemetryHolder h) {
			calls.add(h);
		}

		@OnEvent(name = "orders.query.server", mode = DispatchMode.SUCCESS)
		public void serverOk(TelemetryHolder h) {
			calls.add(h);
		} // should be blocked by EventScope

		void reset() {
			calls.clear();
		}
	}

	/**
	 * Proves that out-of-scope events are invisible: even a COMPONENT-scoped unmatched handler does not fire when the
	 * event lies outside the component's @EventScope.
	 */
	@TelemetryEventHandler
	@EventScope(
			value = "orders.",
			lifecycles = {Lifecycle.FLOW_FINISHED})
	static class ComponentScopedUnmatched {
		final List<TelemetryHolder> componentUnmatched = new CopyOnWriteArrayList<>();

		@OnUnMatchedEvent(scope = OnUnMatchedEvent.Scope.COMPONENT, mode = DispatchMode.COMBINED)
		public void onUnmatchedAtComponent(TelemetryHolder holder) {
			componentUnmatched.add(holder);
		}

		void reset() {
			componentUnmatched.clear();
		}
	}

	/** Control group: no EventScope → sees everything. */
	@TelemetryEventHandler
	static class UnscopedControl {
		final List<TelemetryHolder> successCalls = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> failureCalls = new CopyOnWriteArrayList<>();

		@OnEvent(
				name = "orders.create",
				lifecycle = {Lifecycle.FLOW_FINISHED},
				mode = DispatchMode.SUCCESS)
		public void sOrdersCreate(TelemetryHolder h) {
			successCalls.add(h);
		}

		@OnEvent(
				name = "payments.charge",
				lifecycle = {Lifecycle.FLOW_FINISHED},
				mode = DispatchMode.SUCCESS)
		public void sPaymentsCharge(TelemetryHolder h) {
			successCalls.add(h);
		}

		@OnEvent(
				name = "orders.create",
				lifecycle = {Lifecycle.FLOW_FINISHED},
				mode = DispatchMode.FAILURE)
		public void fOrdersCreate(@BindEventThrowable Throwable t, TelemetryHolder h) {
			failureCalls.add(h);
		}

		@OnEvent(
				name = "orders.query.client",
				lifecycle = {Lifecycle.FLOW_FINISHED},
				mode = DispatchMode.SUCCESS)
		public void sOrdersQueryClient(TelemetryHolder h) {
			successCalls.add(h);
		}

		@OnEvent(
				name = "orders.query.server",
				lifecycle = {Lifecycle.FLOW_FINISHED},
				mode = DispatchMode.SUCCESS)
		public void sOrdersQueryServer(TelemetryHolder h) {
			successCalls.add(h);
		}

		void reset() {
			successCalls.clear();
			failureCalls.clear();
		}
	}
}
