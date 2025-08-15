package com.obsinity.telemetry.receivers;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.stereotype.Component;

import io.opentelemetry.api.trace.SpanKind;
import com.obsinity.telemetry.annotations.PullAllAttributes;
import com.obsinity.telemetry.annotations.PullAllContextValues;
import com.obsinity.telemetry.annotations.PullAttribute;
import com.obsinity.telemetry.annotations.PullContextValue;
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
 *
 * <p><b>Exception dispatch semantics:</b> If {@code holder.getThrowable() != null}, the dispatcher invokes exactly one
 * ERROR handler (the most specific match) and does <b>not</b> invoke NORMAL handlers. ALWAYS handlers still run. When
 * no exception is present, NORMAL and ALWAYS handlers run.
 */
@Component
public class TelemetryDispatchBus implements TelemetryEventDispatcher {

	private static final Logger log = LoggerFactory.getLogger(TelemetryDispatchBus.class);

	private final List<Handler> handlers;
	private final Map<Lifecycle, List<Handler>> byLifecycle;

	public TelemetryDispatchBus(ListableBeanFactory beanFactory, TelemetryEventHandlerScanner scanner) {
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

		final Throwable t = holder.getThrowable();

		// ==== Exception present → single-dispatch to ERROR handler; ALWAYS also runs ====
		if (t != null) {
			Handler best = selectBestErrorHandler(candidates, holder, phase, t);
			if (best != null) {
				invokeHandler(best, holder, phase);
			} else {
				log.warn("Unhandled exception for event name={} phase={} ex={}", holder.getName(), phase, t.toString());
			}

			// Run ALWAYS handlers (side-effect-safe), but never NORMAL in error path
			for (Handler h : candidates) {
				if (!h.isAlwaysMode()) continue;
				if (!h.kindAccepts(nullToInternal(holder.getSpanKind()))) continue;
				if (!h.nameMatches(holder.getName())) continue;
				if (!throwableFiltersAccept(h, t)) continue;
				// required attributes
				if (!h.requiredAttrs().isEmpty()) {
					List<String> missing = missingAttrs(holder, h.requiredAttrs());
					if (!missing.isEmpty()) {
						logMissing(h, holder, phase, missing);
						continue;
					}
				}
				invokeHandler(h, holder, phase);
			}
			return; // do NOT run NORMAL handlers
		}

		// ==== Normal path (no exception) → run NORMAL + ALWAYS ====
		boolean any = false;
		for (Handler h : candidates) {
			if (!(h.isNormalMode() || h.isAlwaysMode())) continue; // skip ERROR handlers
			if (!h.kindAccepts(nullToInternal(holder.getSpanKind()))) continue;
			if (!h.nameMatches(holder.getName())) continue;
			// when no throwable, throwable filters must not disqualify
			if (!throwableFiltersAccept(h, null)) continue;

			// required attributes
			if (!h.requiredAttrs().isEmpty()) {
				List<String> missing = missingAttrs(holder, h.requiredAttrs());
				if (!missing.isEmpty()) {
					logMissing(h, holder, phase, missing);
					continue;
				}
			}

			if (invokeHandler(h, holder, phase)) any = true;
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

	/* ================= Selection & Matching Helpers ================= */

	/** Choose the most specific ERROR handler. If none matches, pick a catch-all, else null. */
	private Handler selectBestErrorHandler(
			List<Handler> candidates, TelemetryHolder holder, Lifecycle phase, Throwable t) {
		Handler best = null;
		int bestDist = Integer.MAX_VALUE;

		// First pass: specific matches (based on throwableTypes + message/cause)
		for (Handler h : candidates) {
			if (!h.isErrorMode()) continue; // only ERROR here
			if (!h.kindAccepts(nullToInternal(holder.getSpanKind()))) continue;
			if (!h.nameMatches(holder.getName())) continue;
			if (!throwableFiltersAccept(h, t)) continue;

			int dist = distanceToTypes(t, h.throwableTypes(), h.includeSubclasses());
			// If no types are declared, treat as catch-all (handled in second pass)
			if (dist == Integer.MAX_VALUE) continue;

			// Keep closest match
			if (dist < bestDist) {
				bestDist = dist;
				best = h;
			}
		}
		if (best != null) return best;

		// Second pass: catch-alls for this selector (no types declared)
		for (Handler h : candidates) {
			if (!h.isErrorMode()) continue;
			if (!h.kindAccepts(nullToInternal(holder.getSpanKind()))) continue;
			if (!h.nameMatches(holder.getName())) continue;
			// no types means catch-all; still respect message/cause filters if present
			if (!throwableFiltersAccept(h, t)) continue;

			if (h.throwableTypes() == null || h.throwableTypes().isEmpty()) {
				return h; // first catch-all is fine; ordering can be improved with priority later
			}
		}
		return null;
	}

	/** Accepts throwable-related filters on the handler given the current throwable (may be null). */
	private static boolean throwableFiltersAccept(Handler h, Throwable t) {
		// When there is no throwable: ERROR handlers are already filtered out by caller, so only
		// allow NORMAL/ALWAYS here, and ignore throwable type/message/cause filters.
		if (t == null) {
			return true;
		}

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

	/** Class distance to the closest declared type; Integer.MAX_VALUE if none declared. */
	private static int distanceToTypes(Throwable t, List<Class<? extends Throwable>> types, boolean includeSubclasses) {
		if (types == null || types.isEmpty()) return Integer.MAX_VALUE;
		int best = Integer.MAX_VALUE;
		for (Class<? extends Throwable> cls : types) {
			if (includeSubclasses ? cls.isInstance(t) : t.getClass().equals(cls)) {
				int d = classDistance(t.getClass(), cls);
				if (d < best) best = d;
			}
		}
		return best;
	}

	private static int classDistance(Class<?> actual, Class<?> target) {
		if (target == null) return Integer.MAX_VALUE;
		int d = 0;
		Class<?> c = actual;
		while (c != null && !target.equals(c)) {
			c = c.getSuperclass();
			d++;
		}
		return (c == null) ? Integer.MAX_VALUE : d;
	}

	/* ================= Existing Helpers (mostly unchanged) ================= */

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

	private static List<String> missingAttrs(TelemetryHolder holder, Set<String> required) {
		if (required == null || required.isEmpty()) return List.of();
		List<String> missing = new ArrayList<>();
		for (String k : required) if (!holder.hasAttr(k)) missing.add(k);
		return missing;
	}

	private boolean invokeHandler(Handler h, TelemetryHolder holder, Lifecycle phase) {
		final Object[] args;
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
			return false;
		}
		try {
			Method m = h.method();
			if (!m.canAccess(h.bean())) m.setAccessible(true);
			m.invoke(h.bean(), args);
			return true;
		} catch (Throwable t) {
			log.warn(
					"Handler invocation failed handler={} name={} phase={}: {}",
					h.id(),
					holder.getName(),
					phase,
					t.toString());
			return false;
		}
	}

	/**
	 * Bind params for single-holder dispatch. First tries declared ParamBinders; if a slot isn't provided by a binder,
	 * it falls back to: - TelemetryHolder injection - @PullAttribute(name="...") / @PullAttribute("...") from
	 * holder.attributes() - @PullContextValue(name="...") / @PullContextValue("...") from holder.getEventContext()
	 * (flow-scoped) - @PullAllContextValues Map view of holder.getEventContext() - @PullAllAttributes Map snapshot of
	 * holder.attributes().asMap()
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
				} else if (b instanceof HolderBinder) {
					val = holder;
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
					// --- Attributes (supports alias name()/value())
					PullAttribute pullAttr = p.getAnnotation(PullAttribute.class);
					if (pullAttr != null) {
						String key = readAlias(pullAttr);
						Object raw = holder.attributes().asMap().get(key);
						val = coerce(raw, p.getType());
					}

					// --- Context values (supports alias name()/value())
					if (val == null) {
						PullContextValue pullCtx = p.getAnnotation(PullContextValue.class);
						if (pullCtx != null) {
							String key = readAlias(pullCtx);
							Object raw = holder.getEventContext().get(key);
							val = coerce(raw, p.getType());
						}
					}

					// @PullAllContextValues Map view of event context (unmodifiable)
					if (val == null && p.isAnnotationPresent(PullAllContextValues.class)) {
						val = Collections.unmodifiableMap(holder.getEventContext());
					}

					// @PullAllAttributes Map snapshot of event attributes (unmodifiable)
					if (val == null && p.isAnnotationPresent(PullAllAttributes.class)) {
						val = Collections.unmodifiableMap(
								new LinkedHashMap<>(holder.attributes().asMap()));
					}
				}
			}

			args[i] = val;
		}
		return args;
	}

	/**
	 * Bind params for batch dispatch. Supports BatchBinder, optional TelemetryHolder (root/first)
	 * injection, @PullAttribute/@PullContextValue sourced from the root holder,
	 * and @PullAllContextValues/@PullAllAttributes from the root holder.
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
					val = root;
				} else if (b != null) {
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
					// Attributes (supports alias name()/value())
					PullAttribute pullAttr = p.getAnnotation(PullAttribute.class);
					if (pullAttr != null) {
						String key = readAlias(pullAttr);
						Object raw = root.attributes().asMap().get(key);
						val = coerce(raw, p.getType());
					}

					// Context values (supports alias name()/value())
					if (val == null) {
						PullContextValue pullCtx = p.getAnnotation(PullContextValue.class);
						if (pullCtx != null) {
							String key = readAlias(pullCtx);
							Object raw = root.getEventContext().get(key);
							val = coerce(raw, p.getType());
						}
					}

					if (val == null && p.isAnnotationPresent(PullAllContextValues.class)) {
						val = Collections.unmodifiableMap(root.getEventContext());
					}
					if (val == null && p.isAnnotationPresent(PullAllAttributes.class)) {
						val = Collections.unmodifiableMap(
								new LinkedHashMap<>(root.attributes().asMap()));
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

	/* ====== Small reflection helpers to support @AliasFor(name/value) on annotations ====== */

	private static String readAlias(PullAttribute ann) {
		// Prefer name() if present & non-empty, else value(). Works with @AliasFor.
		String key = invokeIfPresent(ann, "name");
		if (key == null || key.isEmpty()) key = invokeIfPresent(ann, "value");
		return key;
	}

	private static String readAlias(PullContextValue ann) {
		String key = invokeIfPresent(ann, "name");
		if (key == null || key.isEmpty()) key = invokeIfPresent(ann, "value");
		return key;
	}

	private static String invokeIfPresent(Annotation ann, String method) {
		try {
			var m = ann.annotationType().getMethod(method);
			Object v = m.invoke(ann);
			return (v instanceof String s) ? s : null;
		} catch (NoSuchMethodException e) {
			return null;
		} catch (Exception e) {
			return null;
		}
	}
}
