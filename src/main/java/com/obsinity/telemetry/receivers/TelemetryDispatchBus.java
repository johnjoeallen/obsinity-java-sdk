package com.obsinity.telemetry.receivers;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.stereotype.Component;

import io.opentelemetry.api.trace.SpanKind;
import com.obsinity.telemetry.annotations.BindAllContextValues;
import com.obsinity.telemetry.annotations.BindContextValue;
import com.obsinity.telemetry.annotations.BindEventAttribute;
import com.obsinity.telemetry.annotations.TelemetryEventHandler;
import com.obsinity.telemetry.dispatch.AttrBindingException;
import com.obsinity.telemetry.dispatch.BatchBinder;
import com.obsinity.telemetry.dispatch.Handler;
import com.obsinity.telemetry.dispatch.HolderBinder;
import com.obsinity.telemetry.dispatch.ParamBinder;
import com.obsinity.telemetry.dispatch.TelemetryEventHandlerScanner;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;

/**
 * Event dispatcher that routes TelemetryHolder instances to @OnEvent methods declared on beans annotated
 * with @TelemetryEventHandler.
 */
@Component
public class TelemetryDispatchBus implements TelemetryEventDispatcher {

	private static final Logger log = LoggerFactory.getLogger(TelemetryDispatchBus.class);

	private final TelemetryEventHandlerScanner scanner;
	private final List<Handler> handlers;
	private final Map<Lifecycle, List<Handler>> byLifecycle;

	public TelemetryDispatchBus(ListableBeanFactory beanFactory, TelemetryEventHandlerScanner scanner) {
		this.scanner = scanner;
		// Find only beans marked with @TelemetryEventHandler
		Collection<Object> candidateBeans =
				beanFactory.getBeansWithAnnotation(TelemetryEventHandler.class).values();

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
				args = bindParams(h, holder);
			} catch (AttrBindingException ex) {
				log.debug(
						"Binding error for handler={} name={} phase={} key={}: {}",
						h.id(),
						holder.getName(),
						phase,
						ex.key(),
						ex.getMessage());
				continue;
			}

			try {
				Method m = h.method();
				if (!m.canAccess(h.bean())) m.setAccessible(true);
				m.invoke(h.bean(), args);
				any = true;
			} catch (Throwable t) {
				log.warn(
						"Handler invocation failed handler={} name={} phase={}: {}",
						h.id(),
						holder.getName(),
						phase,
						t.toString());
			}
		}

		if (!any) {
			log.trace("No handler accepted event name={} phase={}", holder.getName(), phase);
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
				Object[] args = bindBatchParams(h, batch);
				Method m = h.method();
				if (!m.canAccess(h.bean())) m.setAccessible(true);
				m.invoke(h.bean(), args);
			} catch (AttrBindingException ex) {
				log.debug(
						"Batch binding error handler={} phase=ROOT_FLOW_FINISHED key={}: {}",
						h.id(),
						ex.key(),
						ex.getMessage());
			} catch (Throwable t) {
				log.warn(
						"Batch handler invocation failed handler={} phase=ROOT_FLOW_FINISHED: {}",
						h.id(),
						t.toString());
			}
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
					if (cls.isInstance(t)) {
						match = true;
						break;
					}
				} else {
					if (t.getClass().equals(cls)) {
						match = true;
						break;
					}
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

	/**
	 * Bind params for single-holder dispatch. First tries declared ParamBinders; if a slot isn't provided by a binder,
	 * it falls back to: - TelemetryHolder injection - @Attribute(name="...") value from holder.attributes()
	 * - @EventContextParam(name="...") from holder.eventContext() (flow-scoped) - @AllEventContextParam Map view of
	 * holder.eventContext()
	 */
	private static Object[] bindParams(Handler h, TelemetryHolder holder) {
		List<ParamBinder> binders = h.binders();
		Method method = h.method();
		Parameter[] params = method.getParameters();

		Object[] args = new Object[params.length];
		for (int i = 0; i < params.length; i++) {
			Object val = null;

			// 1) If a binder exists for this slot, use it.
			if (i < binders.size()) {
				ParamBinder b = binders.get(i);
				if (b instanceof BatchBinder) {
					// single-event dispatch: batch param not applicable
					val = null;
				} else if (b != null) {
					val = b.bind(holder);
				}
			}

			// 2) Fallbacks if binder didn't provide a value
			if (val == null) {
				Parameter p = params[i];

				// Inject holder
				if (TelemetryHolder.class.isAssignableFrom(p.getType())) {
					val = holder;

				} else {
					// @Attribute on handler param
					BindEventAttribute attr = p.getAnnotation(BindEventAttribute.class);
					if (attr != null) {
						Object raw = holder.attributes().asMap().get(attr.name());
						val = coerce(raw, p.getType());
					}

					// @EventContextParam on handler param (flow-scoped fallback)
					if (val == null) {
						BindContextValue ecp = p.getAnnotation(BindContextValue.class);
						if (ecp != null) {
							Object raw = holder.getEventContext().get(ecp.name());
							val = coerce(raw, p.getType());
						}
					}

					// @AllEventContextParam → unmodifiable view of flow-scoped EventContext
					if (val == null && p.isAnnotationPresent(BindAllContextValues.class)) {
						val = Collections.unmodifiableMap(holder.getEventContext());
					}
				}
			}

			args[i] = val;
		}
		return args;
	}

	/**
	 * Bind params for batch dispatch. Supports BatchBinder, optional TelemetryHolder (root/first) injection,
	 * and @Attribute on handler params sourced from the first holder in the batch.
	 */
	private static Object[] bindBatchParams(Handler h, List<TelemetryHolder> batch) {
		List<ParamBinder> binders = h.binders();
		Method method = h.method();
		Parameter[] params = method.getParameters();

		TelemetryHolder root = batch.get(0);
		Object[] args = new Object[params.length];

		for (int i = 0; i < params.length; i++) {
			Object val = null;

			// 1) Prefer declared binders
			if (i < binders.size()) {
				ParamBinder b = binders.get(i);
				if (b instanceof BatchBinder bb) {
					val = bb.bindBatch(batch);
				} else if (b instanceof HolderBinder) {
					// pass the root holder as convenience
					val = root;
				} else if (b != null) {
					// allow other binders to pull from the root holder
					val = b.bind(root);
				}
			}

			// 2) Fallbacks if binder didn't provide a value
			if (val == null) {
				Parameter p = params[i];

				if (List.class.isAssignableFrom(p.getType())) {
					val = batch;
				} else if (TelemetryHolder.class.isAssignableFrom(p.getType())) {
					val = root;
				} else {
					BindEventAttribute attr = p.getAnnotation(BindEventAttribute.class);
					if (attr != null) {
						Object raw = root.attributes().asMap().get(attr.name());
						val = coerce(raw, p.getType());
					}
				}
			}

			args[i] = val;
		}
		return args;
	}

	private static Object coerce(Object raw, Class<?> targetType) {
		if (raw == null) return null;
		if (targetType.isInstance(raw)) return raw;
		// Most handler params will ask for String; add more converters as needed.
		if (targetType == String.class) return String.valueOf(raw);
		// simple numeric coercions could go here if required later
		return raw; // best effort
	}

	private static void logMissing(Handler h, TelemetryHolder holder, Lifecycle phase, List<String> missing) {
		log.debug(
				"Missing required attributes handler={} name={} phase={} missing={}",
				h.id(),
				holder.getName(),
				phase,
				missing);
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
