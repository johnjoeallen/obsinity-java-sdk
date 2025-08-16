package com.obsinity.telemetry.receivers;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.stereotype.Component;

import io.opentelemetry.api.trace.SpanKind;
import com.obsinity.telemetry.annotations.*;
import com.obsinity.telemetry.dispatch.*;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;

@Component
public class TelemetryDispatchBus implements TelemetryEventDispatcher {

	private static final Logger log = LoggerFactory.getLogger(TelemetryDispatchBus.class);

	private final List<HandlerGroup> groups;

	public TelemetryDispatchBus(ListableBeanFactory beanFactory, TelemetryEventHandlerScanner scanner) {
		Collection<Object> beans = beanFactory.getBeansWithAnnotation(TelemetryEventHandler.class).values();

		// De-dup by underlying user class to avoid double registration (proxy + target)
		Map<Class<?>, HandlerGroup> uniq = new LinkedHashMap<>();
		for (Object bean : beans) {
			HandlerGroup g = scanner.scanGrouped(bean);
			if (g == null) continue;

			Class<?> userClass = resolveUserClass(bean);
			HandlerGroup prev = uniq.putIfAbsent(userClass, g);
			if (prev != null) {
				log.warn("[Obsinity] Skipping duplicate @TelemetryEventHandler for userClass={} "
						+ "(kept {}, dropped {})",
					userClass.getName(),
					describeBean(prev.bean()),
					describeBean(bean));
			}
		}
		this.groups = List.copyOf(uniq.values());
		log.info("TelemetryDispatchBus registered {} handler beans (from {} discovered beans)",
			groups.size(), beans.size());
	}

	@Override
	public void dispatch(Lifecycle phase, TelemetryHolder holder) {
		if (holder == null) return;
		final Throwable t = holder.getThrowable();

		for (HandlerGroup g : groups) {
			HandlerGroup.ModeBuckets bucket = findBucket(g, phase, holder.getName());
			if (bucket == null) continue;

			if (t != null) {
				// ERROR: pick best, then ALWAYS
				Handler best = selectBestError(bucket.error, holder, phase, t);
				if (best != null) {
					invokeHandler(best, holder, phase);
				} else {
					log.warn("Unhandled exception for event name={} phase={} ex={}",
						holder.getName(), phase, String.valueOf(t));
				}
				runAlways(bucket.always, holder, phase, t);
			} else {
				// NORMAL path: NORMAL then ALWAYS
				runNormal(bucket.normal, holder, phase);
				runAlways(bucket.always, holder, phase, null);
			}
		}
	}

	@Override
	public void dispatchRootFinished(List<TelemetryHolder> batch) {
		if (batch == null || batch.isEmpty()) return;

		for (HandlerGroup g : groups) {
			Map<String, HandlerGroup.ModeBuckets> byName =
				g.index().getOrDefault(Lifecycle.ROOT_FLOW_FINISHED, new LinkedHashMap<>());
			for (HandlerGroup.ModeBuckets b : new LinkedHashSet<>(byName.values())) {
				for (Handler h : concat(b.normal, b.always, b.error)) {
					boolean batchCapable = h.binders().stream().anyMatch(bb -> bb instanceof BatchBinder);
					if (!batchCapable) continue;
					try {
						Object[] args = bindBatchParams(h, batch);
						Method m = h.method();
						if (!m.canAccess(h.bean())) m.setAccessible(true);
						m.invoke(h.bean(), args);
					} catch (AttrBindingException ex) {
						log.debug("Batch binding error handler={} phase=ROOT_FLOW_FINISHED key={}: {}",
							h.id(), ex.key(), ex.getMessage());
					} catch (Throwable t) {
						log.warn("Batch handler invocation failed handler={} phase=ROOT_FLOW_FINISHED: {}",
							h.id(), String.valueOf(t));
					}
				}
			}
		}
	}

	/* -------- name fallback per handler group: exact → chop → "" -------- */

