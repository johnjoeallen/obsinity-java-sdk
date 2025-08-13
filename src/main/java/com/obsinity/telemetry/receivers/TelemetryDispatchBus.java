package com.obsinity.telemetry.receivers;

import com.obsinity.telemetry.annotations.TelemetryEventHandler;
import com.obsinity.telemetry.dispatch.AttrBindingException;
import com.obsinity.telemetry.dispatch.BatchBinder;
import com.obsinity.telemetry.dispatch.Handler;
import com.obsinity.telemetry.dispatch.HolderBinder;
import com.obsinity.telemetry.dispatch.ParamBinder;
import com.obsinity.telemetry.dispatch.TelemetryEventHandlerScanner;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;
import io.opentelemetry.api.trace.SpanKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Event dispatcher that routes TelemetryHolder instances to @OnEvent methods
 * declared on beans annotated with @TelemetryEventHandler.
 */
@Component
public class TelemetryDispatchBus implements com.obsinity.telemetry.processor.TelemetryProcessorSupport.TelemetryEventDispatcher {

	private static final Logger log = LoggerFactory.getLogger(TelemetryDispatchBus.class);

	private final TelemetryEventHandlerScanner scanner;
	private final List<Handler> handlers;
	private final Map<Lifecycle, List<Handler>> byLifecycle;

	public TelemetryDispatchBus(ListableBeanFactory beanFactory, TelemetryEventHandlerScanner scanner) {
		this.scanner = scanner;
		// Find only beans marked with @TelemetryEventHandler
		Collection<Object> candidateBeans = beanFactory.getBeansWithAnnotation(TelemetryEventHandler.class).values();

		List<Handler> discovered = new ArrayList<>();
		for (Object bean : candidateBeans) {
			discovered.addAll(scanner.scan(bean));
		}
		this.handlers = List.copyOf(discovered);
		this.byLifecycle = indexByLifecycle(this.handlers);

		log.info("TelemetryDispatchBus registered {} handlers", this.handlers.size());
	}

	/* ================= Dispatch (single item) ================= */

	@Override
	public void dispatch(Lifecycle phase, TelemetryHolder holder) {
		if (holder == null) return;
		List<Handler> candidates = byLifecycle.getOrDefault(phase, List.of());
		if (candidates.isEmpty()) return;

		boolean any = false;
		for (Handler h : candidates) {
			if (!h.kindAccepts(nullToInternal(holder.getSpanKind()))) continue;
			if (!h.nameMatches(holder.getName())) continue;
			if (!throwableMatches(h, holder.getThrowable())) continue;

			// quick presence check for required attrs
			if (!h.requiredAttrs().isEmpty()) {
				List<String> missing = missingAttrs(holder, h.requiredAttrs());
				if (!missing.isEmpty()) {
					logMissing(h, holder, phase, missing);
					continue;
				}
			}

			Object[] args;
			try {
				args = bindParams(h.binders(), holder);
			} catch (AttrBindingException ex) {
				log.debug("Binding error for handler={} name={} phase={} key={}: {}",
					h.id(), holder.getName(), phase, ex.key(), ex.getMessage());
				continue;
			}

			try {
				Method m = h.method();
				if (!m.canAccess(h.bean())) m.setAccessible(true);
				m.invoke(h.bean(), args);
				any = true;
			} catch (Throwable t) {
				log.warn("Handler invocation failed handler={} name={} phase={}: {}",
					h.id(), holder.getName(), phase, t.toString());
			}
		}

		if (!any) {
			// optional: log at trace; counting can be done by metrics layer
			// log.trace("No handler accepted event name={} phase={}", holder.getName(), phase);
		}
	}

	/* ================= Dispatch (batch) ================= */

	@Override
	public void dispatchRootFinished(List<TelemetryHolder> batch) {
		if (batch == null || batch.isEmpty()) return;

		List<Handler> candidates = byLifecycle.getOrDefault(Lifecycle.ROOT_FLOW_FINISHED, List.of());
		if (candidates.isEmpty()) return;

		// Prefer batch-capable handlers (those with a BatchBinder)
		for (Handler h : candidates) {
			boolean isBatchCapable = h.binders().stream().anyMatch(b -> b instanceof BatchBinder);
			if (!isBatchCapable) continue;

			try {
				Object[] args = bindBatchParams(h.binders(), batch);
				Method m = h.method();
				if (!m.canAccess(h.bean())) m.setAccessible(true);
				m.invoke(h.bean(), args);
			} catch (AttrBindingException ex) {
				log.debug("Batch binding error handler={} phase=ROOT_FLOW_FINISHED key={}: {}",
					h.id(), ex.key(), ex.getMessage());
			} catch (Throwable t) {
				log.warn("Batch handler invocation failed handler={} phase=ROOT_FLOW_FINISHED: {}",
					h.id(), t.toString());
			}
		}

		// Optional: also fan out individual FLOW_FINISHED events for non-batch handlers
		for (TelemetryHolder h : batch) {
			dispatch(Lifecycle.FLOW_FINISHED, h);
		}
	}

