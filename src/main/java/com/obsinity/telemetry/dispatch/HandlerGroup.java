// src/main/java/com/obsinity/telemetry/dispatch/HandlerGroup.java
package com.obsinity.telemetry.dispatch;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;

/**
 * Per-component registry of handlers discovered by the scanner (flow-centric).
 *
 * Structure:
 *  - tiers: dot-chop tiers for named handlers (exact name → nearest ancestor fallback).
 *  - unmatched: component-scoped not-matched handlers.
 *  - globalUnmatched: global not-matched handlers.
 *
 * Component-level Scope supports prefix and lifecycle filtering.
 */
public final class HandlerGroup {

	/** Human-friendly component name (e.g., bean class simple name). */
	public final String componentName;

	/** Optional component-level scope filter; null/ALLOW_ALL means no filtering. */
	private Scope scope = Scope.allowAll();

	/** Dot-chop tiers: event name → per-phase buckets. Exact name first, then ancestors during dispatch. */
	private final Map<String, ModeBucketsByPhase> tiers = new HashMap<>();

	/** Component-scoped unmatched buckets (invoked when this group had no match for an in-scope event). */
	public final Unmatched unmatched = new Unmatched();

	/** Global unmatched buckets (invoked only when no group matched anywhere). */
	public final Unmatched globalUnmatched = new Unmatched();

	public HandlerGroup(String componentName) {
		this.componentName = Objects.requireNonNull(componentName);
	}

	public HandlerGroup(String componentName, Scope scope) {
		this.componentName = Objects.requireNonNull(componentName);
		this.scope = (scope == null ? Scope.allowAll() : scope);
	}

	public String getComponentName() {
		return this.componentName;
	}

	public Scope getScope() {
		return scope;
	}

	/** Configure/override the component-level scope. Null means "allow all". */
	public void setScope(Scope scope) {
		this.scope = (scope == null ? Scope.allowAll() : scope);
	}

	/** True if this group should see the given event (component-level filter). */
	public boolean isInScope(Lifecycle phase, String eventName, TelemetryHolder holder) {
		return scope.test(phase, eventName);
	}

	/* =========================
	   Registration (scanner)
	   ========================= */

	/** Register a handler that should run on BOTH outcomes (aka "completed"). */
	public void registerFlowCompleted(String exactName, Lifecycle phase, Handler h) {
		tiers.computeIfAbsent(exactName, k -> new ModeBucketsByPhase())
			.forPhase(phase)
			.completed.add(h);
	}

	/** Register a handler that should run only on SUCCESS outcome. */
	public void registerFlowSuccess(String exactName, Lifecycle phase, Handler h) {
		tiers.computeIfAbsent(exactName, k -> new ModeBucketsByPhase())
			.forPhase(phase)
			.success.add(h);
	}

	/** Register a handler that should run only on FAILURE outcome. */
	public void registerFlowFailure(String exactName, Lifecycle phase, Handler h) {
		tiers.computeIfAbsent(exactName, k -> new ModeBucketsByPhase())
			.forPhase(phase)
			.failure.add(h);
	}

	/** Component-scoped unmatched for COMPLETED (both outcomes). */
	public void registerComponentUnmatchedCompleted(Lifecycle phase, Handler h) {
		unmatched.forPhase(phase).completed.add(h);
	}

	/** Component-scoped unmatched for SUCCESS. */
	public void registerComponentUnmatchedSuccess(Lifecycle phase, Handler h) {
		unmatched.forPhase(phase).success.add(h);
	}

	/** Component-scoped unmatched for FAILURE. */
	public void registerComponentUnmatchedFailure(Lifecycle phase, Handler h) {
		unmatched.forPhase(phase).failure.add(h);
	}

	/** Global unmatched for COMPLETED (both outcomes). */
	public void registerGlobalUnmatchedCompleted(Lifecycle phase, Handler h) {
		globalUnmatched.forPhase(phase).completed.add(h);
	}

	/** Global unmatched for SUCCESS. */
	public void registerGlobalUnmatchedSuccess(Lifecycle phase, Handler h) {
		globalUnmatched.forPhase(phase).success.add(h);
	}

	/** Global unmatched for FAILURE. */
	public void registerGlobalUnmatchedFailure(Lifecycle phase, Handler h) {
		globalUnmatched.forPhase(phase).failure.add(h);
	}

