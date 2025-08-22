package com.obsinity.telemetry.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import com.obsinity.telemetry.annotations.OnAllLifecycles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import io.opentelemetry.api.trace.SpanKind;
import com.obsinity.telemetry.annotations.BindEventThrowable;
import com.obsinity.telemetry.annotations.EventReceiver;
import com.obsinity.telemetry.annotations.Flow;
import com.obsinity.telemetry.annotations.GlobalFlowFallback;
import com.obsinity.telemetry.annotations.Kind;
import com.obsinity.telemetry.annotations.OnFlowFailure;
import com.obsinity.telemetry.annotations.OnFlowNotMatched;
import com.obsinity.telemetry.annotations.OnFlowSuccess;
import com.obsinity.telemetry.annotations.PullAttribute;
import com.obsinity.telemetry.annotations.PushAttribute;
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

@TelemetryBootSuite(
		classes = TelemetryIntegrationBootTest.TestApp.class,
		properties = {"spring.main.web-application-type=none"})
class TelemetryIntegrationBootTest {

	private static final Logger log = LoggerFactory.getLogger(TelemetryIntegrationBootTest.class);

	/** Example object type to store under the "custom.tag" attribute. */
	static record CustomTag(String value) {}

	@Configuration
	@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
	static class TestApp {
		@Bean
		com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
			return new com.fasterxml.jackson.databind.ObjectMapper();
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

		@Bean
		TelemetryDispatchBus telemetryDispatchBus(List<HandlerGroup> groups) {
			return new TelemetryDispatchBus(groups);
		}

		@Bean
		TelemetryProcessor telemetryProcessor(
				TelemetryAttributeBinder binder, TelemetryProcessorSupport support, TelemetryDispatchBus dispatchBus) {
			return new TelemetryProcessor(binder, support, dispatchBus) {
				@Override
				protected OAttributes buildAttributes(org.aspectj.lang.ProceedingJoinPoint pjp, FlowOptions opts) {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("test.flow", opts.name());
					m.put("declaring.class", pjp.getSignature().getDeclaringTypeName());
					m.put("declaring.method", pjp.getSignature().getName());
					m.put("custom.tag", new CustomTag("integration"));
					OAttributes attrs = new OAttributes(m);
					binder.bind(attrs, pjp);
					return attrs;
				}
			};
		}

		@Bean
		TelemetryAspect telemetryAspect(TelemetryProcessor p) {
			return new TelemetryAspect(p);
		}

		@Bean
		TelemetryIntegrationBootTestService telemetryIntegrationBootTestService(TelemetryContext telemetry) {
			return new TelemetryIntegrationBootTestService(telemetry);
		}

		@Bean
		RecordingReceiver recordingReceiver() {
			return new RecordingReceiver();
		}

		@Bean
		GlobalFallbackReceiver globalFallbackReceiver() {
			return new GlobalFallbackReceiver();
		}
	}

	/** Global fallbacks with phase filtering via method parameter (no lifecycle element on the annotation). */
	@GlobalFlowFallback
	@OnAllLifecycles
	static class GlobalFallbackReceiver {
		final List<TelemetryHolder> starts = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> finishes = new CopyOnWriteArrayList<>();
		final List<List<TelemetryHolder>> rootBatches = new CopyOnWriteArrayList<>();
		final List<CustomTag> finishCustomTags = new CopyOnWriteArrayList<>();

		@OnFlowNotMatched
		public void onStart(TelemetryHolder holder, Lifecycle phase) {
			if (phase == Lifecycle.FLOW_STARTED) starts.add(holder);
		}

		@OnFlowNotMatched
		public void onFinish(TelemetryHolder holder, Lifecycle phase) {
			if (phase == Lifecycle.FLOW_FINISHED) finishes.add(holder);
		}

		@OnFlowNotMatched
		public void onFinishCustomTag(@PullAttribute(name = "custom.tag") CustomTag customTag, Lifecycle phase) {
			if (phase == Lifecycle.FLOW_FINISHED) finishCustomTags.add(customTag);
		}

		@OnFlowNotMatched
		public void onRoot(List<TelemetryHolder> batch, Lifecycle phase) {
			// batch is only populated at ROOT_FLOW_FINISHED by the binder
			if (phase == Lifecycle.ROOT_FLOW_FINISHED && batch != null) rootBatches.add(batch);
		}
	}

