package com.obsinity.telemetry.dispatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.obsinity.telemetry.model.Lifecycle;

public final class HandlerGroup {

	private final Object bean;
	private final Map<Lifecycle, Map<String, ModeBuckets>> index;

	public HandlerGroup(Object bean, Map<Lifecycle, Map<String, ModeBuckets>> index) {
		this.bean = Objects.requireNonNull(bean, "bean");
		this.index = deepUnmodifiable(index);
	}

	public Object bean() {
		return bean;
	}

	public Map<Lifecycle, Map<String, ModeBuckets>> index() {
		return index;
	}

	/* ------------ Buckets by mode for a single (lifecycle, name) key ------------ */
	public static final class ModeBuckets {
		public final List<Handler> normal = new ArrayList<>();
		public final List<Handler> always = new ArrayList<>();
		public final List<Handler> error = new ArrayList<>();
	}

	private static Map<Lifecycle, Map<String, ModeBuckets>> deepUnmodifiable(
			Map<Lifecycle, Map<String, ModeBuckets>> src) {
		Map<Lifecycle, Map<String, ModeBuckets>> out = new EnumMap<>(Lifecycle.class);
		for (var e : src.entrySet()) {
			Map<String, ModeBuckets> names = new LinkedHashMap<>();
			for (var ne : e.getValue().entrySet()) {
				ModeBuckets b = ne.getValue();
				ModeBuckets copy = new ModeBuckets();
				copy.normal.addAll(List.copyOf(b.normal));
				copy.always.addAll(List.copyOf(b.always));
				copy.error.addAll(List.copyOf(b.error));
				names.put(ne.getKey(), copy);
			}
			out.put(e.getKey(), Collections.unmodifiableMap(names));
		}
		return Collections.unmodifiableMap(out);
	}
}
