package com.obsinity.telemetry.aspect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.annotation.DirtiesContext;

import io.opentelemetry.api.trace.SpanKind;

import com.obsinity.telemetry.annotations.EventReceiver;
import com.obsinity.telemetry.annotations.Flow;
import com.obsinity.telemetry.annotations.Kind;
import com.obsinity.telemetry.annotations.OnFlowStarted;
import com.obsinity.telemetry.annotations.OnFlowCompleted;
import com.obsinity.telemetry.annotations.OnFlowSuccess;
import com.obsinity.telemetry.annotations.PullAllContextValues;
import com.obsinity.telemetry.annotations.PullAttribute;
import com.obsinity.telemetry.annotations.PullContextValue;
import com.obsinity.telemetry.annotations.Step;

import com.obsinity.telemetry.dispatch.HandlerGroup;
import com.obsinity.telemetry.dispatch.TelemetryEventHandlerScanner;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.OAttributes;
import com.obsinity.telemetry.model.OEvent;
import com.obsinity.telemetry.model.TelemetryHolder;
import com.obsinity.telemetry.processor.AttributeParamExtractor;
import com.obsinity.telemetry.processor.TelemetryAttributeBinder;
import com.obsinity.telemetry.processor.TelemetryContext;
import com.obsinity.telemetry.processor.TelemetryProcessor;
import com.obsinity.telemetry.processor.TelemetryProcessorSupport;
import com.obsinity.telemetry.receivers.TelemetryDispatchBus;

