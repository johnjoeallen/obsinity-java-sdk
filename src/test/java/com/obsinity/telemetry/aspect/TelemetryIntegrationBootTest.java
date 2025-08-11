package com.obsinity.telemetry.aspect;

import com.obsinity.telemetry.annotations.AutoFlow;
import com.obsinity.telemetry.annotations.Flow;
import com.obsinity.telemetry.annotations.Kind;
import com.obsinity.telemetry.annotations.Step;
import com.obsinity.telemetry.annotations.Attribute;
import com.obsinity.telemetry.model.TelemetryHolder;
import com.obsinity.telemetry.processor.AttributeParamExtractor;
import com.obsinity.telemetry.processor.TelemetryAttributeBinder;
import com.obsinity.telemetry.processor.TelemetryProcessor;
import com.obsinity.telemetry.processor.TelemetryProcessorSupport;
import com.obsinity.telemetry.receivers.TelemetryReceiver;
import io.opentelemetry.api.trace.SpanKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
	classes = TelemetryIntegrationBootTest.TestApp.class,
	webEnvironment = SpringBootTest.WebEnvironment.NONE,
	properties = {
		"spring.main.web-application-type=none"
	}
)
class TelemetryIntegrationBootTest {

	private static final Logger log = LoggerFactory.getLogger(TelemetryIntegrationBootTest.class);

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
	static class TestApp {
		@Bean
		com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
			return new com.fasterxml.jackson.databind.ObjectMapper();
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
		TelemetryProcessor telemetryProcessor(TelemetryAttributeBinder binder,
											  TelemetryProcessorSupport support,
											  List<TelemetryReceiver> receivers) {
			return new TelemetryProcessor(binder, support, receivers) {
				@Override
				protected TelemetryHolder.OAttributes buildAttributes(org.aspectj.lang.ProceedingJoinPoint pjp, FlowOptions opts) {
					// Base attributes for the flow
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("test.flow", opts.name());
					m.put("declaring.class", pjp.getSignature().getDeclaringTypeName());
					m.put("declaring.method", pjp.getSignature().getName());
					m.put("custom.tag", "integration");

					// Create OAttributes, then merge @Attribute-annotated parameters from the join point
					TelemetryHolder.OAttributes attrs = new TelemetryHolder.OAttributes(m);
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
		TelemetryIntegrationBootTestService telemetryIntegrationBootTestService() {
			return new TelemetryIntegrationBootTestService();
		}
	}

	static class RecordingReceiver implements TelemetryReceiver {
		final List<TelemetryHolder> starts = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> finishes = new CopyOnWriteArrayList<>();
		final List<List<TelemetryHolder>> rootBatches = new CopyOnWriteArrayList<>();

		@Override
		public void flowStarted(TelemetryHolder holder) {
			starts.add(holder);
		}

		@Override
		public void flowFinished(TelemetryHolder holder) {
			finishes.add(holder);
		}

		@Override
		public void rootFlowFinished(List<TelemetryHolder> batch) {
			rootBatches.add(batch);
		}
	}

	@Kind(SpanKind.SERVER)
	static class TelemetryIntegrationBootTestService {
		@Flow(name = "flowA")
		public String flowA() {
			((TelemetryIntegrationBootTestService) AopContext.currentProxy()).stepB();
			return "ok";
		}

		@Step(name = "stepB")
		public void stepB() { /* no-op */ }

		@Kind(SpanKind.PRODUCER)
		@Step(name = "lonelyStep")
		@AutoFlow
		public void lonelyStep() { /* no-op */ }

		@Kind(SpanKind.CLIENT)
		@Flow(name = "flowClient")
		public void flowClient() { /* no-op */ }

		@Flow(name = "rootFlow")
		public void rootFlow() {
			((TelemetryIntegrationBootTestService) AopContext.currentProxy()).nestedFlow();
		}

		@Flow(name = "nestedFlow")
		public void nestedFlow() { /* no-op */ }

		// Example method showing how @Attribute on params would flow into OAttributes via binder
		@Flow(name = "paramFlowExample")
		public void paramFlowExample(
			@Attribute(name = "user.id") String userId,
			@Attribute(name = "flags") Map<String, Object> flags
		) { /* no-op */ }
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
	}

	@Test
	@DisplayName("Flow with nested step emits single start/finish; event recorded with attributes")
	void flowWithNestedStepEmitsSingleStartAndFinishAndRecordsEventAttrs() {
		String out = service.flowA();
		assertThat(out).isEqualTo("ok");

		assertThat(receiver.starts).hasSize(1);
		assertThat(receiver.finishes).hasSize(1);

		TelemetryHolder start = receiver.starts.get(0);
		TelemetryHolder finish = receiver.finishes.get(0);

		assertThat(start.name()).isEqualTo("flowA");
		assertThat(start.kind()).isEqualTo(SpanKind.SERVER);
		assertTraceAndSpanIds(start);

		assertThat(finish.traceId()).isEqualTo(start.traceId());
		assertThat(finish.spanId()).isEqualTo(start.spanId());
		assertThat(finish.endTimestamp()).isNotNull();
		assertThat(finish.timestamp()).isBeforeOrEqualTo(Instant.now());

		Map<String, Object> flowAttrs = finish.attributes().asMap();
		log.info("flowA attributes: {}", flowAttrs);
		assertThat(flowAttrs)
			.containsEntry("test.flow", "flowA")
			.containsEntry("declaring.method", "flowA")
			.containsEntry("custom.tag", "integration");

		assertThat(finish.events()).isNotNull().isNotEmpty();

		TelemetryHolder.OEvent stepEvent = finish.events().stream()
			.filter(e -> "stepB".equals(e.name()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("Expected stepB event"));

		Map<String, Object> ev = stepEvent.attributes().asMap();
		log.info("stepB event attributes: {}", ev);
		assertThat(ev)
			.containsEntry("phase", "finish")
			.containsEntry("result", "success")
			.containsEntry("class", TelemetryIntegrationBootTestService.class.getName())
			.containsEntry("method", "stepB");
		assertThat((Long) ev.get("duration.nanos")).isNotNull().isGreaterThanOrEqualTo(0L);

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
		assertThat(attrs)
			.containsEntry("test.flow", "lonelyStep")
			.containsEntry("declaring.method", "lonelyStep")
			.containsEntry("custom.tag", "integration");
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
		assertThat(attrs)
			.containsEntry("test.flow", "flowClient")
			.containsEntry("declaring.method", "flowClient");
	}

	@Test
	@DisplayName("Root flow batch is in execution order with nested flow and attributes present")
	void rootFlowBatchContainsAllFlowsInExecutionOrderAndAttrsPresent() {
		service.rootFlow();

		assertThat(receiver.rootBatches).hasSize(1);
		List<TelemetryHolder> batch = receiver.rootBatches.get(0);
		log.info("rootFlow batch (size={}): {}", batch.size(), batch.stream().map(TelemetryHolder::name).toList());

		// Execution/start order (root, then nested)
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
			// Still has the test attributes from buildAttributes()
			.containsEntry("test.flow", "paramFlowExample")
			.containsEntry("declaring.method", "paramFlowExample");
	}

	/* helpers */
	private static final Pattern HEX_32 = Pattern.compile("^[0-9a-f]{32}$");
	private static final Pattern HEX_16 = Pattern.compile("^[0-9a-f]{16}$");

	private static void assertTraceAndSpanIds(TelemetryHolder h) {
		assertThat(h.traceId()).matches(HEX_32);
		assertThat(h.spanId()).matches(HEX_16);
	}
}
