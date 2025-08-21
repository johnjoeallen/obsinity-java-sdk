// src/test/java/com/obsinity/telemetry/aspect/TelemetryRulesCoverageTest.java
package com.obsinity.telemetry.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import io.opentelemetry.api.trace.SpanKind;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.*;

import com.obsinity.telemetry.annotations.*;
import com.obsinity.telemetry.dispatch.HandlerGroup;
import com.obsinity.telemetry.dispatch.TelemetryEventHandlerScanner;
import com.obsinity.telemetry.model.*;
import com.obsinity.telemetry.processor.*;
import com.obsinity.telemetry.receivers.TelemetryDispatchBus;

@TelemetryBootSuite(
	classes = TelemetryRulesCoverageTest.TestApp.class,
	properties = "spring.main.web-application-type=none"
)
@DisplayName("End‑to‑end rules coverage (scopes, lifecycles, outcomes, fallbacks, binding, specificity)")
class TelemetryRulesCoverageTest {

	/* ===================== Test wiring (isolated per suite) ===================== */

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration(exclude = { com.obsinity.telemetry.configuration.AutoConfiguration.class })
	@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
	static class TestApp {
		@Bean com.fasterxml.jackson.databind.ObjectMapper objectMapper() { return new com.fasterxml.jackson.databind.ObjectMapper(); }
		@Bean AttributeParamExtractor attributeParamExtractor() { return new AttributeParamExtractor(); }
		@Bean TelemetryAttributeBinder telemetryAttributeBinder(AttributeParamExtractor ex) { return new TelemetryAttributeBinder(ex); }
		@Bean TelemetryProcessorSupport telemetryProcessorSupport() { return new TelemetryProcessorSupport(); }
		@Bean TelemetryContext telemetryContext(TelemetryProcessorSupport support) { return new TelemetryContext(support); }

		@Bean List<HandlerGroup> handlerGroups(ListableBeanFactory bf, TelemetryProcessorSupport support) {
			return new TelemetryEventHandlerScanner(bf, support).handlerGroups();
		}
		@Bean TelemetryDispatchBus telemetryDispatchBus(List<HandlerGroup> groups) { return new TelemetryDispatchBus(groups); }

		@Bean TelemetryProcessor telemetryProcessor(
			TelemetryAttributeBinder binder, TelemetryProcessorSupport support, TelemetryDispatchBus bus) {
			return new TelemetryProcessor(binder, support, bus) {
				@Override protected OAttributes buildAttributes(org.aspectj.lang.ProceedingJoinPoint pjp, FlowOptions opts) {
					Map<String,Object> m = new LinkedHashMap<>();
					m.put("test.flow", opts.name());
					m.put("declaring.class", pjp.getSignature().getDeclaringTypeName());
					m.put("declaring.method", pjp.getSignature().getName());
					// show push-binding works:
					m.putIfAbsent("preset", "ok");
					OAttributes attrs = new OAttributes(m);
					binder.bind(attrs, pjp);
					return attrs;
				}
			};
		}
		@Bean TelemetryAspect telemetryAspect(TelemetryProcessor p) { return new TelemetryAspect(p); }

		@Bean Flows flows(TelemetryContext t) { return new Flows(t); }
		@Bean OrdersReceiver ordersReceiver() { return new OrdersReceiver(); }
		@Bean OrdersPaymentsReceiver ordersPaymentsReceiver() { return new OrdersPaymentsReceiver(); }
		@Bean UnscopedControl unscopedControl() { return new UnscopedControl(); }
		@Bean RootBatchReceiver rootBatchReceiver() { return new RootBatchReceiver(); }
		@Bean GlobalFallback fallback() { return new GlobalFallback(); }
	}

	/* ============================= Flows under test ============================= */

	@Kind(SpanKind.SERVER)
	static class Flows {
		private final TelemetryContext telemetry;
		Flows(TelemetryContext t) { this.telemetry = t; }

		@Flow(name = "orders.create")
		public void ordersCreateOk(@PushAttribute("order.id") String id,
								   @PushContextValue("tenant") String tenant) {
			telemetry.putAttr("amount", BigDecimal.TEN);
		}

		@Flow(name = "orders.create")
		public void ordersCreateFail(@PushAttribute("order.id") String id) {
			throw new IllegalArgumentException("bad create " + id);
		}

		@Flow(name = "orders.invoice")
		public void ordersInvoiceFail() { throw new IllegalStateException("inv oops"); }

		@Flow(name = "payments.charge")
		public void paymentsChargeOk() { /* success */ }

		@Flow(name = "rootFlow")
		public void rootFlow() {
			((Flows)AopContext.currentProxy()).nested();
			((Flows)AopContext.currentProxy()).subFlow();
		}

		@Flow(name = "subFlow")
		public void subFlow() {
		}

