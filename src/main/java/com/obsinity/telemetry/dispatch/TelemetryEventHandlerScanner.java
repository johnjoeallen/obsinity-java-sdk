package com.obsinity.telemetry.dispatch;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.opentelemetry.api.trace.SpanKind;
import com.obsinity.telemetry.annotations.AllAttrs;
import com.obsinity.telemetry.annotations.Attr;
import com.obsinity.telemetry.annotations.Err;
import com.obsinity.telemetry.annotations.OnEvent;
import com.obsinity.telemetry.annotations.RequireAttrs;
import com.obsinity.telemetry.annotations.TelemetryEventHandler;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;

/** Scans beans for @OnEvent methods, but only if the bean's user class is annotated with @TelemetryEventHandler. */
public final class TelemetryEventHandlerScanner {

	public List<Handler> scan(Object bean) {
		Class<?> userClass = resolveUserClass(bean);

		// Only scan classes marked as TelemetryEventHandler
		if (!userClass.isAnnotationPresent(TelemetryEventHandler.class)) {
			return List.of(); // skip silently
		}

		List<Handler> out = new ArrayList<>();
		for (Method m : userClass.getMethods()) {
			OnEvent on = m.getAnnotation(OnEvent.class);
			if (on == null) continue;

			validateNameSelectors(on, m);

			String exact = on.name().isBlank() ? null : on.name();
			Pattern namePat = on.nameRegex().isBlank() ? null : Pattern.compile(on.nameRegex());

			BitSet lifecycleMask = bitset(on.lifecycle(), Lifecycle.values().length, lc -> lc.ordinal());
			BitSet kindMask = bitset(on.kinds(), SpanKind.values().length, k -> k.ordinal());

			Pattern msgPat = on.messageRegex().isBlank() ? null : Pattern.compile(on.messageRegex());

			Class<?> causeType = null;
			if (!on.causeType().isBlank()) {
				try {
					causeType = Class.forName(on.causeType(), false, userClass.getClassLoader());
				} catch (ClassNotFoundException e) {
					throw new IllegalArgumentException(
							"@OnEvent causeType not found: " + on.causeType() + " on " + signature(userClass, m));
				}
			}

			// Collect required attrs from @RequireAttrs + @Attr(required=true)
			Set<String> requiredAttrs = new LinkedHashSet<>();
			RequireAttrs req = m.getAnnotation(RequireAttrs.class);
			if (req != null) requiredAttrs.addAll(Arrays.asList(req.value()));

			// Build param binders
			List<ParamBinder> binders = new ArrayList<>();
			var params = m.getParameters();
			for (var p : params) {
				Attr a = p.getAnnotation(Attr.class);
				Err err = p.getAnnotation(Err.class);
				AllAttrs all = p.getAnnotation(AllAttrs.class);

				if (a != null) {
					requiredAttrs.add(a.value());
					binders.add(new AttrBinder(a.value(), a.required(), TypeConverters.forType(p.getType())));
				} else if (err != null) {
					boolean bindCause = "cause".equals(err.target());
					binders.add(new ErrBinder(err.required(), bindCause));
				} else if (all != null) {
					if (!Map.class.isAssignableFrom(p.getType())) {
						throw new IllegalArgumentException(
								"@AllAttrs parameter must be a Map: " + signature(userClass, m));
					}
					binders.add(new AllAttrsBinder());
				} else if (List.class.isAssignableFrom(p.getType())) {
					// Inferred batch parameter — only allowed for ROOT_FLOW_FINISHED
					validateListParamAllowed(on, userClass, m);
					// Reuse your existing binder that expects the dispatcher to supply List<TelemetryHolder>
					binders.add(new BatchBinder(p.getType()));
				} else if (TelemetryHolder.class.isAssignableFrom(p.getType())) {
					binders.add(new HolderBinder());
				} else {
					throw new IllegalArgumentException(
							"Unsupported parameter binding on " + signature(userClass, m) + " for parameter: " + p);
				}
			}

			List<Class<? extends Throwable>> types = List.of(on.throwableTypes());
			String id = userClass.getSimpleName() + "#" + m.getName();

			out.add(new Handler(
					bean,
					m,
					exact,
					namePat,
					lifecycleMask,
					kindMask,
					on.requireThrowable(),
					types,
					on.includeSubclasses(),
					msgPat,
					causeType,
					List.copyOf(binders),
					Set.copyOf(requiredAttrs),
					id));
		}
		return out;
	}

	/** Resolve underlying class if bean is a proxy. Works with/without Spring. */
	private static Class<?> resolveUserClass(Object bean) {
		Class<?> c = bean.getClass();
		try {
			Class<?> aopUtils = Class.forName("org.springframework.aop.support.AopUtils");
			boolean isAopProxy =
					(boolean) aopUtils.getMethod("isAopProxy", Object.class).invoke(null, bean);
			if (isAopProxy) {
				Class<?> target = (Class<?>)
						aopUtils.getMethod("getTargetClass", Object.class).invoke(null, bean);
				if (target != null) return target;
			}
			Class<?> classUtils = Class.forName("org.springframework.util.ClassUtils");
			Class<?> user =
					(Class<?>) classUtils.getMethod("getUserClass", Class.class).invoke(null, c);
			if (user != null) return user;
		} catch (Throwable ignore) {
			// Not using Spring or reflection failed — fall back
		}
		String n = c.getName();
		int jdkProxyIdx = n.indexOf('$');
		if (jdkProxyIdx > 0) {
			try {
				return Class.forName(n.substring(0, jdkProxyIdx));
			} catch (ClassNotFoundException ignored) {
			}
		}
		return c;
	}

	private static void validateNameSelectors(OnEvent on, Method m) {
		boolean hasExact = !on.name().isBlank();
		boolean hasRegex = !on.nameRegex().isBlank();
		if (hasExact && hasRegex) {
			throw new IllegalArgumentException(
					"@OnEvent cannot set both name and nameRegex on " + signature(m.getDeclaringClass(), m));
		}
	}

	private static void validateListParamAllowed(OnEvent on, Class<?> userClass, Method m) {
		// List parameter is only valid when lifecycle is exactly ROOT_FLOW_FINISHED (i.e., batch of completed flows)
		var lifecycles = Arrays.asList(on.lifecycle());
		boolean onlyRootFinished =
				!lifecycles.isEmpty() && lifecycles.stream().allMatch(lc -> lc == Lifecycle.ROOT_FLOW_FINISHED);
		if (!onlyRootFinished) {
			throw new IllegalArgumentException(
					"List<TelemetryHolder> parameters are only allowed for lifecycle=ROOT_FLOW_FINISHED on "
							+ signature(userClass, m));
		}
	}

	private static <T> BitSet bitset(T[] values, int size, java.util.function.ToIntFunction<T> ord) {
		if (values == null || values.length == 0) return null; // means “any”
		BitSet bs = new BitSet(size);
		for (T v : values) bs.set(ord.applyAsInt(v));
		return bs;
	}

	private static String signature(Class<?> c, Method m) {
		return c.getName() + "." + m.getName() + Arrays.toString(m.getParameterTypes());
	}
}
