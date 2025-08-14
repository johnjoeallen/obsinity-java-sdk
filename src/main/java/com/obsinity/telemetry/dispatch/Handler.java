package com.obsinity.telemetry.dispatch;

import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import io.opentelemetry.api.trace.SpanKind;
import com.obsinity.telemetry.model.Lifecycle;

/** Immutable, precompiled handler descriptor discovered at bootstrap. */
public record Handler(
	Object bean,
	Method method,
	String exactName,                 // exact event name match (optional)
	String namePrefix,                // prefix-based match (optional)
	BitSet lifecycleMask,
	BitSet kindMask,
	boolean requireThrowable,
	List<Class<? extends Throwable>> throwableTypes,
	boolean includeSubclasses,
	Pattern messagePattern,           // still regex for message if you use it
	Class<?> causeTypeOrNull,
	List<ParamBinder> binders,
	Set<String> requiredAttrs,
	String id                         // e.g. beanClass#method
) {
	public boolean lifecycleAccepts(Lifecycle lc) {
		return (lifecycleMask == null) || lifecycleMask.get(lc.ordinal());
	}

	public boolean kindAccepts(SpanKind kind) {
		return (kindMask == null) || kindMask.get(kind.ordinal());
	}

	/** Name match rules (in order): exactName > namePrefix > no constraint. */
	public boolean nameMatches(String name) {
		if (exactName != null && !exactName.isEmpty()) {
			return exactName.equals(name);
		}
		if (namePrefix != null && !namePrefix.isEmpty()) {
			return name != null && name.startsWith(namePrefix);
		}
		return true; // no constraint
	}
}