	/** Named handlers for a thrown flow (success/failure) */
	@EventReceiver
	static class RecordingReceiver {
		final List<TelemetryHolder> normalOnErrorFinishes = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> alwaysOnErrorFinishes = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> errorFinishes = new CopyOnWriteArrayList<>();
		final List<Throwable> capturedErrors = new CopyOnWriteArrayList<>();

		@OnFlowSuccess(name = "flowError")
		public void normalFinishOnError(TelemetryHolder holder) {
			normalOnErrorFinishes.add(holder);
		}

		// No @OnFlowCompleted here to avoid overlapping the (FLOW_FINISHED,SUCCESS/FAILURE) slots.

		@OnFlowFailure(name = "flowError")
		public void errorFinishOnError(@BindEventThrowable Exception ex, TelemetryHolder holder) {
			errorFinishes.add(holder);
			capturedErrors.add(ex);
		}

		@OnFlowFailure(name = "flowError")
		public void errorFinishOnError(@BindEventThrowable IllegalStateException ex, TelemetryHolder holder) {
			errorFinishes.add(holder);
			capturedErrors.add(ex);
		}
	}

	@Kind(SpanKind.SERVER)
	static class TelemetryIntegrationBootTestService {
		private final TelemetryContext telemetry;

		TelemetryIntegrationBootTestService(TelemetryContext telemetry) {
			this.telemetry = telemetry;
		}

		@Flow(name = "flowA")
		public String flowA() {
			((TelemetryIntegrationBootTestService) AopContext.currentProxy()).stepB();
			return "ok";
		}

		@Step(name = "stepB")
		public void stepB() {
			telemetry.putAttr("step.flag", true);
			telemetry.putContext("step.ctx", "ctx-value");
		}

		@Kind(SpanKind.PRODUCER)
		@Step(name = "lonelyStep")
		public void lonelyStep() {
			/* no-op */
		}

		@Kind(SpanKind.CLIENT)
		@Flow(name = "flowClient")
		public void flowClient() {
			/* no-op */
		}

		@Flow(name = "rootFlow")
		public void rootFlow() {
			((TelemetryIntegrationBootTestService) AopContext.currentProxy()).nestedFlow();
		}

		@Flow(name = "nestedFlow")
		public void nestedFlow() {
			/* no-op */
		}

		@Flow(name = "paramFlowExample")
		public void paramFlowExample(
				@PushAttribute(name = "user.id") String userId,
				@PushAttribute(name = "flags") Map<String, Object> flags) {
			/* no-op */
		}

		@Flow(name = "flowError")
		public void flowError() {
			throw new IllegalStateException("boom");
		}
	}

	@Autowired
	TelemetryIntegrationBootTestService service;

	@Autowired
	RecordingReceiver receiver;

	@Autowired
	GlobalFallbackReceiver fallback;

	@BeforeEach
	void reset() {
		fallback.starts.clear();
		fallback.finishes.clear();
		fallback.rootBatches.clear();
		fallback.finishCustomTags.clear();
		receiver.normalOnErrorFinishes.clear();
		receiver.alwaysOnErrorFinishes.clear();
		receiver.errorFinishes.clear();
		receiver.capturedErrors.clear();
	}

	@Test
	@DisplayName(
			"Flow + Step: step holder seen by handlers has attr+context; parent flow keeps them only on folded OEvent")
	void stepWritesAttrAndContext_FlowHasThemOnlyOnEvent() {
		String out = service.flowA();
		assertThat(out).isEqualTo("ok");

		assertThat(fallback.starts).hasSize(2);
		assertThat(fallback.finishes).hasSize(2);

		TelemetryHolder stepFinish = fallback.finishes.stream()
				.filter(TelemetryHolder::isStep)
				.findFirst()
				.orElseThrow();
		TelemetryHolder flowFinish =
				fallback.finishes.stream().filter(h -> !h.isStep()).findFirst().orElseThrow();

		assertThat(stepFinish.isStep()).isTrue();
		assertThat(stepFinish.attributes().map()).containsEntry("step.flag", true);
		assertThat(stepFinish.getEventContext()).containsEntry("step.ctx", "ctx-value");

		assertThat(flowFinish.isStep()).isFalse();
		assertThat(flowFinish.attributes().map()).doesNotContainKey("step.flag");
		assertThat(flowFinish.getEventContext().get("step.ctx")).isNull();

		OEvent stepEvent = flowFinish.events().stream()
				.filter(e -> "stepB".equals(e.name()))
				.findFirst()
				.orElseThrow();
		assertThat(stepEvent.attributes().map()).containsEntry("step.flag", true);
		assertThat(stepEvent.eventContext()).containsEntry("step.ctx", "ctx-value");
		assertThat(stepEvent.epochNanos()).isPositive();
		assertThat(stepEvent.endEpochNanos()).isNotNull();
	}

