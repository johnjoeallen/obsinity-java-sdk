package com.obsinity.telemetry.aspect;

import static com.obsinity.telemetry.model.Lifecycle.FLOW_FINISHED;
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
 * Validates component-level filtering via @OnEventScope. Notes: - @OnEvent requires a 'name' parameter -> each handler
 * method binds to a specific event name. - Throwable binding uses @BindEventThrowable.
 */
@SpringBootTest(classes = com.obsinity.telemetry.aspect.EventScopeSelectionTest.Config.class)
class EventScopeSelectionTest {

	/* ============================= Scoped handlers ============================= */

	/** Prefix-only scope to orders.*, lifecycle pinned to FLOW_FINISHED (common terminal). */
	@EventReceiver
	@OnEventScope("orders.")
	@OnEventLifecycle(FLOW_FINISHED)
	static class OrdersOnlyFinished {
		final List<TelemetryHolder> successCalls = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> failureCalls = new CopyOnWriteArrayList<>();

		@OnFlow("orders.create")
		public void onFlow(TelemetryHolder h) {
			successCalls.add(h);
		}

		@OnFlowFailure("orders.create")
		public void failure(@BindEventThrowable Throwable t, TelemetryHolder h) {
			failureCalls.add(h);
		}

		@OnFlowFailure("orders.create")
		public void failure(@BindEventThrowable Throwable t, TelemetryHolder h) {
			failureCalls.add(h);
		}

		void reset() {
			successCalls.clear();
			failureCalls.clear();
		}
	}

	/** Two prefixes: orders.* OR payments.*; provide a handler for each name. */
	@EventReceiver
	@OnEventScope("orders.")
	@OnEventScope("payments.")
	@OnEventLifecycle(FLOW_FINISHED)
	static class OrdersOrPaymentsFinished {
		final List<TelemetryHolder> successCalls = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> failureCalls = new CopyOnWriteArrayList<>();

		@OnFlowSuccess("orders.create")
		public void ordersCreateOk(TelemetryHolder h) {
			successCalls.add(h);
		}

		@OnFlowSuccess("payments.create")
		public void paymentsChargeOk(TelemetryHolder h) {
			successCalls.add(h);
		}

		void reset() {
			successCalls.clear();
			failureCalls.clear();
		}
	}

	/** ErrorMode=ONLY_ERROR within orders.* at FLOW_FINISHED. */
	@EventReceiver
	@OnEventScope("orders.")
	@OnEventLifecycle(FLOW_FINISHED)
	@OnOutcome(SUCCESS)
//	@OnOutcome(FAILURE) both can be specified
	static class ErrorsOnlyFinished {
		final List<TelemetryHolder> successCalls = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> failureCalls = new CopyOnWriteArrayList<>();

		@OnFlowCompleted("orders.create")
		@OnOutcome(SUCCESS)
		public void ordersCreateOk(TelemetryHolder h) {
			successCalls.add(h);
		}

		@OnFlowCompleted("orders.create")
		@OnOutcome(FAILURE)
		public void failure(@BindEventThrowable Throwable t, TelemetryHolder h) {
			failureCalls.add(h);
		}

		void reset() {
			successCalls.clear();
			failureCalls.clear();
		}
	}
}
