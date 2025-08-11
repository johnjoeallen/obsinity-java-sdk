package com.obsinity.telemetry.aspects;

import com.obsinity.telemetry.annotations.AutoFlow;
import com.obsinity.telemetry.annotations.Flow;
import com.obsinity.telemetry.annotations.Step;
import com.obsinity.telemetry.processors.TelemetryProcessor;
import org.apache.logging.log4j.spi.StandardLevel;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.lang.reflect.Method;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;

/**
 * Parameterized verification for Obsinity’s Java SDK aspect-driven telemetry.
 *
 * <p>This test exercises the SDK’s annotation model—{@link Flow}, {@link Step}, and {@link AutoFlow}—
 * which lets applications declare telemetry at method boundaries without creating a Java class per
 * event type. The {@link TelemetryAspect} recognizes these annotations at runtime and forwards a
 * derived {@link FlowOptions} to the pluggable {@link TelemetryProcessor}.</p>
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li><b>Flow</b> — {@link Flow}-annotated entry point.</li>
 *   <li><b>Step</b> — {@link Step} within an active flow.</li>
 *   <li><b>Step + AutoFlow (default ERROR)</b> — {@link Step} that may start its own flow when none is active.</li>
 *   <li><b>Step + AutoFlow(WARN)</b> — same as above with {@link AutoFlow#level()} set to WARN.</li>
 * </ul>
 */
@SpringBootTest
@ContextConfiguration(classes = TelemetryAspectParameterizedTest.TestConfig.class)
class TelemetryAspectParameterizedTest {

	@MockitoSpyBean
	private TelemetryAspect telemetryAspect;

	@MockitoBean
	TelemetryProcessor telemetryProcessor;

	@Autowired
	private TestService testService;

	@BeforeEach
	void resetMocks() {
		Mockito.reset(telemetryAspect, telemetryProcessor);
	}

	static Stream<TestCase> cases() {
		return Stream.of(
			new TestCase("doFlow", true),
			new TestCase("doStep", false),
			new TestCase("doStepAutoFlow", false),
			new TestCase("doStepAutoFlowWWarn", false)
		);
	}

	@ParameterizedTest(name = "{index} -> {0}")
	@MethodSource("cases")
	void advice_runs_and_forwards_FlowOptions(TestCase tc) throws Throwable {
		// GIVEN: expected FlowOptions for this method
		FlowOptions expected = FlowOptionsFactory.fromClassAndMethod(TestService.class, tc.methodName);

		Mockito.when(telemetryProcessor.proceed(any(ProceedingJoinPoint.class), eq(expected)))
			.thenReturn("ok");

		// WHEN: invoke the method by name on the proxied bean (ensures aspect advice applies)
		Method m = testService.getClass().getMethod(tc.methodName);
		String out = (String) m.invoke(testService);

		// THEN
		assertThat(out).isEqualTo("ok");

		if (tc.isFlow) {
			Mockito.verify(telemetryAspect, times(1))
				.interceptFlow(any(ProceedingJoinPoint.class), any(Flow.class));
			Mockito.verify(telemetryAspect, times(0))
				.interceptStep(any(ProceedingJoinPoint.class), any(Step.class));
		} else {
			Mockito.verify(telemetryAspect, times(1))
				.interceptStep(any(ProceedingJoinPoint.class), any(Step.class));
			Mockito.verify(telemetryAspect, times(0))
				.interceptFlow(any(ProceedingJoinPoint.class), any(Flow.class));
		}
	}

	private record TestCase(String methodName, boolean isFlow) {
		@Override
		public String toString() {
			return methodName;
		}
	}

	@Configuration
	@EnableAspectJAutoProxy(proxyTargetClass = true)
	@Import({TelemetryAspect.class})
	static class TestConfig {
		@Bean
		TestService testService() {
			return new TestService();
		}
	}

	@Component
	static class TestService {
		@Flow(name = "doFlow")
		public String doFlow() {
			return "ok";
		}

		@Step(name = "doStep")
		public String doStep() {
			return "ok";
		}

		@Step(name = "doStep")
		@AutoFlow
		public String doStepAutoFlow() {
			return "ok";
		}

		@Step(name = "doStep")
		@AutoFlow(level = StandardLevel.WARN)
		public String doStepAutoFlowWWarn() {
			return "ok";
		}
	}
}
