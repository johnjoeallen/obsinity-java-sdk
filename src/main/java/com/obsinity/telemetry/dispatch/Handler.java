package com.obsinity.telemetry.dispatch;

import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import io.opentelemetry.api.trace.SpanKind;
import com.obsinity.telemetry.annotations.DispatchMode;
import com.obsinity.telemetry.model.Lifecycle;

/** Immutable, precompiled handler descriptor discovered at bootstrap. */
public record Handler(
		Object bean,
		Method method,
		String exactName, // exact event name match (optional)
		String namePrefix, // prefix-based match (optional)
		BitSet lifecycleMask,
		BitSet kindMask,
		DispatchMode mode, // <-- replaces requireThrowable
		List<Class<? extends Throwable>> throwableTypes,
		boolean includeSubclasses,
		Pattern messagePattern, // still regex for message if you use it
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

	/** Name match rules (in order): exactName > namePrefix > no constraint. */
	public boolean nameMatches(String eventName) {
		// Exact name takes precedence when present and non-empty
		if (this.exactName != null && !this.exactName.isEmpty()) {
			return this.exactName.equals(eventName);
		}

		// Prefix match: treat null OR empty string as wildcard (match everything)
		if (this.namePrefix == null || this.namePrefix.isEmpty()) {
			return true;
		}

		// Normal prefix check
		return eventName != null && eventName.startsWith(this.namePrefix);
	}

	/** Convenience flags for dispatch logic. */
	public boolean isErrorMode() {
		return mode == DispatchMode.ERROR;
	}

	public boolean isNormalMode() {
		return mode == DispatchMode.NORMAL;
	}

	public boolean isAlwaysMode() {
		return mode == DispatchMode.ALWAYS;
	}
}
