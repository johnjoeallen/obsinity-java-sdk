# 1) Native Obsinity Time‑Series Client

## Goals

* Low‑overhead ingestion of counters, gauges, histograms, timers into Obsinity TS backends.
* First‑class tags (attrs) aligned with your telemetry attributes.
* Async batching with backpressure and graceful drop policies.

## Core API (fluent + minimal)

```java
ObsinityTS ts = ObsinityTS.create(ObsinityTS.Config.builder()
    .endpoint("https://obsinity-ts:8443")
    .apiKey(System.getenv("OBSINITY_API_KEY"))
    .batchBytes(256 * 1024)
    .flushInterval(Duration.ofMillis(500))
    .retryPolicy(Retry.fixed(3, Duration.ofMillis(200)))
    .build());

// Counters / timers
ts.counter("checkout.orders").tags("tenant", "acme").inc();
ts.timer("http.server.latency").tags("route", "/api/checkout").record(Duration.ofMillis(123));

// Histograms
try (var h = ts.histogram("db.query.time").tags("db","primary").start()) {
  // run DB call
}

// Gauges (function‑backed)
ts.gauge("queue.depth").tags("queue", "billing").observe(() -> queue.size());
```

### Data Model

* **Metric types:** counter, gauge, timer, histogram, distribution.
* **Tags:** `Map<String,String>` (small, bounded cardinality) derived from `TelemetryHolder` attrs when called inside a flow/step (see §3 MDC).
* **Timestamp:** client clock; optional server‑side upsert with monotonic protection.

### Batching & Transport

* Lock‑free ring buffer → encoder (protobuf/flatbuffer) → HTTP/2 streaming.
* Payload shaping: `batchBytes` or `metricsPerBatch` thresholds, time‑based flush fallback.
* Backpressure modes: `DROP_OLDEST`, `BLOCK`, `DROP_NEW`; metrics sampling knob at meter level.

### Interop (Micrometer bridge)

* Optional: `MeterRegistry` that forwards to `ObsinityTS` (one‑way), so existing Micrometer apps can adopt without code changes.

```java
MeterRegistry registry = ObsinityMicrometerRegistry.create(ts);
Counter.builder("orders").tag("tenant","acme").register(registry).increment();
```

### Resilience & Security

* Retries with jitter; idempotent write tokens for at‑least‑once.
* mTLS / API keys; PII tag filters and redaction rules (prefix/suffix, regex).

---

# 2) OpenTelemetry Export (Traces, Metrics, Logs)

## Goals

* Zero‑surprise OTEL compatibility for teams already standardizing on OTEL backends.
* Map Obsinity **flows/steps** to OTEL **spans**; attributes/context → span attributes; handlers can still run locally.

## Trace Exporter (SpanExporter)

```java
public final class ObsinitySpanExporter implements SpanExporter {
  private final OtlpGrpcSpanExporter delegate; // or HTTP exporter
  // map TelemetryHolder → SpanData
  public CompletableResultCode export(Collection<SpanData> spans) { ... }
}
```

### Mapping

* Flow → parent span; Step → child span (`SpanKind` honored).
* Event attributes/context → span attributes (namespaced: `obs.` reserved).
* Exceptions: `Throwable` → `StatusCode.ERROR`, `exception.*` attributes per spec.
* Resource: service.name/version/env from config or Spring Boot props.

### Metrics Export

* Either forward TS client via OTEL metrics bridge (`SdkMeterProvider`) **or** expose ObsinityTS as an OTEL `MetricExporter`.
* If both are enabled, deduplicate (one source of truth flag).

### Logs Export

* Optional: map handler emissions or `@OnEvent` summaries to OTEL Logs via `OtlpGrpcLogRecordExporter`.

### Config (Spring Boot)

```yaml
obsinity.telemetry:
  otel:
    traces.enabled: true
    metrics.enabled: false
    logs.enabled: false
    otlp.endpoint: https://otel-collector:4317
    resource:
      service.name: checkout-svc
      service.version: 1.12.0
```

---

# 3) Annotation‑Controlled **MDC** with Stack Semantics

## Goals

* Populate SLF4J MDC automatically at **Flow/Step entry**, clear on **exit**, **nested‑scope aware** (stack).
* Same behavior as `TelemetryHolder` push/pop; works with threads, executors, virtual threads.

## New Annotations

```java
@Target({ElementType.PARAMETER, ElementType.METHOD})
public @interface PushMdc {
  String name();               // MDC key
  String value() default "";   // literal, or empty to pull from attribute/context (see below)
  Source source() default Source.ATTRIBUTE; // ATTRIBUTE | CONTEXT | LITERAL
  boolean omitIfNull() default true;
  boolean redact() default false; // use configured redactor
}

@Target({ElementType.METHOD})
public @interface PushAllMdc {
  Source source() default Source.ATTRIBUTE; // ATTRIBUTE or CONTEXT
  String[] include() default {}; // allow-list patterns
  String[] exclude() default {"password","secret","token","*key*"}; // deny-list patterns
}
```

### Semantics

* At `@Flow/@Step` entry, an interceptor:

    * Builds a **delta map** of MDC keys to push (from annotations on method params or method).
    * **Pushes** each key, **remembering the previous value** on a per‑thread stack.
