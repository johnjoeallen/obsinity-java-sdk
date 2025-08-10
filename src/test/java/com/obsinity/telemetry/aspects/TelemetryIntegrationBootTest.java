package com.obsinity.telemetry.aspects;

import com.obsinity.telemetry.annotations.AutoFlow;
import com.obsinity.telemetry.annotations.Flow;
import com.obsinity.telemetry.annotations.Kind;
import com.obsinity.telemetry.annotations.Step;
import com.obsinity.telemetry.model.TelemetryHolder;
import com.obsinity.telemetry.processors.TelemetryProcessor;
import com.obsinity.telemetry.receivers.TelemetryReceiver;
import io.opentelemetry.api.trace.SpanKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
	classes = TelemetryIntegrationBootTest.TestApp.class,
	webEnvironment = SpringBootTest.WebEnvironment.NONE,
	properties = {
		"spring.main.web-application-type=none",
		// Optional safety net if another name sneaks in:
		// "spring.main.allow-bean-definition-overriding=true"
	}
)
class TelemetryIntegrationBootTest {

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EnableAspectJAutoProxy(proxyTargetClass = true)
	@ComponentScan(basePackages = "com.obsinity.telemetry.receivers")
	static class TestApp {
		@Bean
		com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
			return new com.fasterxml.jackson.databind.ObjectMapper();
		}

		@Bean
		RecordingReceiver recordingReceiver() {
			return new RecordingReceiver();
		}

		// Wire the processor explicitly; it depends on List<TelemetryReceiver>
		@Bean
		TelemetryProcessor telemetryProcessor(List<TelemetryReceiver> receivers) {
			return new TelemetryProcessor(receivers);
		}

		// Import the aspect explicitly (no component scan)
		@Bean
		TelemetryAspect telemetryAspect(TelemetryProcessor p) {
			return new TelemetryAspect(p);
		}

		// Plain POJO test service (NOT @Component); no name collision
		@Bean
		TestService testService() {
			return new TestService();
		}
	}

	/**
	 * Receiver that records starts/finishes for assertions.
	 */
	static class RecordingReceiver implements TelemetryReceiver {
		final List<TelemetryHolder> starts = new CopyOnWriteArrayList<>();
		final List<TelemetryHolder> finishes = new CopyOnWriteArrayList<>();

		@Override
		public void flowStarted(TelemetryHolder holder) {
			starts.add(holder);
		}

		@Override
		public void flowFinished(TelemetryHolder holder) {
			finishes.add(holder);
		}
	}

	// Not a component; registered via @Bean above
	@Kind(SpanKind.SERVER) // class-level default
	static class TestService {
		@Flow(name = "flowA")
		public String flowA() {
			stepB();
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
	}

	@Autowired
	TestService service;
	@Autowired
	RecordingReceiver receiver;

	@BeforeEach
	void reset() {
		receiver.starts.clear();
		receiver.finishes.clear();
	}

	@Test
	void flow_with_nested_step_emits_single_start_and_finish() {
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
	}

	@Test
	void lonely_step_with_autoflow_is_promoted_and_notifies_start_finish() {
		service.lonelyStep();

		assertThat(receiver.starts).hasSize(1);
		assertThat(receiver.finishes).hasSize(1);

		TelemetryHolder start = receiver.starts.get(0);
		assertThat(start.name()).isEqualTo("lonelyStep");
		assertThat(start.kind()).isEqualTo(SpanKind.PRODUCER);
		assertTraceAndSpanIds(start);
		assertThat(start.parentSpanId()).isNull(); // promoted root
	}

	@Test
	void method_level_kind_overrides_class_kind() {
		service.flowClient();
		assertThat(receiver.starts).hasSize(1);
		assertThat(receiver.starts.get(0).kind()).isEqualTo(SpanKind.CLIENT);
	}

	/* helpers */
	private static final Pattern HEX_32 = Pattern.compile("^[0-9a-f]{32}$");
	private static final Pattern HEX_16 = Pattern.compile("^[0-9a-f]{16}$");

	private static void assertTraceAndSpanIds(TelemetryHolder h) {
		assertThat(h.traceId()).matches(HEX_32);
		assertThat(h.spanId()).matches(HEX_16);
	}
}
