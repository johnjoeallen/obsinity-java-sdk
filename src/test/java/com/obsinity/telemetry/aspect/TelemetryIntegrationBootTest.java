package com.obsinity.telemetry.aspect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import io.opentelemetry.api.trace.SpanKind;
import com.obsinity.telemetry.annotations.AutoFlow;
import com.obsinity.telemetry.annotations.BindEventAttribute;
import com.obsinity.telemetry.annotations.Flow;
import com.obsinity.telemetry.annotations.Kind;
import com.obsinity.telemetry.annotations.OnEvent;
import com.obsinity.telemetry.annotations.Step;
import com.obsinity.telemetry.annotations.TelemetryEventHandler;
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
		classes = TelemetryIntegrationBootTest.TestApp.class,
		webEnvironment = SpringBootTest.WebEnvironment.NONE,
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
		RecordingReceiver recordingReceiver() {
			return new RecordingReceiver();
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
		TelemetryEventHandlerScanner telemetryEventHandlerScanner() {
			return new TelemetryEventHandlerScanner();
		}

		/** Build the dispatch bus that routes to @OnEvent handlers annotated with @TelemetryEventHandler. */
		@Bean
		TelemetryDispatchBus telemetryDispatchBus(
				ListableBeanFactory beanFactory, TelemetryEventHandlerScanner scanner) {
			return new TelemetryDispatchBus(beanFactory, scanner);
		}

		@Bean
		TelemetryProcessor telemetryProcessor(
				TelemetryAttributeBinder binder, TelemetryProcessorSupport support, TelemetryDispatchBus dispatchBus) {
			return new TelemetryProcessor(binder, support, dispatchBus) {
				@Override
				protected OAttributes buildAttributes(org.aspectj.lang.ProceedingJoinPoint pjp, FlowOptions opts) {
					// Base attributes for the flow
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("test.flow", opts.name());
					m.put("declaring.class", pjp.getSignature().getDeclaringTypeName());
					m.put("declaring.method", pjp.getSignature().getName());
					// store an OBJECT, not a String
					m.put("custom.tag", new CustomTag("integration"));

					// Create OAttributes, then merge @Attribute-annotated parameters from the join point
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
	}

	/**
	 * Test “receiver” implemented as an annotation-driven handler. Collects starts, finishes, root batches, and
	 * demonstrates an @OnEvent method that takes an OBJECT from flow attributes.
	 */
	@TelemetryEventHandler
	static class RecordingReceiver {
		final List<TelemetryHolder> starts = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> finishes = new CopyOnWriteArrayList<>();
		final List<List<TelemetryHolder>> rootBatches = new CopyOnWriteArrayList<>();
		final List<CustomTag> finishCustomTags = new CopyOnWriteArrayList<>();

		@OnEvent(lifecycle = {Lifecycle.FLOW_STARTED})
		public void onStart(TelemetryHolder holder) {
			starts.add(holder);
		}

		@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})
		public void onFinish(TelemetryHolder holder) {
			finishes.add(holder);
		}

		/**
		 * Attribute-bound parameter: injects "custom.tag" as a CustomTag object from the finished flow's attributes.
		 * (Will run only for flows that actually carry the attribute.)
		 */
		@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})
		public void onFinishCustomTag(@BindEventAttribute(name = "custom.tag") CustomTag customTag) {
			finishCustomTags.add(customTag);
		}

		@OnEvent(lifecycle = {Lifecycle.ROOT_FLOW_FINISHED})
		public void onRoot(List<TelemetryHolder> batch) {
			rootBatches.add(batch);
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
			telemetry.putAttr("step.flag", true); // persisted
			telemetry.putContext("step.ctx", "ctx-value"); // ephemeral
		}

		@Kind(SpanKind.PRODUCER)
		@Step(name = "lonelyStep")
		@AutoFlow
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

		// Example method showing how @Attribute on params would flow into OAttributes via binder
		@Flow(name = "paramFlowExample")
		public void paramFlowExample(
				@BindEventAttribute(name = "user.id") String userId,
				@BindEventAttribute(name = "flags") Map<String, Object> flags) {
			/* no-op */
		}
	}

	@Autowired
	TelemetryIntegrationBootTestService service;

	@Autowired
	RecordingReceiver receiver;

	@BeforeEach
	void reset() {
		receiver.starts.clear();
		receiver.finishes.clear();
		receiver.rootBatches.clear();
		receiver.finishCustomTags.clear();
	}

	@Test
	@DisplayName(
			"Flow + Step: step holder seen by handlers has attr+context; parent flow keeps them only on folded OEvent")
	void stepWritesAttrAndContext_FlowHasThemOnlyOnEvent() {
		String out = service.flowA();
		assertThat(out).isEqualTo("ok");

		assertThat(receiver.starts).hasSize(2);
		assertThat(receiver.finishes).hasSize(2);

		TelemetryHolder stepFinish = receiver.finishes.stream()
				.filter(TelemetryHolder::isStep)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Expected step finish holder"));
		TelemetryHolder flowFinish = receiver.finishes.stream()
				.filter(h -> !h.isStep())
				.findFirst()
				.orElseThrow(() -> new AssertionError("Expected flow finish holder"));

		assertThat(stepFinish.isStep()).isTrue();
		assertThat(stepFinish.attributes().asMap()).containsEntry("step.flag", true);
		assertThat(stepFinish.getEventContext().get("step.ctx")).isEqualTo("ctx-value");

		assertThat(flowFinish.isStep()).isFalse();
		assertThat(flowFinish.attributes().asMap()).doesNotContainKey("step.flag");
		assertThat(flowFinish.getEventContext().get("step.ctx")).isNull();

		OEvent stepEvent = flowFinish.events().stream()
				.filter(e -> "stepB".equals(e.name()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Expected folded stepB event on flow holder"));

		assertThat(stepEvent.attributes().asMap()).containsEntry("step.flag", true);
		assertThat(stepEvent.eventContext().get("step.ctx")).isEqualTo("ctx-value");
		assertThat(stepEvent.epochNanos()).isGreaterThan(0L);
		assertThat(stepEvent.endEpochNanos()).isNotNull();
	}

	@Test
	@DisplayName("Lonely step with @AutoFlow is promoted to a root flow and has attributes")
	void lonelyStepWithAutoFlowIsPromotedAndHasFlowAttrs() {
		service.lonelyStep();

		assertThat(receiver.starts).hasSize(1);
		assertThat(receiver.finishes).hasSize(1);

		TelemetryHolder start = receiver.starts.get(0);
		assertThat(start.name()).isEqualTo("lonelyStep");
		assertThat(start.kind()).isEqualTo(SpanKind.PRODUCER);
		assertTraceAndSpanIds(start);
		assertThat(start.parentSpanId()).isNull();

		Map<String, Object> attrs = start.attributes().asMap();
		log.info("lonelyStep attributes: {}", attrs);
		assertThat(attrs).containsEntry("test.flow", "lonelyStep").containsEntry("declaring.method", "lonelyStep");

		assertThat(attrs.get("custom.tag")).isInstanceOf(CustomTag.class);
		assertThat(((CustomTag) attrs.get("custom.tag")).value()).isEqualTo("integration");
	}

	@Test
	@DisplayName("Method-level @Kind overrides class-level kind; attributes present")
	void methodLevelKindOverridesClassKindAndAttrsPresent() {
		service.flowClient();
		assertThat(receiver.starts).hasSize(1);
		TelemetryHolder h = receiver.starts.get(0);
		assertThat(h.kind()).isEqualTo(SpanKind.CLIENT);

		Map<String, Object> attrs = h.attributes().asMap();
		log.info("flowClient attributes: {}", attrs);
		assertThat(attrs).containsEntry("test.flow", "flowClient").containsEntry("declaring.method", "flowClient");
	}

	@Test
	@DisplayName("Root flow batch is in execution order with nested flow and attributes present")
	void rootFlowBatchContainsAllFlowsInExecutionOrderAndAttrsPresent() {
		service.rootFlow();

		assertThat(receiver.rootBatches).hasSize(1);
		List<TelemetryHolder> batch = receiver.rootBatches.get(0);
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

		Map<String, Object> firstAttrs = first.attributes().asMap();
		Map<String, Object> secondAttrs = second.attributes().asMap();
		log.info("rootFlow attrs: {}", firstAttrs);
		log.info("nestedFlow attrs: {}", secondAttrs);

		assertThat(firstAttrs).containsEntry("test.flow", "rootFlow");
		assertThat(secondAttrs).containsEntry("test.flow", "nestedFlow");
	}

	@Test
	@DisplayName("@Attribute parameters are bound into flow attributes")
	void paramFlowExampleBindsAttributes() {
		Map<String, Object> flags = new LinkedHashMap<>();
		flags.put("flag.one", true);
		flags.put("flag.two", "yes");

		service.paramFlowExample("user-123", flags);

		assertThat(receiver.starts).hasSize(1);
		TelemetryHolder start = receiver.starts.get(0);

		Map<String, Object> attrs = start.attributes().asMap();
		log.info("paramFlowExample attributes: {}", attrs);

		assertThat(attrs)
				.containsEntry("user.id", "user-123")
				.containsEntry("flags", flags)
				.containsEntry("test.flow", "paramFlowExample")
				.containsEntry("declaring.method", "paramFlowExample");
	}

	@Test
	@DisplayName("@OnEvent handler can take an OBJECT sourced from attributes")
	void onEventHandlerReceivesObjectFromAttributes() {
		service.flowA();

		var nonNullTags =
				receiver.finishCustomTags.stream().filter(Objects::nonNull).toList();

		assertThat(nonNullTags)
				.as("Expected one non-null CustomTag from flow finish")
				.hasSize(1);
		assertThat(nonNullTags.get(0).value()).isEqualTo("integration");
	}

	/* helpers */
	private static final Pattern HEX_32 = Pattern.compile("^[0-9a-f]{32}$");
	private static final Pattern HEX_16 = Pattern.compile("^[0-9a-f]{16}$");

	private static void assertTraceAndSpanIds(TelemetryHolder h) {
		assertThat(h.traceId()).matches(HEX_32);
		assertThat(h.spanId()).matches(HEX_16);
	}
}