	private HandlerGroup.ModeBuckets findBucket(HandlerGroup g, Lifecycle lc, String eventName) {
		Map<String, HandlerGroup.ModeBuckets> byName = g.index().getOrDefault(lc, new LinkedHashMap<>());
		String probe = (eventName == null ? "" : eventName);
		while (true) {
			HandlerGroup.ModeBuckets b = byName.get(probe);
			if (b != null) return b;
			int dot = probe.lastIndexOf('.');
			if (dot < 0) return byName.get(""); // ultimate wildcard
			probe = probe.substring(0, dot);
		}
	}

	/* -------- selection & execution helpers -------- */

	private Handler selectBestError(List<Handler> candidates, TelemetryHolder holder, Lifecycle phase, Throwable t) {
		if (candidates == null || candidates.isEmpty()) return null;

		Handler bestTyped = null; int bestDist = Integer.MAX_VALUE;
		Handler catchAll  = null;

		for (Handler h : candidates) {
			if (!h.kindAccepts(nullToInternal(holder.getSpanKind()))) continue;
			if (!throwableFiltersAccept(h, t)) continue;
			if (!requiredAttrsPresent(h, holder, phase)) continue;

			List<Class<? extends Throwable>> types = h.throwableTypes();
			if (types == null || types.isEmpty()) {
				if (catchAll == null) catchAll = h;
				continue;
			}
			int dist = distanceToTypes(t, types, h.includeSubclasses());
			if (dist < bestDist) { bestDist = dist; bestTyped = h; }
		}
		return (bestTyped != null) ? bestTyped : catchAll;
	}

	private void runNormal(List<Handler> normals, TelemetryHolder holder, Lifecycle phase) {
		if (normals == null || normals.isEmpty()) return;
		for (Handler h : normals) {
			if (!h.kindAccepts(nullToInternal(holder.getSpanKind()))) continue;
			if (!requiredAttrsPresent(h, holder, phase)) continue;
			invokeHandler(h, holder, phase);
		}
	}

	private void runAlways(List<Handler> always, TelemetryHolder holder, Lifecycle phase, Throwable t) {
		if (always == null || always.isEmpty()) return;
		for (Handler h : always) {
			if (!h.kindAccepts(nullToInternal(holder.getSpanKind()))) continue;
			if (!throwableFiltersAccept(h, t)) continue;
			if (!requiredAttrsPresent(h, holder, phase)) continue;
			invokeHandler(h, holder, phase);
		}
	}

	private static boolean requiredAttrsPresent(Handler h, TelemetryHolder holder, Lifecycle phase) {
		if (h.requiredAttrs().isEmpty()) return true;
		for (String k : h.requiredAttrs()) if (!holder.hasAttr(k)) return false;
		return true;
	}

	private static boolean throwableFiltersAccept(Handler h, Throwable t) {
		if (t == null) return true;
		List<Class<? extends Throwable>> types = h.throwableTypes();
		if (types != null && !types.isEmpty()) {
			boolean match = false;
			for (Class<? extends Throwable> cls : types) {
				if (h.includeSubclasses() ? cls.isInstance(t) : t.getClass().equals(cls)) { match = true; break; }
			}
			if (!match) return false;
		}
		if (h.messagePattern() != null) {
			String msg = t.getMessage();
			if (msg == null || !h.messagePattern().matcher(msg).find()) return false;
		}
		if (h.causeTypeOrNull() != null) {
			Throwable c = t.getCause();
			if (c == null || !h.causeTypeOrNull().isInstance(c)) return false;
		}
		return true;
	}

	private static int distanceToTypes(Throwable t, List<Class<? extends Throwable>> types, boolean includeSubclasses) {
		if (t == null) return Integer.MAX_VALUE;
		if (types == null || types.isEmpty()) return Integer.MAX_VALUE - 1;
		int best = Integer.MAX_VALUE;
		for (Class<? extends Throwable> cls : types) {
			if (includeSubclasses ? cls.isInstance(t) : t.getClass().equals(cls)) {
				int d = 0; Class<?> c = t.getClass();
				while (c != null && !cls.equals(c)) { c = c.getSuperclass(); d++; }
				if (c != null && d < best) best = d;
			}
		}
		return best;
	}

	private static SpanKind nullToInternal(SpanKind kind) {
		return (kind == null) ? SpanKind.INTERNAL : kind;
	}

	/* -------- binding/invocation -------- */

