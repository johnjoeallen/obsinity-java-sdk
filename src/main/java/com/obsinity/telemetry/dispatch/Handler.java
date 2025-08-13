package com.obsinity.telemetry.dispatch;

import com.obsinity.telemetry.model.Lifecycle;
import io.opentelemetry.api.trace.SpanKind;

import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable, precompiled handler descriptor discovered at bootstrap. */
public record Handler(
	Object bean,
	Method method,
	String exactName,
	Pattern namePattern,
	BitSet lifecycleMask,
	BitSet kindMask,
	boolean requireThrowable,
	List<Class<? extends Throwable>> throwableTypes,
	boolean includeSubclasses,
	Pattern messagePattern,
	Class<?> causeTypeOrNull,
	List<ParamBinder> binders,
	Set<String> requiredAttrs,
	String id // e.g. beanClass#method
) {
	public boolean lifecycleAccepts(Lifecycle lc) {
		return (lifecycleMask == null) || lifecycleMask.get(lc.ordinal());
	}
	public boolean kindAccepts(SpanKind kind) {
		return (kindMask == null) || kindMask.get(kind.ordinal());
	}
	public boolean nameMatches(String name) {
		if (exactName != null && !exactName.isEmpty()) return exactName.equals(name);
		if (namePattern != null) return namePattern.matcher(name).matches();
		return true; // no constraint
	}
}