* At exit (normal or exceptional), **pop** and restore previous values.
* Reentrant/nested calls compose: LIFO restoration.
* Works even if a logger is called inside handler or downstream library.

### Usage Examples

```java
@Flow(name = "checkout.process")
@PushMdc(name="tenant", source=ATTRIBUTE, value="tenant.id")    // pull from attributes
@PushMdc(name="cid",    source=CONTEXT,   value="correlationId")// pull from context
public Receipt checkout(@PushAttribute("tenant.id") String tenant,
                        @PushContextValue("correlationId") String cid) { ... }

@Step(name = "payment.authorize")
@PushAllMdc(source=ATTRIBUTE, include={"order.*","payment.method"}, exclude={"payment.card.*"})
public void authorize(@PushAttribute("order.id") String orderId) { ... }
```

### Integration with `TelemetryHolder`

* When inside a Flow/Step, `PushMdc` with `source=ATTRIBUTE` reads from `holder.attributes()`.
* `source=CONTEXT` reads from `holder.getEventContext()`.
* `source=LITERAL` uses the annotation’s `value`.

### Async & Threading

* **MDC propagation** wrappers for:

    * `Executor`, `ExecutorService`, `ScheduledExecutorService`
    * `CompletableFuture` helpers (`thenApplyAsync` wrappers)
    * Reactor (context bridge) — optional module `obsinity-reactor`
* Snapshot current MDC map + `TelemetryHolder` context, capture to task, restore on run, ensure try/finally pop.

### Redaction & Limits

* Built‑in redactor for marked keys: mask patterns, keep last 4 chars, etc.
* Cardinality guard: capped set of MDC keys; long values truncated with suffix `…`.

### Config

```yaml
obsinity.telemetry.mdc:
  enabled: true
  stackSemantics: true
  maxKeys: 64
  maxValueLength: 256
  redact:
    default: true
    patterns: ["password", "secret", "token", "authorization", "*key*"]
```

---

# 4) Wiring & Module Layout

```
obsinity-java-sdk
  ├── obsinity-telemetry-core          # Flow/Step, Holder, Dispatch, Annotations
  ├── obsinity-telemetry-spring        # Spring AOP interceptors, scanner, MDC aspect
  ├── obsinity-ts-client               # Native time-series client + Micrometer bridge
  ├── obsinity-otel-export             # OTEL Span/Metrics/Logs exporters
  ├── obsinity-reactor (optional)      # Reactor Context <-> MDC/Holder bridge
  └── obsinity-examples                # sample apps
```

* Keep `core` free of Spring; Spring bits live in `-spring`.
* Exporters & clients are optional dependencies.

---

# 5) Migration & Backward Compatibility

* **Non‑breaking**: Annotations are opt‑in. Existing `@Flow/@Step/@OnEvent` apps continue to work.
* MDC:

    * Off by default; enable via config.
    * If multiple MDC sources exist, our interceptor only overrides keys it pushed and restores exactly those.
* OTEL export:

    * If an app already uses OTEL, allow selecting our exporters **or** keep theirs; avoid double‑export via a guard bean.

---

# 6) Performance & Footguns

* **Allocation budget**: avoid per‑event maps by reusing small object pools; use primitive hash maps for tag encoding.
* **Async backpressure**: visible metrics (`obsinity.ts.dropped`) for dropped/blocked states.
* **PII/Secrets**: redaction defaults on; allow explicit safe‑list.
* **Cardinality**: warn if tag key/value cardinality explodes (top‑K counters).
* **Virtual threads**: ThreadLocal MDC works in Loom; still push/pop in try/finally on entry/exit.

---

# 7) Minimal Examples (end‑to‑end)

## a) Flow + MDC + OTEL export

```java
@SpringBootApplication
public class App {
  @Bean SpanExporter exporter() { return new ObsinitySpanExporter("https://collector:4317"); }
  @Bean TelemetryMdcAspect mdcAspect() { return new TelemetryMdcAspect(); }
}
```

```java
@Kind(SpanKind.SERVER)
public class Checkout {

  @Flow(name = "checkout.process")
  @PushMdc(name="tenant", source=ATTRIBUTE, value="tenant.id")
  @PushMdc(name="cid", source=CONTEXT, value="correlationId")
  public void checkout(
      @PushAttribute("tenant.id") String tenant,
      @PushContextValue("correlationId") String cid) {
    validate(tenant);
    pay();
  }

  @Step(name = "checkout.validate") public void validate(String t) { ... }

  @Step(name = "checkout.pay") @Kind(SpanKind.CLIENT) public void pay() { ... }
}
```

## b) Batch handler + TS metrics

```java
@TelemetryEventHandler
@Component
@RequiredArgsConstructor
class CheckoutHandlers {
  private final ObsinityTS ts;

  @OnEvent(name = "checkout.process", lifecycle = {Lifecycle.ROOT_FLOW_FINISHED})
  public void summarize(List<TelemetryHolder> flows) {
    ts.counter("checkout.roots").inc(flows.size());
    ts.histogram("checkout.stepsPerFlow").record(
        flows.stream().mapToInt(f -> f.getChildren().size()).average().orElse(0));
  }
}
```
