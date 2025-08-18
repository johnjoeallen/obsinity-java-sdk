package com.obsinity.telemetry.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.obsinity.telemetry.annotations.BindEventThrowable;
import com.obsinity.telemetry.annotations.DispatchMode;
import com.obsinity.telemetry.annotations.EventScope;
import com.obsinity.telemetry.annotations.Flow;
import com.obsinity.telemetry.annotations.Kind;
import com.obsinity.telemetry.annotations.OnEvent;
import com.obsinity.telemetry.annotations.OnUnMatchedEvent;
import com.obsinity.telemetry.annotations.TelemetryEventHandler;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;
import io.opentelemetry.api.trace.SpanKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Validates component-level filtering via @EventScope.
 * Notes:
 *  - @OnEvent requires a 'name' parameter -> each handler method binds to a specific event name.
 *  - Throwable binding uses @BindEventThrowable.
 */
@SpringBootTest(classes = EventScopeSelectionTest.Config.class)
class EventScopeSelectionTest {

	@org.springframework.beans.factory.annotation.Autowired
	@Qualifier("EventScopeSelectionTest.flows")
	Config.Flows flows;

	@org.springframework.beans.factory.annotation.Autowired OrdersOnlyFinished ordersOnlyFinished;
	@org.springframework.beans.factory.annotation.Autowired OrdersOrPaymentsFinished ordersOrPaymentsFinished;
	@org.springframework.beans.factory.annotation.Autowired ErrorsOnlyFinished errorsOnlyFinished;
	@org.springframework.beans.factory.annotation.Autowired NonErrorsOnlyFinished nonErrorsOnlyFinished;
	@org.springframework.beans.factory.annotation.Autowired ClientKindOnly clientKindOnly;
	@org.springframework.beans.factory.annotation.Autowired ComponentScopedUnmatched componentScopedUnmatched;
	@org.springframework.beans.factory.annotation.Autowired UnscopedControl unscopedControl;

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
	@Disabled("EventScope not working yet")
	@DisplayName("@EventScope(prefix='orders.') → only orders.* seen")
	void prefix_orders_only() {
		flows.ordersCreateOk();     // in-scope
		flows.paymentsChargeOk();   // out-of-scope

		assertThat(ordersOnlyFinished.successCalls).hasSize(1);
		assertThat(ordersOnlyFinished.failureCalls).isEmpty();

		// Control sees both
		assertThat(unscopedControl.successCalls).hasSize(2);
	}

	@Test
	@Disabled("EventScope not working yet")
	@DisplayName("Multiple prefixes are OR'ed (orders.* OR payments.*)")
	void multiple_prefixes() {
		flows.ordersCreateOk();
		flows.paymentsChargeOk();

		assertThat(ordersOrPaymentsFinished.successCalls).hasSize(2);
		assertThat(ordersOrPaymentsFinished.failureCalls).isEmpty();
	}

	@Test
	@Disabled("EventScope not working yet")
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
	@Disabled("EventScope not working yet")
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
	@Disabled("EventScope not working yet")
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

		@Bean(name = "EventScopeSelectionTest.flows")
		Flows flows() { return new Flows(); }

		@Bean(name = "EventScopeSelectionTest.ordersOnlyFinished")
		OrdersOnlyFinished ordersOnlyFinished() { return new OrdersOnlyFinished(); }

		@Bean(name = "EventScopeSelectionTest.ordersOrPaymentsFinished")
		OrdersOrPaymentsFinished ordersOrPaymentsFinished() { return new OrdersOrPaymentsFinished(); }

		@Bean(name = "EventScopeSelectionTest.errorsOnlyFinished")
		ErrorsOnlyFinished errorsOnlyFinished() { return new ErrorsOnlyFinished(); }

		@Bean(name = "EventScopeSelectionTest.nonErrorsOnlyFinished")
		NonErrorsOnlyFinished nonErrorsOnlyFinished() { return new NonErrorsOnlyFinished(); }

