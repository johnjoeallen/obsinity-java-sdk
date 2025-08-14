package com.obsinity.telemetry.processor;

import java.lang.reflect.Parameter;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import com.obsinity.telemetry.annotations.PushAttribute;
import com.obsinity.telemetry.annotations.PushContextValue;
import com.obsinity.telemetry.model.OAttributes;
import com.obsinity.telemetry.model.OEvent;
import com.obsinity.telemetry.model.TelemetryHolder;

/**
 * Binds method parameter annotations into telemetry:
 * <ul>
 *   <li>Delegates to {@link AttributeParamExtractor} for legacy attribute extraction.</li>
 *   <li>Additionally supports producer-side {@link PushAttribute} (persisted) and
 *       {@link PushContextValue} (ephemeral) annotations.</li>
 * </ul>
 */
@Component
public class TelemetryAttributeBinder {

	private final AttributeParamExtractor extractor;

	/**
	 * Optional runtime telemetry context. If null, context writes will fall back to the holder's eventContext map.
	 */
	@Nullable
	private final TelemetryContext telemetryContext;

	/**
	 * Backward-compatible constructor; context writes will fall back to the holder's eventContext map.
	 */
	public TelemetryAttributeBinder(AttributeParamExtractor extractor) {
		this(extractor, null);
	}

	/**
	 * Preferred constructor when you want {@link PushContextValue} to route via {@link TelemetryContext}.
	 */
	public TelemetryAttributeBinder(AttributeParamExtractor extractor, @Nullable TelemetryContext telemetryContext) {
		this.extractor = extractor;
		this.telemetryContext = telemetryContext;
	}

	/** Entry point for TelemetryHolder-based pipelines. */
	public void bind(TelemetryHolder holder, JoinPoint jp) {
		if (holder == null) return;

		// Legacy / existing behavior: extract attributes using the configured extractor.
		OAttributes attrs = holder.attributes();
		extractor.extractTo(attrs, jp);

		// New: apply @PushAttribute and @PushContextValue
		applyPushAnnotations(holder, attrs, jp);
	}

	/** Entry point for OEvent-based pipelines. */
	public void bind(OEvent event, JoinPoint jp) {
		if (event == null) return;

		// Legacy / existing behavior
		OAttributes attrs = event.attributes();
		extractor.extractTo(attrs, jp);

		// New: apply @PushAttribute. For @PushContextValue we attempt to use TelemetryContext if available.
		applyPushAnnotations(null, attrs, jp);
	}

	/** Generic entry when only attributes are available. */
	public void bind(OAttributes attrs, JoinPoint jp) {
		// Legacy / existing behavior
		extractor.extractTo(attrs, jp);

		// New: apply @PushAttribute (no holder present; context pushes can only go through TelemetryContext if provided)
		applyPushAnnotations(null, attrs, jp);
	}

	/* ================================== helpers ================================== */

	private void applyPushAnnotations(@Nullable TelemetryHolder holder, OAttributes attrs, JoinPoint jp) {
		if (!(jp.getSignature() instanceof MethodSignature ms)) return;

		Parameter[] params = ms.getMethod().getParameters();
		Object[] args = jp.getArgs();
		int n = Math.min(params.length, args != null ? args.length : 0);

		for (int i = 0; i < n; i++) {
			Parameter p = params[i];
			Object arg = args[i];

			// Persisted attributes (@PushAttribute)
			PushAttribute pushAttr = p.getAnnotation(PushAttribute.class);
			if (pushAttr != null) {
				if (!(pushAttr.omitIfNull() && arg == null)) {
					attrs.put(pushAttr.name(), arg);
				}
			}

			// Ephemeral context (@PushContextValue)
			PushContextValue pushCtx = p.getAnnotation(PushContextValue.class);
			if (pushCtx != null) {
				if (!(pushCtx.omitIfNull() && arg == null)) {
					// Prefer TelemetryContext if available, otherwise fall back to the holder's eventContext
					if (telemetryContext != null) {
						telemetryContext.putContext(pushCtx.value(), arg);
					} else if (holder != null) {
						holder.getEventContext().put(pushCtx.value(), arg);
					}
					// If neither telemetryContext nor holder is available (e.g., bind(OAttributes, jp)),
					// we simply can't place context — safe no-op.
				}
			}
		}
	}
}
