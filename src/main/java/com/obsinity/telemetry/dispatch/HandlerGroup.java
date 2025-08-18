package com.obsinity.telemetry.dispatch;

import com.obsinity.telemetry.annotations.DispatchMode;
import com.obsinity.telemetry.annotations.EventScope;
import com.obsinity.telemetry.model.Lifecycle;
import com.obsinity.telemetry.model.TelemetryHolder;
import io.opentelemetry.api.trace.SpanKind;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-component registry of handlers discovered by the scanner.
 *
 * Structure:
 * - tiers: dot-chop tiers for @OnEvent (exact name → nearest ancestor fallback).
 * - unmatched: component-scoped @OnUnMatchedEvent(scope=COMPONENT).
 * - globalUnmatched: global @OnUnMatchedEvent(scope=GLOBAL).
 * - taps: @OnEveryEvent (additive; always run).
 *
 * Optional: component-level EventScope filter (prefix + lifecycle + kind + error mode).
 */
public final class HandlerGroup {

	/** Human-friendly component name (e.g., bean class simple name). */
	public final String componentName;

	/** Optional component-level scope filter; null/ALLOW_ALL means no filtering. */
	private Scope scope = Scope.allowAll();

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

	public HandlerGroup(String componentName, Scope scope) {
		this.componentName = Objects.requireNonNull(componentName);
		this.scope = (scope == null ? Scope.allowAll() : scope);
	}

	/** Accessor used by logging/diagnostics to identify the handler component. */
	public String componentName() {
		return this.componentName;
	}

	/** Configure/override the component-level scope. Null means "allow all". */
	public void setScope(Scope scope) {
		this.scope = (scope == null ? Scope.allowAll() : scope);
	}

	/** True if this group should see the given event (component-level filter). */
	public boolean isInScope(Lifecycle phase, String eventName, TelemetryHolder holder) {
		return scope.test(phase, eventName, holder);
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

	/**
	 * Compiled component-level scope based on {@link EventScope}.
	 * All configured predicates are ANDed (prefix AND lifecycle AND kind AND error mode).
	 */
	public static final class Scope {
		private final String[] prefixes;           // null/empty = any
		private final EnumSet<Lifecycle> phases;   // null = any
		private final EnumSet<SpanKind> kinds;     // null = any
		private final EventScope.ErrorMode errorMode; // never null

		private Scope(String[] prefixes,
					  EnumSet<Lifecycle> phases,
					  EnumSet<SpanKind> kinds,
					  EventScope.ErrorMode errorMode) {
			this.prefixes = (prefixes == null || prefixes.length == 0) ? null : prefixes;
			this.phases = (phases == null || phases.isEmpty()) ? null : phases;
			this.kinds = (kinds == null || kinds.isEmpty()) ? null : kinds;
			this.errorMode = (errorMode == null ? EventScope.ErrorMode.ANY : errorMode);
		}

		public static Scope allowAll() {
			return new Scope(null, null, null, EventScope.ErrorMode.ANY);
		}

		public static Scope of(String[] prefixes,
							   Lifecycle[] lifecycles,
							   SpanKind[] kinds,
							   EventScope.ErrorMode errorMode) {
			EnumSet<Lifecycle> plc = null;
			if (lifecycles != null && lifecycles.length > 0) {
				plc = EnumSet.noneOf(Lifecycle.class);
				for (Lifecycle lc : lifecycles) plc.add(lc);
			}
			EnumSet<SpanKind> pk = null;
			if (kinds != null && kinds.length > 0) {
				pk = EnumSet.noneOf(SpanKind.class);
				for (SpanKind k : kinds) pk.add(k == null ? SpanKind.INTERNAL : k);
			}
			return new Scope(prefixes, plc, pk, errorMode);
		}

		boolean test(Lifecycle phase, String eventName, TelemetryHolder holder) {
			// prefixes
			if (prefixes != null && eventName != null) {
				boolean hit = false;
				for (String p : prefixes) {
					if (p != null && !p.isEmpty() && eventName.startsWith(p)) { hit = true; break; }
				}
				if (!hit) return false;
			}
			// phase
			if (phases != null && (phase == null || !phases.contains(phase))) return false;

			// kind
			if (kinds != null) {
				SpanKind sk = (holder == null || holder.kind() == null) ? SpanKind.INTERNAL : holder.kind();
				if (!kinds.contains(sk)) return false;
			}

			// error mode
			boolean failed = holder != null && holder.throwable() != null;
			return switch (errorMode) {
				case ANY -> true;
				case ONLY_ERROR -> failed;
				case ONLY_NON_ERROR -> !failed;
			};
		}
	}

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