	/* ================= Helpers ================= */

	private static Map<Lifecycle, List<Handler>> indexByLifecycle(List<Handler> handlers) {
		Map<Lifecycle, List<Handler>> map = new EnumMap<>(Lifecycle.class);
		for (Lifecycle lc : Lifecycle.values()) map.put(lc, new ArrayList<>());

		for (Handler h : handlers) {
			// If no lifecycle mask, handler applies to all phases
			boolean anyMask = (h.lifecycleMask() != null);
			if (!anyMask) {
				for (Lifecycle lc : Lifecycle.values()) map.get(lc).add(h);
			} else {
				for (Lifecycle lc : Lifecycle.values()) {
					if (h.lifecycleAccepts(lc)) map.get(lc).add(h);
				}
			}
		}
		for (Lifecycle lc : Lifecycle.values()) {
			map.put(lc, List.copyOf(map.get(lc)));
		}
		return map;
	}

	private static SpanKind nullToInternal(SpanKind kind) {
		return (kind == null) ? SpanKind.INTERNAL : kind;
	}

	private static boolean throwableMatches(Handler h, Throwable t) {
		if (t == null) return !h.requireThrowable();
		// types
		List<Class<? extends Throwable>> types = h.throwableTypes();
		if (types != null && !types.isEmpty()) {
			boolean match = false;
			for (Class<? extends Throwable> cls : types) {
				if (h.includeSubclasses()) {
					if (cls.isInstance(t)) { match = true; break; }
				} else {
					if (t.getClass().equals(cls)) { match = true; break; }
				}
			}
			if (!match) return false;
		}
		// message
		if (h.messagePattern() != null) {
			String msg = t.getMessage();
			if (msg == null || !h.messagePattern().matcher(msg).find()) return false;
		}
		// cause type
		if (h.causeTypeOrNull() != null) {
			Throwable c = t.getCause();
			if (c == null || !h.causeTypeOrNull().isInstance(c)) return false;
		}
		return true;
	}

	private static List<String> missingAttrs(TelemetryHolder holder, Set<String> required) {
		if (required == null || required.isEmpty()) return List.of();
		List<String> missing = new ArrayList<>();
		for (String k : required) if (!holder.hasAttr(k)) missing.add(k);
		return missing;
	}

	private static Object[] bindParams(List<ParamBinder> binders, TelemetryHolder holder) {
		Object[] args = new Object[binders.size()];
		for (int i = 0; i < binders.size(); i++) {
			ParamBinder b = binders.get(i);
			if (b instanceof BatchBinder) {
				// single-event dispatch: batch param not applicable
				args[i] = null;
			} else {
				args[i] = b.bind(holder);
			}
		}
		return args;
	}

	private static Object[] bindBatchParams(List<ParamBinder> binders, List<TelemetryHolder> batch) {
		Object[] args = new Object[binders.size()];
		for (int i = 0; i < binders.size(); i++) {
			ParamBinder b = binders.get(i);
			if (b instanceof BatchBinder bb) {
				args[i] = bb.bindBatch(batch);
			} else if (b instanceof HolderBinder) {
				// if a handler also asked for a holder, pass the root (first) as a convenience
				args[i] = batch.get(0);
			} else {
				// other binders don't apply in batch context
				args[i] = null;
			}
		}
		return args;
	}

	private static void logMissing(Handler h, TelemetryHolder holder, Lifecycle phase, List<String> missing) {
		log.debug("Missing required attributes handler={} name={} phase={} missing={}",
			h.id(), holder.getName(), phase, missing);
	}

	// --- Compatibility shims for legacy callers (enqueue*) ---

	/** @deprecated Use dispatch(Lifecycle.FLOW_STARTED, holder) */
	@Deprecated
	public void enqueueStart(TelemetryHolder holder) {
		dispatch(Lifecycle.FLOW_STARTED, holder);
	}

	/** @deprecated Use dispatch(Lifecycle.FLOW_FINISHED, holder) */
	@Deprecated
	public void enqueueFinish(TelemetryHolder holder) {
		dispatch(Lifecycle.FLOW_FINISHED, holder);
	}

	/** @deprecated Use dispatchRootFinished(List<TelemetryHolder>) */
	@Deprecated
	public void enqueueRootFinished(List<TelemetryHolder> batch) {
		dispatchRootFinished(batch);
	}
}