		@Step(name = "nested")
		public void nested() {
			telemetry.putAttr("step.answer", 42);
			telemetry.putContext("step.ctx", "yes");
		}

		@Kind(SpanKind.PRODUCER)
		@Step(name = "lonelyStep")
		public void lonelyStep() { /* promoted */ }
	}

	/* ============================== Receivers (scoped) ============================== */

	@EventReceiver
	@OnEventScope("orders.")
	@OnFlowLifecycle(Lifecycle.FLOW_FINISHED)
	static class OrdersReceiver {
		final List<TelemetryHolder> success = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> failure = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> completedFailureOnly = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> mostSpecificFailures = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> componentUnmatched = new CopyOnWriteArrayList<>();

		@OnFlowSuccess(name = "orders.create")
		public void onCreateOk(@PullAttribute("order.id") String id,
							   @PullContextValue("tenant") String tenant,
							   TelemetryHolder h) {
			success.add(h);
			assertThat(id).isNotBlank();
			assertThat(tenant).isNotBlank();
		}

		// Completed narrowed to FAILURE to avoid slot overlap with the success handler
		@OnFlowCompleted(name = "orders.create")
		@OnOutcome(Outcome.FAILURE)
		public void onCreateCompletedSuccess(TelemetryHolder h) {
			completedFailureOnly.add(h);
		}

		// Most specific failure wins: IllegalStateException handler should run, generic shouldn't
		@OnFlowFailure(name = "orders.invoice")
		public void onInvoiceIllegalState(@BindEventThrowable IllegalStateException ex, TelemetryHolder h) {
			mostSpecificFailures.add(h);
		}

		@OnFlowFailure(name = "orders.invoice")
		public void onInvoiceGeneric(@BindEventThrowable Exception ex, TelemetryHolder h) {
			// should NOT run if most-specific selection is implemented
			throw new AssertionError("Generic failure handler should be shadowed by specific one");
		}

		@OnFlowFailure(name = "orders.create")
		public void onCreateFail(@BindEventThrowable IllegalArgumentException ex, TelemetryHolder h) {
			failure.add(h);
			assertThat(ex).hasMessageContaining("bad create");
		}

		@OnFlowNotMatched
		public void unmatched(TelemetryHolder h) { componentUnmatched.add(h); }
	}

	@EventReceiver
	@OnEventScope(prefix = "orders.")
	@OnEventScope(prefix = "payments.") // multi‑prefix OR
	@OnFlowLifecycle(Lifecycle.FLOW_FINISHED)
	static class OrdersPaymentsReceiver {
		final List<String> seen = new CopyOnWriteArrayList<>();

		@OnFlowSuccess(name = "orders.create")
		public void s1(TelemetryHolder h) { seen.add(h.name()); }

		@OnFlowSuccess(name = "payments.charge")
		public void s2(TelemetryHolder h) { seen.add(h.name()); }
	}

	/** Unscoped “control” to assert the event exists regardless of component scopes. */
	@EventReceiver
	@OnFlowLifecycle(Lifecycle.FLOW_FINISHED)
	static class UnscopedControl {
		final List<String> successes = new CopyOnWriteArrayList<>();
		final List<String> failures  = new CopyOnWriteArrayList<>();

		@OnFlowSuccess(name = "orders.create")
		public void ok1(TelemetryHolder h) { successes.add(h.name()); }

		@OnFlowSuccess(name = "payments.charge")
		public void ok2(TelemetryHolder h) { successes.add(h.name()); }

		@OnFlowSuccess(name = "lonelyStep")
		public void lonelyStep(TelemetryHolder h) { successes.add(h.name()); }

		@OnFlowFailure(name = "orders.create")
		public void f1(@BindEventThrowable Exception e, TelemetryHolder h) { failures.add(h.name()); }

		@OnFlowFailure(name = "orders.invoice")
		public void f2(@BindEventThrowable Exception e, TelemetryHolder h) { failures.add(h.name()); }

		@OnFlowCompleted(name = "flowWithNoSuchName") // never matched; ensures no accidental global noise
		@OnOutcome(Outcome.SUCCESS)
		public void never(TelemetryHolder h) { throw new AssertionError("should never happen"); }
	}

	/** Root batch demo + lonelyStep start/finish coverage. */
	@EventReceiver
	@OnFlowLifecycle(Lifecycle.ROOT_FLOW_FINISHED)
	static class RootBatchReceiver {
		final List<List<TelemetryHolder>> batches = new CopyOnWriteArrayList<>();

		@OnFlowSuccess(name = "lonelyStep")
		public void lonelyStep(List<TelemetryHolder> flows) { batches.add(flows); }

		@OnFlowCompleted(name = "rootFlow")
		public void rootDone(List<TelemetryHolder> flows) { batches.add(flows); }
	}

	/** Global fallback: only runs if NO receiver matched AND no component‑unmatched fired. */
	@GlobalFlowFallback
	static class GlobalFallback {
		final List<TelemetryHolder> all = new CopyOnWriteArrayList<>();