		@Bean(name = "EventScopeSelectionTest.clientKindOnly")
		ClientKindOnly clientKindOnly() { return new ClientKindOnly(); }

		@Bean(name = "EventScopeSelectionTest.componentScopedUnmatched")
		ComponentScopedUnmatched componentScopedUnmatched() { return new ComponentScopedUnmatched(); }

		@Bean(name = "EventScopeSelectionTest.unscopedControl")
		UnscopedControl unscopedControl() { return new UnscopedControl(); }

		/** Emits distinct flows for names, kinds, and success/failure paths. */
		@TelemetryEventHandler
		static class Flows {

			@Flow(name = "orders.create") @Kind(SpanKind.INTERNAL)
			public void ordersCreateOk() { /* success */ }

			@Flow(name = "orders.create") @Kind(SpanKind.INTERNAL)
			public void ordersCreateFail() { throw new IllegalArgumentException("create-fail"); }

			@Flow(name = "payments.charge") @Kind(SpanKind.INTERNAL)
			public void paymentsChargeOk() { /* success */ }

			@Flow(name = "orders.query.client") @Kind(SpanKind.CLIENT)
			public void ordersQueryClient() { /* success */ }

			@Flow(name = "orders.query.server") @Kind(SpanKind.SERVER)
			public void ordersQueryServer() { /* success */ }
		}
	}

	/* ============================= Scoped handlers ============================= */

	/** Prefix-only scope to orders.*, lifecycle pinned to FLOW_FINISHED (common terminal). */
	@TelemetryEventHandler
	@EventScope(value = "orders.", lifecycles = { Lifecycle.FLOW_FINISHED })
	static class OrdersOnlyFinished {
		final List<TelemetryHolder> successCalls = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> failureCalls = new CopyOnWriteArrayList<>();

		@OnEvent(name = "orders.create", mode = DispatchMode.SUCCESS)
		public void success(TelemetryHolder h) { successCalls.add(h); }

		@OnEvent(name = "orders.create", mode = DispatchMode.FAILURE)
		public void failure(@BindEventThrowable Throwable t, TelemetryHolder h) { failureCalls.add(h); }

		void reset() { successCalls.clear(); failureCalls.clear(); }
	}

	/** Two prefixes: orders.* OR payments.*; provide a handler for each name. */
	@TelemetryEventHandler
	@EventScope(prefixes = { "orders.", "payments." }, lifecycles = { Lifecycle.FLOW_FINISHED })
	static class OrdersOrPaymentsFinished {
		final List<TelemetryHolder> successCalls = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> failureCalls = new CopyOnWriteArrayList<>();

		@OnEvent(name = "orders.create", mode = DispatchMode.SUCCESS)
		public void ordersCreateOk(TelemetryHolder h) { successCalls.add(h); }

		@OnEvent(name = "payments.charge", mode = DispatchMode.SUCCESS)
		public void paymentsChargeOk(TelemetryHolder h) { successCalls.add(h); }

		void reset() { successCalls.clear(); failureCalls.clear(); }
	}

	/** ErrorMode=ONLY_ERROR within orders.* at FLOW_FINISHED. */
	@TelemetryEventHandler
	@EventScope(value = "orders.", lifecycles = { Lifecycle.FLOW_FINISHED }, errorMode = EventScope.ErrorMode.ONLY_ERROR)
	static class ErrorsOnlyFinished {
		final List<TelemetryHolder> successCalls = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> failureCalls = new CopyOnWriteArrayList<>();

		@OnEvent(name = "orders.create", mode = DispatchMode.SUCCESS)
		public void success(TelemetryHolder h) { successCalls.add(h); }

		@OnEvent(name = "orders.create", mode = DispatchMode.FAILURE)
		public void failure(@BindEventThrowable Throwable t, TelemetryHolder h) { failureCalls.add(h); }

		void reset() { successCalls.clear(); failureCalls.clear(); }
	}

	/** ErrorMode=ONLY_NON_ERROR complement. */
	@TelemetryEventHandler
	@EventScope(value = "orders.", lifecycles = { Lifecycle.FLOW_FINISHED }, errorMode = EventScope.ErrorMode.ONLY_NON_ERROR)
	static class NonErrorsOnlyFinished {
		final List<TelemetryHolder> successCalls = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> failureCalls = new CopyOnWriteArrayList<>();

		@OnEvent(name = "orders.create", mode = DispatchMode.SUCCESS)
		public void success(TelemetryHolder h) { successCalls.add(h); }

		@OnEvent(name = "orders.create", mode = DispatchMode.FAILURE)
		public void failure(@BindEventThrowable Throwable t, TelemetryHolder h) { failureCalls.add(h); }

		void reset() { successCalls.clear(); failureCalls.clear(); }
	}

	/**
	 * Kind filter: only CLIENT spans under orders.* at FLOW_FINISHED.
	 * We deliberately register handlers for both names; @EventScope(kinds={CLIENT})
	 * must prevent the SERVER one from seeing events.
	 */
	@TelemetryEventHandler
	@EventScope(value = "orders.", lifecycles = { Lifecycle.FLOW_FINISHED }, kinds = { SpanKind.CLIENT })
	static class ClientKindOnly {
		final List<TelemetryHolder> calls = new CopyOnWriteArrayList<>();

		@OnEvent(name = "orders.query.client", mode = DispatchMode.SUCCESS)
		public void clientOk(TelemetryHolder h) { calls.add(h); }

		@OnEvent(name = "orders.query.server", mode = DispatchMode.SUCCESS)
		public void serverOk(TelemetryHolder h) { calls.add(h); } // should be blocked by EventScope

		void reset() { calls.clear(); }
	}

	/**
	 * Proves that out-of-scope events are invisible: even a COMPONENT-scoped unmatched
	 * handler does not fire when the event lies outside the component's @EventScope.
	 */
	@TelemetryEventHandler
	@EventScope(value = "orders.", lifecycles = { Lifecycle.FLOW_FINISHED })
	static class ComponentScopedUnmatched {
		final List<TelemetryHolder> componentUnmatched = new CopyOnWriteArrayList<>();

		@OnUnMatchedEvent(scope = OnUnMatchedEvent.Scope.COMPONENT, mode = DispatchMode.COMBINED)
		public void onUnmatchedAtComponent(TelemetryHolder holder) {
			componentUnmatched.add(holder);
		}

		void reset() { componentUnmatched.clear(); }
	}

	/** Control group: no EventScope → sees everything. */
	@TelemetryEventHandler
	static class UnscopedControl {
		final List<TelemetryHolder> successCalls = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> failureCalls = new CopyOnWriteArrayList<>();

		@OnEvent(name = "orders.create", mode = DispatchMode.SUCCESS)
		public void sOrdersCreate(TelemetryHolder h) { successCalls.add(h); }

		@OnEvent(name = "payments.charge", mode = DispatchMode.SUCCESS)
		public void sPaymentsCharge(TelemetryHolder h) { successCalls.add(h); }

		@OnEvent(name = "orders.create", mode = DispatchMode.FAILURE)
		public void fOrdersCreate(@BindEventThrowable Throwable t, TelemetryHolder h) { failureCalls.add(h); }

		@OnEvent(name = "orders.query.client", mode = DispatchMode.SUCCESS)
		public void sOrdersQueryClient(TelemetryHolder h) { successCalls.add(h); }

		@OnEvent(name = "orders.query.server", mode = DispatchMode.SUCCESS)
		public void sOrdersQueryServer(TelemetryHolder h) { successCalls.add(h); }

		void reset() { successCalls.clear(); failureCalls.clear(); }
	}
}
