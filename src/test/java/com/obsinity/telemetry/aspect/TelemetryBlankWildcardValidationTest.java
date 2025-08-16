package com.obsinity.telemetry.aspect;

import com.obsinity.telemetry.annotations.BindEventThrowable;
import com.obsinity.telemetry.annotations.DispatchMode;
import com.obsinity.telemetry.annotations.OnEvent;
import com.obsinity.telemetry.annotations.TelemetryEventHandler;
import com.obsinity.telemetry.dispatch.TelemetryEventHandlerScanner;
import com.obsinity.telemetry.model.TelemetryHolder;
import com.obsinity.telemetry.processor.AttributeParamExtractor;
import com.obsinity.telemetry.processor.TelemetryAttributeBinder;
import com.obsinity.telemetry.processor.TelemetryProcessorSupport;
import com.obsinity.telemetry.receivers.TelemetryDispatchBus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelemetryBlankWildcardValidationTest {

	/** One handler class declaring TWO blank-wildcard ERROR methods → should fail. */
	@TelemetryEventHandler
	static class BadCatchAllDuplicate {
		@OnEvent(mode = DispatchMode.ERROR)
		public void e1(@BindEventThrowable Exception ex, TelemetryHolder h) {}

		@OnEvent(mode = DispatchMode.ERROR)
		public void e2(@BindEventThrowable Exception ex, TelemetryHolder h) {}
	}

	@Test
	@DisplayName("Declaring multiple blank wildcard ERROR handlers in the SAME class fails validation")
	void duplicateBlankWildcardInOneHandlerFails() {
		var ctx = new GenericApplicationContext();

		// minimal supporting beans (disambiguate registerBean by providing Supplier)
		ctx.registerBean("objectMapper",
			com.fasterxml.jackson.databind.ObjectMapper.class);
		ctx.registerBean("attributeParamExtractor",
			AttributeParamExtractor.class,
			AttributeParamExtractor::new);
		ctx.registerBean("telemetryAttributeBinder",
			TelemetryAttributeBinder.class,
			() -> new TelemetryAttributeBinder(ctx.getBean(AttributeParamExtractor.class)));
		ctx.registerBean("telemetryProcessorSupport",
			TelemetryProcessorSupport.class,
			TelemetryProcessorSupport::new);
		ctx.registerBean("telemetryEventHandlerScanner",
			TelemetryEventHandlerScanner.class,
			TelemetryEventHandlerScanner::new);

		// register the bad handler bean
		ctx.registerBean("badCatchAllDuplicate", BadCatchAllDuplicate.class);

		ctx.refresh();

		assertThatThrownBy(() ->
			new TelemetryDispatchBus(ctx, ctx.getBean(TelemetryEventHandlerScanner.class))
		)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Multiple blank wildcard ERROR handlers")
			.hasMessageContaining(BadCatchAllDuplicate.class.getName());
	}
}