@SpringBootTest(
	classes = TelemetryOnEventParamBindingTest.TestConfig.class,
	webEnvironment = SpringBootTest.WebEnvironment.NONE,
	properties = {
		"spring.main.web-application-type=none",
		"spring.main.allow-bean-definition-overriding=true"
	})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TelemetryOnEventParamBindingTest {

	/* ===== Sample complex object carried as an attribute ===== */
	static record ComplexMeta(String ref, int weight) {}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration(exclude = {com.obsinity.telemetry.configuration.AutoConfiguration.class})
	@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
	static class TestConfig {
		@Bean AttributeParamExtractor attributeParamExtractor() { return new AttributeParamExtractor(); }

		@Bean TelemetryAttributeBinder telemetryAttributeBinder(AttributeParamExtractor extractor) {
			return new TelemetryAttributeBinder(extractor);
		}

		@Bean TelemetryProcessorSupport telemetryProcessorSupport() { return new TelemetryProcessorSupport(); }

		@Bean List<HandlerGroup> handlerGroups(ListableBeanFactory bf, TelemetryProcessorSupport support) {
			return new TelemetryEventHandlerScanner(bf, support).handlerGroups();
		}

		// Bus now takes just the groups
		@Bean TelemetryDispatchBus telemetryDispatchBus(List<HandlerGroup> groups) {
			return new TelemetryDispatchBus(groups);
		}

		@Bean TelemetryProcessor telemetryProcessor(
			TelemetryAttributeBinder binder, TelemetryProcessorSupport support, TelemetryDispatchBus bus) {
			return new TelemetryProcessor(binder, support, bus) {
				@Override
				protected OAttributes buildAttributes(
					org.aspectj.lang.ProceedingJoinPoint pjp, com.obsinity.telemetry.aspect.FlowOptions opts) {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("test.flow", opts.name());
					m.put("declaring.class", pjp.getSignature().getDeclaringTypeName());
					m.put("declaring.method", pjp.getSignature().getName());
					OAttributes attrs = new OAttributes(m);
					binder.bind(attrs, pjp);
					return attrs;
				}
			};
		}

		@Bean TelemetryAspect telemetryAspect(TelemetryProcessor p) { return new TelemetryAspect(p); }

		@Bean TelemetryContext telemetryContext(TelemetryProcessorSupport support) {
			return new TelemetryContext(support);
		}

		@Bean TestService testService(TelemetryContext telemetry) { return new TestService(telemetry); }

		@Bean BindingCaptureReceiver bindingCaptureReceiver() { return new BindingCaptureReceiver(); }
	}

	/* ===== Test service that writes attributes + EventContext ===== */
	@Kind(SpanKind.SERVER)
	static class TestService {
		private final TelemetryContext telemetry;

		TestService(TelemetryContext telemetry) { this.telemetry = telemetry; }

		@Flow(name = "bindingFlow")
		public void bindingFlow() {
			UUID orderId = UUID.randomUUID();
			telemetry.putAttr("user.id", "user-1");
			telemetry.putAttr("attempt", Integer.valueOf(3));
			telemetry.putAttr("order.uuid", orderId);
			telemetry.putAttr("meta", new ComplexMeta("ref-xyz", 42));
			telemetry.putContext("tenant", "acme");
			telemetry.putContext("retries", Integer.valueOf(2));
		}

		@Flow(name = "flowWithStep")
		public void flowWithStep() {
			((TestService) AopContext.currentProxy()).stepWithParams();
		}

		@Step(name = "stepWithParams")
		public void stepWithParams() {
			telemetry.putAttr("step.answer", Integer.valueOf(7));
			telemetry.putAttr("step.note", "ok");
			telemetry.putContext("step.ctx", "ctx!");
		}

		@Kind(SpanKind.PRODUCER)
		@Step(name = "lonelyStep")
		public void lonelyStep() { /* no-op */ }
	}

	/* ===== Receiver that captures bound params (new API) ===== */
	@EventReceiver
	static class BindingCaptureReceiver {

		// collect lifecycle-specific taps
		final List<TelemetryHolder> starts = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> finishes = new CopyOnWriteArrayList<>();

		// binding assertions
		static final class FlowCapture {
			final String userId;
			final Integer attempt;
			final UUID orderUuid;
			final ComplexMeta meta;
			final String tenantCtx;
			final Integer retriesCtx;
			final Map<String, Object> ctxAll;
			FlowCapture(String userId, Integer attempt, UUID orderUuid, ComplexMeta meta,
						String tenantCtx, Integer retriesCtx, Map<String, Object> ctxAll) {
				this.userId = userId; this.attempt = attempt; this.orderUuid = orderUuid;
				this.meta = meta; this.tenantCtx = tenantCtx; this.retriesCtx = retriesCtx; this.ctxAll = ctxAll;
			}
		}
		final List<FlowCapture> flowFinished = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> allFinishes = new CopyOnWriteArrayList<>();

		// step capture
		static final class StepCapture {
			final Integer ans; final String note; final String ctx; final TelemetryHolder holder;
			StepCapture(Integer ans, String note, String ctx, TelemetryHolder holder) {
				this.ans = ans; this.note = note; this.ctx = ctx; this.holder = holder;
			}
		}
		final List<StepCapture> stepFinished = new CopyOnWriteArrayList<>();

		/* ---- Start taps (lonely step only, to keep the test’s assertion) ---- */
		@OnFlowStarted(name = "lonelyStep")
		public void onLonelyStepStart(TelemetryHolder holder, Lifecycle phase) {
			if (phase == Lifecycle.FLOW_STARTED) {
				starts.add(holder);
			}
		}

		/* ---- Completed/Success handlers (name-specific) ---- */

		// bindingFlow success → bind attributes + context
		@OnFlowSuccess(name = "bindingFlow")
		public void onBindingFlowFinished(
			TelemetryHolder holder,
			@PullAttribute(name = "user.id") String userId,
			@PullAttribute(name = "attempt") Integer attempt,
			@PullAttribute(name = "order.uuid") UUID orderUuid,
			@PullAttribute(name = "meta") ComplexMeta meta,
			@PullContextValue(name = "tenant") String tenant,
			@PullContextValue(name = "retries") Integer retries,
			@PullAllContextValues Map<String, Object> allCtx,
			Lifecycle phase) {
			if (phase == Lifecycle.FLOW_FINISHED) {
				flowFinished.add(new FlowCapture(userId, attempt, orderUuid, meta, tenant, retries, allCtx));
				allFinishes.add(holder);
			}
		}

		// stepWithParams success → bind step attrs/context
		@OnFlowSuccess(name = "stepWithParams")
		public void stepWithParamsFinishedSuccessfully(
			@PullAttribute(name = "step.answer") Integer ans,
			@PullAttribute(name = "step.note") String note,
			@PullContextValue(name = "step.ctx") String sctx,
			TelemetryHolder holder,
			Lifecycle phase) {
			if (phase == Lifecycle.FLOW_FINISHED) {
				stepFinished.add(new StepCapture(ans, note, sctx, holder));
			}
		}

		// lonelyStep success → treat lonely step like a flow and also track finishes list
		@OnFlowSuccess(name = "lonelyStep")
		public void lonelyStepFinishedSuccessfully(TelemetryHolder holder, Lifecycle phase) {
			if (phase == Lifecycle.FLOW_FINISHED) {
				finishes.add(holder);
			}
		}

		// also capture flowWithStep finish so the test can find the parent holder
		@OnFlowCompleted(name = "flowWithStep")
		public void flowWithStepCompleted(TelemetryHolder holder, Lifecycle phase) {
			if (phase == Lifecycle.FLOW_FINISHED) {
				finishes.add(holder);
			}
		}
	}

	@Autowired TestService service;
	@Autowired BindingCaptureReceiver handler;

	@BeforeEach
	void reset() {
		handler.flowFinished.clear();
		handler.stepFinished.clear();
		handler.allFinishes.clear();
		handler.starts.clear();
		handler.finishes.clear();
	}

	@Test
	@DisplayName("FLOW_FINISHED binds String, Integer, UUID, complex object + EventContext")
	void flowFinished_bindsAttributesAndContextTypes() {
		service.bindingFlow();
		assertThat(handler.flowFinished).hasSize(1);
		var cap = handler.flowFinished.get(0);

		assertThat(cap.userId).isEqualTo("user-1");
		assertThat(cap.attempt).isEqualTo(3);
		assertThat(cap.orderUuid).isNotNull();
		assertThat(cap.meta).isEqualTo(new ComplexMeta("ref-xyz", 42));
		assertThat(cap.tenantCtx).isEqualTo("acme");
		assertThat(cap.retriesCtx).isEqualTo(2);
		assertThat(cap.ctxAll).containsEntry("tenant", "acme").containsEntry("retries", 2);

		assertThat(handler.allFinishes).hasSize(1);
		var flowHolder = handler.allFinishes.get(0);
		assertThat(flowHolder.name()).isEqualTo("bindingFlow");
		assertThat(flowHolder.isStep()).isFalse();
		assertTraceAndSpanIds(flowHolder);
	}

	@Test
	@DisplayName("STEP temp-holder binds params; folded event matches")
	void stepFinished_bindsAttributesAndContext_thenFoldsIntoParentEvent() {
		service.flowWithStep();

		assertThat(handler.stepFinished).hasSize(1);
		var sc = handler.stepFinished.get(0);
		assertThat(sc.ans).isEqualTo(7);
		assertThat(sc.note).isEqualTo("ok");
		assertThat(sc.ctx).isEqualTo("ctx!");
		assertThat(sc.holder.isStep()).isTrue();

		TelemetryHolder parent = handler.finishes.stream()
			.filter(h -> !h.isStep() && "flowWithStep".equals(h.name()))
			.findFirst()
			.orElseThrow();

		OEvent folded = parent.events().stream()
			.filter(e -> "stepWithParams".equals(e.name()))
			.findFirst()
			.orElseThrow();

		assertThat(folded.attributes().asMap())
			.containsEntry("step.answer", 7)
			.containsEntry("step.note", "ok");
		assertThat(folded.eventContext().get("step.ctx")).isEqualTo("ctx!");
	}

	@Test
	@DisplayName("Lonely @Step with @AutoFlow behaves like a real flow")
	void lonelyStep_autoFlow_bindsLikeFlow() {
		service.lonelyStep();
		assertThat(handler.starts).hasSize(1);     // start captured via @OnFlow (phase=FLOW_STARTED)
		assertThat(handler.finishes).hasSize(1);   // finish captured via @OnFlowSuccess (phase=FLOW_FINISHED)

		TelemetryHolder h = handler.finishes.get(0);
		assertThat(h.name()).isEqualTo("lonelyStep");
		assertThat(h.parentSpanId()).isNull();
		assertThat(h.isStep()).isFalse();
	}

	private static final Pattern HEX_32 = Pattern.compile("^[0-9a-f]{32}$");
	private static final Pattern HEX_16 = Pattern.compile("^[0-9a-f]{16}$");

	private static void assertTraceAndSpanIds(TelemetryHolder h) {
		assertThat(h.traceId()).matches(HEX_32);
		assertThat(h.spanId()).matches(HEX_16);
	}
}