	/* =========================
	   Query (dispatcher)
	   ========================= */

	/**
	 * Dot-chop selection for this component:
	 *  - Try exact event name tier; if no handlers in that tier for the phase, chop last segment and try again.
	 *  - Returns the first (nearest) tier that has ANY handlers registered for the given phase; dispatcher checks eligibility.
	 */
	public ModeBuckets findNearestEligibleTier(
		Lifecycle phase, String eventName, TelemetryHolder holder, boolean failed, Throwable error) {
		String name = eventName;
		while (name != null && !name.isEmpty()) {
			ModeBucketsByPhase byPhase = tiers.get(name);
			if (byPhase != null) {
				ModeBuckets buckets = byPhase.forPhase(phase);
				if (!buckets.isEmpty()) {
					return buckets;
				}
			}
			name = chop(name);
		}
		return null;
	}

	private static String chop(String name) {
		int i = name.lastIndexOf('.');
		return (i < 0) ? null : name.substring(0, i);
	}

	/* =========================
	   Types
	   ========================= */

	/**
	 * Compiled component-level scope: prefixes (OR) and lifecycles (OR). Both sets are ANDed together.
	 * Null/empty means "any".
	 */
	public static final class Scope {
		private final String[] prefixes;             // null/empty = any
		private final EnumSet<Lifecycle> phases;     // null = any

		private Scope(String[] prefixes, EnumSet<Lifecycle> phases) {
			this.prefixes = (prefixes == null || prefixes.length == 0) ? null : prefixes;
			this.phases   = (phases == null || phases.isEmpty())       ? null : phases;
		}

		public static Scope allowAll() {
			return new Scope(null, null);
		}

		public static Scope of(String[] prefixes, Lifecycle[] lifecycles) {
			EnumSet<Lifecycle> plc = getLifecycles(lifecycles);
			return new Scope(prefixes, plc);
		}

		private static EnumSet<Lifecycle> getLifecycles(Lifecycle[] lifecycles) {
			if (lifecycles == null || lifecycles.length == 0) return null;
			EnumSet<Lifecycle> plc = EnumSet.noneOf(Lifecycle.class);
			for (Lifecycle lc : lifecycles) plc.add(lc);
			return plc;
		}

		boolean test(Lifecycle phase, String eventName) {
			// prefixes
			if (prefixes != null && eventName != null) {
				boolean hit = false;
				for (String p : prefixes) {
					if (p != null && !p.isEmpty() && eventName.startsWith(p)) {
						hit = true;
						break;
					}
				}
				if (!hit) return false;
			}
			// phase
			if (phases != null && (phase == null || !phases.contains(phase))) return false;

			return true;
		}
	}

	/** Per-phase split of name buckets. */
	public static final class ModeBucketsByPhase {
		private final EnumMap<Lifecycle, ModeBuckets> by = new EnumMap<>(Lifecycle.class);

		public ModeBuckets forPhase(Lifecycle p) {
			return by.computeIfAbsent(p, k -> new ModeBuckets());
		}
	}

	/**
	 * Buckets of handlers by flow outcome.
	 * - completed: runs regardless of outcome
	 * - success: runs only when no throwable
	 * - failure: runs only when throwable present
	 */
	public static final class ModeBuckets {
		public final List<Handler> completed = new ArrayList<>();
		public final List<Handler> success   = new ArrayList<>();
		public final List<Handler> failure   = new ArrayList<>();

		public boolean isEmpty() {
			return completed.isEmpty() && success.isEmpty() && failure.isEmpty();
		}
	}

	/** Component-scoped or global unmatched buckets, split per phase. */
	public static final class Unmatched {
		private final EnumMap<Lifecycle, ModeBuckets> by = new EnumMap<>(Lifecycle.class);

		public ModeBuckets forPhase(Lifecycle p) {
			return by.computeIfAbsent(p, k -> new ModeBuckets());
		}

		// Convenience views used by dispatcher (for current phase)
		public List<Handler> combined(Lifecycle p) { return forPhase(p).completed; } // alias for backwards compat
		public List<Handler> success(Lifecycle p)  { return forPhase(p).success; }
		public List<Handler> failure(Lifecycle p)  { return forPhase(p).failure; }
	}
}
