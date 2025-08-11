package com.obsinity.telemetry.processor;

import com.obsinity.telemetry.model.TelemetryHolder;
import org.aspectj.lang.JoinPoint;
import org.springframework.stereotype.Component;

@Component
public class TelemetryAttributeBinder {

	private final AttributeParamExtractor extractor;

	public TelemetryAttributeBinder(AttributeParamExtractor extractor) {
		this.extractor = extractor;
	}

	/** Entry point for TelemetryHolder-based pipelines. */
	public void bind(TelemetryHolder holder, JoinPoint jp) {
		if (holder == null) return;
		TelemetryHolder.OAttributes attrs = holder.attributes(); // or holder.attributes()
		extractor.extractTo(attrs, jp);
	}

	/** Entry point for OEvent-based pipelines. */
	public void bind(TelemetryHolder.OEvent event, JoinPoint jp) {
		if (event == null) return;
		TelemetryHolder.OAttributes attrs = event.attributes(); // or event.attributes()
		extractor.extractTo(attrs, jp);
	}

	public void bind(TelemetryHolder.OAttributes attrs, JoinPoint jp) {
		extractor.extractTo(attrs, jp);
	}
}
