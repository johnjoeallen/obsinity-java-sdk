package com.obsinity.telemetry.aspects;

import com.obsinity.telemetry.annotations.AutoFlow;
import com.obsinity.telemetry.annotations.Flow;
import com.obsinity.telemetry.annotations.Step;
import com.obsinity.telemetry.processors.TelemetryProcessor;
import org.apache.logging.log4j.spi.StandardLevel;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.lang.reflect.Method;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;

@SpringBootTest(
	classes = TelemetryAspectParameterizedTest.TestConfig.class,
	properties = "spring.main.web-application-type=none"
)
@DisplayName("TelemetryAspect: parameterized advice verification")
class TelemetryAspectParameterizedTest {

	@MockitoSpyBean
	private TelemetryAspect telemetryAspect;

	@MockitoBean
	TelemetryProcessor telemetryProcessor;

	@Autowired
	private TelemetryAspectTestService testService;

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

	@ParameterizedTest(name = "{index} → {0}")
	@MethodSource("cases")
	@DisplayName("Advice runs and forwards FlowOptions to TelemetryProcessor")
	void adviceRunsAndForwardsFlowOptions(TestCase tc) throws Throwable {
		// GIVEN
		FlowOptions expected =
			FlowOptionsFactory.fromClassAndMethod(TelemetryAspectTestService.class, tc.methodName);

		Mockito.when(telemetryProcessor.proceed(any(ProceedingJoinPoint.class), eq(expected)))
			.thenReturn("ok");

		// WHEN
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
		@Override public String toString() { return methodName; }
	}

	@TestConfiguration
	@EnableAspectJAutoProxy(exposeProxy = true, proxyTargetClass = true)
	@Import(TelemetryAspect.class)
	static class TestConfig {
		@Bean
		TelemetryAspectTestService telemetryAspectTestService() {
			return new TelemetryAspectTestService();
		}
	}

	/** Methods under test; exposed only as a @Bean, not via component scan. */
	static class TelemetryAspectTestService {

		@Flow(name = "doFlow" /*, description = "Root flow method"*/)
		public String doFlow() { return "ok"; }

		@Step(name = "doStep" /*, description = "Simple step inside active flow"*/)
		public String doStep() { return "ok"; }

		@Step(name = "doStep" /*, description = "Step that can auto-start a flow if none active"*/)
		@AutoFlow
		public String doStepAutoFlow() { return "ok"; }

		@Step(name = "doStep" /*, description = "AutoFlow with WARN level"*/)
		@AutoFlow(level = StandardLevel.WARN)
		public String doStepAutoFlowWWarn() { return "ok"; }
	}
}