	@Test
	@DisplayName("Lonely step with @AutoFlow is promoted to a root flow and has attributes")
	void lonelyStepWithAutoFlowIsPromotedAndHasFlowAttrs() {
		service.lonelyStep();

		assertThat(fallback.starts).hasSize(1);
		assertThat(fallback.finishes).hasSize(1);

		TelemetryHolder start = fallback.starts.get(0);
		assertThat(start.name()).isEqualTo("lonelyStep");
		assertThat(start.kind()).isEqualTo(SpanKind.PRODUCER);
		assertTraceAndSpanIds(start);
		assertThat(start.parentSpanId()).isNull();

		Map<String, Object> attrs = start.attributes().map();
		log.info("lonelyStep attributes: {}", attrs);
		assertThat(attrs).containsEntry("test.flow", "lonelyStep").containsEntry("declaring.method", "lonelyStep");
		assertThat(attrs.get("custom.tag")).isInstanceOf(CustomTag.class);
		assertThat(((CustomTag) attrs.get("custom.tag")).value()).isEqualTo("integration");
	}

	@Test
	@DisplayName("Method-level @Kind overrides class-level kind; attributes present")
	void methodLevelKindOverridesClassKindAndAttrsPresent() {
		service.flowClient();
		assertThat(fallback.starts).hasSize(1);
		TelemetryHolder h = fallback.starts.get(0);
		assertThat(h.kind()).isEqualTo(SpanKind.CLIENT);

		Map<String, Object> attrs = h.attributes().map();
		log.info("flowClient attributes: {}", attrs);
		assertThat(attrs).containsEntry("test.flow", "flowClient").containsEntry("declaring.method", "flowClient");
	}

	@Test
	@DisplayName("Root flow batch is in execution order with nested flow and attributes present")
	void rootFlowBatchContainsAllFlowsInExecutionOrderAndAttrsPresent() {
		service.rootFlow();

		assertThat(fallback.rootBatches).hasSize(1);
		List<TelemetryHolder> batch = fallback.rootBatches.get(0);
		log.info(
				"rootFlow batch (size={}): {}",
				batch.size(),
				batch.stream().map(TelemetryHolder::name).toList());

		assertThat(batch).hasSize(2);
		TelemetryHolder first = batch.get(0);
		TelemetryHolder second = batch.get(1);

		assertThat(first.name()).isEqualTo("rootFlow");
		assertThat(second.name()).isEqualTo("nestedFlow");
		assertThat(first.parentSpanId()).isNull();
		assertThat(second.parentSpanId()).isNotNull();

		Map<String, Object> firstAttrs = first.attributes().map();
		Map<String, Object> secondAttrs = second.attributes().map();
		log.info("rootFlow attrs: {}", firstAttrs);
		log.info("nestedFlow attrs: {}", secondAttrs);

		assertThat(firstAttrs).containsEntry("test.flow", "rootFlow");
		assertThat(secondAttrs).containsEntry("test.flow", "nestedFlow");
	}

	@Test
	@DisplayName("Exception dispatch: FAILURE only; SUCCESS handlers are skipped")
	void exceptionDispatchesFailureOnly() {
		assertThatThrownBy(() -> service.flowError())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("boom");

		// FAILURE-specific handler(s) should run (including most specific by type)
		assertThat(receiver.errorFinishes).hasSize(1);
		assertThat(receiver.capturedErrors).hasSize(1);
		assertThat(receiver.capturedErrors.get(0)).isInstanceOf(IllegalStateException.class);

		// No success handlers should run on failure
		assertThat(receiver.alwaysOnErrorFinishes).isEmpty();
		assertThat(receiver.normalOnErrorFinishes).isEmpty();
	}

	/* helpers */
	private static final Pattern HEX_32 = Pattern.compile("^[0-9a-f]{32}$");
	private static final Pattern HEX_16 = Pattern.compile("^[0-9a-f]{16}$");

	private static void assertTraceAndSpanIds(TelemetryHolder h) {
		assertThat(h.traceId()).matches(HEX_32);
		assertThat(h.spanId()).matches(HEX_16);
	}
}