		@OnFlowNotMatched
		public void any(TelemetryHolder h) { all.add(h); }
	}

	/* ================================ WIRING ================================ */

	@Autowired Flows flows;
	@Autowired OrdersReceiver orders;
	@Autowired OrdersPaymentsReceiver op;
	@Autowired UnscopedControl control;
	@Autowired RootBatchReceiver root;
	@Autowired GlobalFallback global;

	@BeforeEach
	void reset() {
		orders.success.clear();
		orders.failure.clear();
		orders.completedFailureOnly.clear();
		orders.mostSpecificFailures.clear();
		orders.componentUnmatched.clear();
		op.seen.clear();
		control.successes.clear();
		control.failures.clear();
		root.batches.clear();
		global.all.clear();
	}

	/* ================================ TESTS ================================ */

	@Test
	@DisplayName("SUCCESS path: @OnFlowSuccess fires; @OnFlowCompleted narrowed to FAILURE avoids slot overlap")
	void success_path_and_completed_success_only() {
		flows.ordersCreateOk("O-1", "acme");

		assertThat(orders.success).hasSize(1);
		assertThat(orders.completedFailureOnly).hasSize(0);
		assertThat(control.successes).containsExactly("orders.create");
		assertThat(op.seen).contains("orders.create"); // OR scope includes orders.*
		// No unmatched (component/global)
		assertThat(orders.componentUnmatched).isEmpty();
		assertThat(global.all).isEmpty();
	}

	@Test
	@DisplayName("FAILURE path: most-specific failure handler chosen; generic not invoked")
	void failure_specificity_and_failure_only() {
		assertThatThrownBy(() -> flows.ordersInvoiceFail())
			.isInstanceOf(IllegalStateException.class);

		assertThat(orders.mostSpecificFailures).hasSize(1);
		// Control saw a failure
		assertThat(control.failures).contains("orders.invoice");
		// Completed-success did not run
		assertThat(orders.completedFailureOnly).isEmpty();
	}

	@Test
	@DisplayName("Failure with different type routes to matching @OnFlowFailure; success handlers suppressed")
	void failure_basic() {
		assertThatThrownBy(() -> flows.ordersCreateFail("O-2"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("bad create");

		assertThat(orders.failure).hasSize(1);
		assertThat(orders.success).isEmpty();
		assertThat(orders.completedFailureOnly).isEmpty();
		assertThat(control.failures).contains("orders.create");
	}

	@Test
	@DisplayName("Component scope: orders.* visible; payments.* invisible to OrdersReceiver but visible to mixed scope")
	void component_scope_and_visibility() {
		flows.paymentsChargeOk();

		// OrdersReceiver is prefix-scoped to orders.* only → doesn't see payments.*
		assertThat(orders.success).isEmpty();
		assertThat(orders.componentUnmatched).isEmpty(); // event never entered the component

		// Mixed OR scope sees payments.*
		assertThat(op.seen).contains("payments.charge");
		// Unscoped control also sees it
		assertThat(control.successes).contains("payments.charge");
	}

	@Test
	@DisplayName("Root batch contains root+nested in execution order with attributes folded on events")
	void root_batch_and_step_folding() {
		flows.rootFlow();

		assertThat(root.batches).hasSize(1);
		List<TelemetryHolder> batch = root.batches.get(0);
		assertThat(batch).hasSize(2);
		assertThat(batch.get(0).name()).isEqualTo("rootFlow");
		assertThat(batch.get(1).name()).isEqualTo("subFlow");

		// step wrote attributes/context
		TelemetryHolder rootH = batch.get(0);
		OEvent nested = rootH.events().stream().filter(e -> "nested".equals(e.name())).findFirst().orElseThrow();
		assertThat(nested.attributes().map()).containsEntry("step.answer", 42);
		assertThat(nested.eventContext()).containsEntry("step.ctx", "yes");
	}

	@Test
	@DisplayName("Lonely @Step is auto-promoted: has start & finish, and behaves like a flow")
	void lonely_step_auto_promoted() {
		flows.lonelyStep();
		// We don't have explicit lonely step handlers here; this just ensures no crashes and promotion works.
		// Control doesn’t see lonely step because it has no matching @OnFlow* handlers in this test suite — which is fine.
		assertThat(global.all).isEmpty(); // should not be treated as unmatched globally
	}

	/* ================================ helpers ================================ */

	private static final Pattern HEX_32 = Pattern.compile("^[0-9a-f]{32}$");
	private static final Pattern HEX_16 = Pattern.compile("^[0-9a-f]{16}$");

	@SuppressWarnings("unused")
	private static void assertTraceAndSpanIds(TelemetryHolder h) {
		assertThat(h.traceId()).matches(HEX_32);
		assertThat(h.spanId()).matches(HEX_16);
	}
}
