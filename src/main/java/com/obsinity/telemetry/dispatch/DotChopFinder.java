package com.obsinity.telemetry.dispatch;

import java.util.Map;
import java.util.Objects;

public final class DotChopFinder {

	private DotChopFinder() {}

	/**
	 * Iteratively looks up eventName and its dot-chopped ancestors in the map. Example: orders.checkout.details -> try:
	 * "orders.checkout.details", "orders.checkout", "orders" Returns null if nothing found (empty string is never
	 * queried).
	 */
	public static <V> V get(Map<String, V> map, String eventName) {
		Objects.requireNonNull(map, "map");
		String key = Objects.requireNonNull(eventName, "eventName").trim();
		if (key.isEmpty()) return null;

		for (; ; ) {
			V val = map.get(key);
			if (val != null) return val;

			int lastDot = key.lastIndexOf('.');
			if (lastDot < 0) return null; // no more segments; empty string is NOT a key
			key = key.substring(0, lastDot);
		}
	}

	/** True if ancestorKey matches eventName by dot-chop: exact match OR eventName starts with ancestorKey + "." */
	public static boolean matches(String ancestorKey, String eventName) {
		if (ancestorKey == null || eventName == null) return false;
		return ancestorKey.equals(eventName) || eventName.startsWith(ancestorKey + ".");
	}
}
