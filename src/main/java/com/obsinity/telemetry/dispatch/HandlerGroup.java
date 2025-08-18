package com.obsinity.telemetry.dispatch;

import com.obsinity.telemetry.annotations.DispatchMode;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;

import java.util.*;

/**
 * Per-component registry of handlers discovered by the scanner.
 *
 * Structure:
 * - tiers: dot-chop tiers for @OnEvent (exact name → nearest ancestor fallback).
 * - unmatched: component-scoped @OnUnMatchedEvent(scope=COMPONENT).
 * - globalUnmatched: global @OnUnMatchedEvent(scope=GLOBAL).
 * - taps: @OnEveryEvent (additive; always run).
 */
public final class HandlerGroup {

	/** Human-friendly component name (e.g., bean class simple name). */
	public final String componentName;

	/** Dot-chop tiers: event name → mode buckets. Exact name first, then ancestors during dispatch. */
	private final Map<String, ModeBucketsByPhase> tiers = new HashMap<>();

	/** Component-scoped unmatched buckets (invoked when this group had no @OnEvent match for an in-scope event). */
	public final Unmatched unmatched = new Unmatched();

	/** Global unmatched buckets (invoked only when no @OnEvent matched anywhere). */
	public final Unmatched globalUnmatched = new Unmatched();

	/** Additive taps (@OnEveryEvent). */
	public final Taps taps = new Taps();

	public HandlerGroup(String componentName) {
		this.componentName = Objects.requireNonNull(componentName);
	}

    /* =========================
       Registration (scanner)
       ========================= */

	public void registerOnEvent(String exactName, Lifecycle phase, DispatchMode mode, Handler h) {
		tiers.computeIfAbsent(exactName, k -> new ModeBucketsByPhase())
			.forPhase(phase)
			.add(mode, h);
	}

	public void registerComponentUnmatched(Lifecycle phase, DispatchMode mode, Handler h) {
		unmatched.forPhase(phase).add(mode, h);
	}

	public void registerGlobalUnmatched(Lifecycle phase, DispatchMode mode, Handler h) {
		globalUnmatched.forPhase(phase).add(mode, h);
	}

	public void registerTap(DispatchMode mode, Handler h, Lifecycle... phases) {
		if (phases == null || phases.length == 0) {
			// If scanner didn't pre-split by phase, add to all phases (common case is FLOW_FINISHED)
			taps.any.add(mode, h);
			return;
		}
		for (Lifecycle p : phases) {
			taps.forPhase(p).add(mode, h);
		}
	}

    /* =========================
       Query (dispatcher)
       ========================= */

	/**
	 * Dot-chop selection for this component:
	 * - Try exact event name tier; if no eligible handlers in that tier, chop last segment and try again.
	 * - Returns the first (nearest) tier that has ANY handlers registered for the given phase, regardless of eligibility;
	 *   eligibility is checked by the dispatcher (mode + filters) before invocation.
	 */
	public ModeBuckets findNearestEligibleTier(Lifecycle phase, String eventName,
											   TelemetryHolder holder, boolean failed, Throwable error) {
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

	/** Per-phase split of mode buckets. */
	public static final class ModeBucketsByPhase {
		private final EnumMap<Lifecycle, ModeBuckets> by = new EnumMap<>(Lifecycle.class);

		public ModeBuckets forPhase(Lifecycle p) {
			return by.computeIfAbsent(p, k -> new ModeBuckets());
		}
	}

	/** Buckets of handlers by dispatch mode. */
	public static final class ModeBuckets {
		public final List<Handler> combined = new ArrayList<>();
		public final List<Handler> success  = new ArrayList<>();
		public final List<Handler> failure  = new ArrayList<>();

		void add(DispatchMode mode, Handler h) {
			switch (mode) {
				case SUCCESS -> success.add(h);
				case FAILURE -> failure.add(h);
				default      -> combined.add(h);
			}
		}

		public boolean isEmpty() {
			return combined.isEmpty() && success.isEmpty() && failure.isEmpty();
		}
	}

	/** Component-scoped or global unmatched buckets, split per phase. */
	public static final class Unmatched {
		private final EnumMap<Lifecycle, ModeBuckets> by = new EnumMap<>(Lifecycle.class);

		public ModeBuckets forPhase(Lifecycle p) {
			return by.computeIfAbsent(p, k -> new ModeBuckets());
		}

		// Convenience views used by dispatcher (for current phase)
		public List<Handler> combined(Lifecycle p) { return forPhase(p).combined; }
		public List<Handler> success (Lifecycle p) { return forPhase(p).success;  }
		public List<Handler> failure (Lifecycle p) { return forPhase(p).failure;  }
	}

	/** Taps for @OnEveryEvent: additive, split per phase. */
	public static final class Taps {
		private final EnumMap<Lifecycle, ModeBuckets> by = new EnumMap<>(Lifecycle.class);
		/** Used when scanner does not split by phase; dispatcher should choose correct lists. */
		public final ModeBuckets any = new ModeBuckets();

		public ModeBuckets forPhase(Lifecycle p) {
			return by.computeIfAbsent(p, k -> new ModeBuckets());
		}

		public List<Handler> combined(Lifecycle p) {
			ModeBuckets mb = by.get(p);
			return mb == null ? any.combined : mb.combined;
		}
		public List<Handler> success(Lifecycle p) {
			ModeBuckets mb = by.get(p);
			return mb == null ? any.success : mb.success;
		}
		public List<Handler> failure(Lifecycle p) {
			ModeBuckets mb = by.get(p);
			return mb == null ? any.failure : mb.failure;
		}
	}
}
