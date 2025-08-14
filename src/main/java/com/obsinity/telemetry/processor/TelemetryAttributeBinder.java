package com.obsinity.telemetry.processor;

import org.aspectj.lang.JoinPoint;
import org.springframework.stereotype.Component;

import com.obsinity.telemetry.model.OAttributes;
import com.obsinity.telemetry.model.OEvent;
import com.obsinity.telemetry.model.TelemetryHolder;

@Component
public class TelemetryAttributeBinder {

	private final AttributeParamExtractor extractor;

	public TelemetryAttributeBinder(AttributeParamExtractor extractor) {
		this.extractor = extractor;
	}

	/** Entry point for TelemetryHolder-based pipelines. */
	public void bind(TelemetryHolder holder, JoinPoint jp) {
		if (holder == null) return;
		OAttributes attrs = holder.attributes(); // or holder.attributes()
		extractor.extractTo(attrs, jp);
	}

	/** Entry point for OEvent-based pipelines. */
	public void bind(OEvent event, JoinPoint jp) {
		if (event == null) return;
		OAttributes attrs = event.attributes(); // or event.attributes()
		extractor.extractTo(attrs, jp);
	}

	public void bind(OAttributes attrs, JoinPoint jp) {
		extractor.extractTo(attrs, jp);
	}
}