	private boolean invokeHandler(Handler h, TelemetryHolder holder, Lifecycle phase) {
		final Object[] args;
		try {
			args = bindParams(h, holder);
		} catch (AttrBindingException ex) {
			log.debug("Binding error for handler={} name={} phase={} key={}: {}",
				h.id(), holder.getName(), phase, ex.key(), ex.getMessage());
			return false;
		}
		try {
			Method m = h.method();
			if (!m.canAccess(h.bean())) m.setAccessible(true);
			m.invoke(h.bean(), args);
			return true;
		} catch (Throwable t) {
			log.warn("Handler invocation failed handler={} name={} phase={}: {}",
				h.id(), holder.getName(), phase, String.valueOf(t));
			return false;
		}
	}

	private static Object[] bindParams(Handler h, TelemetryHolder holder) {
		List<ParamBinder> binders = h.binders();
		Method method = h.method();
		Parameter[] params = method.getParameters();

		Object[] args = new Object[params.length];
		for (int i = 0; i < params.length; i++) {
			Object val = null;

			if (i < binders.size()) {
				ParamBinder b = binders.get(i);
				if (b instanceof BatchBinder) {
					val = null;
				} else if (b instanceof HolderBinder) {
					val = holder;
				} else if (b != null) {
					val = b.bind(holder);
				}
			}

			if (val == null) {
				Parameter p = params[i];
				if (TelemetryHolder.class.isAssignableFrom(p.getType())) {
					val = holder;
				} else {
					PullAttribute pullAttr = p.getAnnotation(PullAttribute.class);
					if (pullAttr != null) {
						String key = readAlias(pullAttr);
						Object raw = holder.attributes().asMap().get(key);
						val = coerce(raw, p.getType());
					}
					if (val == null) {
						PullContextValue pullCtx = p.getAnnotation(PullContextValue.class);
						if (pullCtx != null) {
							String key = readAlias(pullCtx);
							Object raw = holder.getEventContext().get(key);
							val = coerce(raw, p.getType());
						}
					}
					if (val == null && p.isAnnotationPresent(PullAllContextValues.class)) {
						val = Collections.unmodifiableMap(holder.getEventContext());
					}
					if (val == null && p.isAnnotationPresent(PullAllAttributes.class)) {
						val = Collections.unmodifiableMap(new LinkedHashMap<>(holder.attributes().asMap()));
					}
				}
			}
			args[i] = val;
		}
		return args;
	}

	private static Object[] bindBatchParams(Handler h, List<TelemetryHolder> batch) {
		List<ParamBinder> binders = h.binders();
		Method method = h.method();
		Parameter[] params = method.getParameters();

		TelemetryHolder root = batch.get(0);
		Object[] args = new Object[params.length];

		for (int i = 0; i < params.length; i++) {
			Object val = null;

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

			if (val == null) {
				Parameter p = params[i];
				if (List.class.isAssignableFrom(p.getType())) {
					val = batch;
				} else if (TelemetryHolder.class.isAssignableFrom(p.getType())) {
					val = root;
				} else {
					PullAttribute pullAttr = p.getAnnotation(PullAttribute.class);
					if (pullAttr != null) {
						String key = readAlias(pullAttr);
						Object raw = root.attributes().asMap().get(key);
						val = coerce(raw, p.getType());
					}
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
						val = Collections.unmodifiableMap(new LinkedHashMap<>(root.attributes().asMap()));
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
		if (targetType == String.class) return String.valueOf(raw);
		return raw;
	}

	private static String readAlias(PullAttribute ann) {
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
		} catch (Exception e) { return null; }
	}

	@SafeVarargs
	private static <T> List<T> concat(List<T>... lists) {
		List<T> all = new ArrayList<>();
		for (List<T> l : lists) if (l != null) all.addAll(l);
		return all;
	}

	private static Class<?> resolveUserClass(Object bean) {
		if (bean == null) return null;
		if (AopUtils.isAopProxy(bean)) {
			Class<?> target = AopUtils.getTargetClass(bean);
			if (target != null) return org.springframework.util.ClassUtils.getUserClass(target);
		}
		return org.springframework.util.ClassUtils.getUserClass(bean.getClass());
	}

	private static String describeBean(Object bean) {
		return bean.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(bean));
	}
}
