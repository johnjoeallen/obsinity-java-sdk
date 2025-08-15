# Comparison: `obsinity-java-sdk` vs Similar Java Telemetry/Tracing Frameworks

## 1) Purpose & Positioning

**Obsinity Java SDK** is a **unified, annotation‑driven telemetry layer** that is **OTEL‑first and Obsinity‑native**:

* **OTEL‑first**: Flows/Steps map directly to OTEL spans; exporters push to OTLP collectors; OTEL context propagation is honored.
* **Obsinity‑native**: You also get richer in‑JVM semantics (Flow/Step lifecycle, `@OnEvent` routing, throwable filters, ROOT batch handlers), a **native Time Series** client (planned), and **annotation‑controlled MDC** with **stack semantics** (planned).

---

## 2) Concept Mapping Across Frameworks

| Concept              | **Obsinity**                             | **OpenTelemetry Java**     | **Spring Events** | **Micrometer**             | **SLF4J/MDC**                   |
| -------------------- | ---------------------------------------- | -------------------------- | ----------------- | -------------------------- | ------------------------------- |
| Flow / Step          | `@Flow` / `@Step`                        | Parent span / child span   | N/A               | N/A                        | N/A                             |
| Attributes / Context | Push on producers, Pull in handlers      | Span attributes / baggage  | Fields on POJOs   | Tags on meters             | MDC map                         |
| Lifecycle            | FLOW\_\* & ROOT\_FLOW\_FINISHED          | Span start/end             | N/A               | N/A                        | N/A                             |
| Handlers             | `@OnEvent` (filters: name/prefix/kind/…) | Custom SpanProcessor       | `@EventListener`  | N/A                        | N/A                             |
| Cross‑service        | **HTTP client/server via MDC + headers** | W3C `traceparent`, baggage | N/A               | N/A                        | **Add/remove headers from MDC** |
| Metrics              | (Planned) Native TS + Micrometer bridge  | OTEL Metrics API           | N/A               | Native                     | N/A                             |
| Export               | OTEL exporters + Obsinity TS (planned)   | OTEL exporters             | N/A               | Prometheus/OTEL registries | Appenders/encoders              |

---

## 3) Feature Matrix (Now → Planned) vs Peers

| Feature                                          | **Obsinity (now)**                   | **Obsinity (planned)**                                   | OTEL Java SDK   | Spring Events | Micrometer | SLF4J/MDC                     |
| ------------------------------------------------ | ------------------------------------ | -------------------------------------------------------- | --------------- | ------------- | ---------- | ----------------------------- |
| Annotation model (Flow/Step/Handlers)            | ✅                                    | ✅                                                        | ⚠️ manual API   | ✅ (generic)   | ❌          | ❌                             |
| SpanKind mapping                                 | ✅                                    | ✅                                                        | ✅               | ❌             | ❌          | ❌                             |
| Lifecycle filters & batch (`ROOT_FLOW_FINISHED`) | ✅                                    | ✅                                                        | ❌               | ❌             | ❌          | ❌                             |
| Throwable filters (type/regex/cause)             | ✅                                    | ✅                                                        | ❌               | ❌             | ❌          | ❌                             |
| Push/Pull binding (attrs/context)                | ✅                                    | ✅                                                        | ❌               | ❌             | ❌          | ❌                             |
| **Cross‑service propagation**                    | **Via MDC‑aware HTTP client/server** | **Enhanced propagation helpers**                         | W3C propagation | ❌             | ❌          | **Manual MDC header mapping** |
| MDC integration                                  | Manual today                         | **Annotation‑controlled, stack‑based**                   | ❌               | ❌             | ❌          | Native MDC (manual)           |
| Metrics / Time Series                            | Bridges                              | **Native Obsinity TS + Micrometer/Prometheus exporters** | OTEL metrics    | ❌             | Native     | ❌                             |
| OTEL export                                      | Bridgeable                           | **First‑class exporters (traces/metrics/logs)**          | Native          | ❌             | Bridge     | N/A                           |

---

## 4) Unique Things Obsinity Brings

1. **In‑JVM telemetry router** with a trace‑like event model: flows, steps, lifecycle, and **declarative handlers**.
2. **Push → Pull ergonomics**: producers push once; handlers pull via annotations without boilerplate.
3. **Exception‑aware selection**: requireThrowable, types, subclasses, message regex, cause type.
4. **Batch handlers** for `ROOT_FLOW_FINISHED`.
5. **Cross‑service made practical**: **configured HTTP client/server** reads/writes headers from MDC using stack‑aware keys (see §6).
6. **Planned**: **native Time Series** client + **Micrometer/Prometheus exporters**, **stack‑aware MDC** via annotations, **first‑class OTEL exporters**.

---

## 5) OTEL‑First, Obsinity‑Native (Compatibility)

**Principle:** *“OTEL‑first interoperability, Obsinity‑native power.”*

* **Span parity**: `@Flow` → parent span, `@Step` → child; `@Kind` maps to OTEL `SpanKind`.
* **Attribute mapping**: event attributes/context → span attributes; Obsinity extras under `obs.*` to avoid collisions.
* **Metrics alignment**: TS counters/gauges/histograms map to OTEL metric types; **Micrometer registry/exporters** supported (see §7).
* **Logs**: Optional OTEL logs exporter for handler summaries/failures.
* **Context propagation**: OTEL W3C context is primary; **MDC is layered** for app‑level keys (tenant, correlationId, etc.).
* **Exporter plugability**: OTLP to any collector, native TS to Obsinity store, or both—**no lock‑in**.
* **Graceful degradation**: Works standalone; if OTEL is present, integrates automatically.

---

## 6) Cross‑Service Boundaries (MDC‑Aware HTTP Client/Server)

**Goal:** carry business context (correlationId, tenant, region) **and** tracing context across HTTP without custom plumbing.

### Outbound (client) – write headers from MDC

* A **client interceptor** reads configured MDC keys and writes headers, alongside OTEL’s `traceparent`.

```java
class MdcHeaderClientInterceptor implements ClientHttpRequestInterceptor {
  private final List<String> keys = List.of("cid","tenant","region");
  public ClientHttpResponse intercept(HttpRequest req, byte[] body, ClientHttpRequestExecution ex) throws IOException {
    var ctx = MDC.getCopyOfContextMap();
    if (ctx != null) for (var k : keys) {
      var v = ctx.get(k);
      if (v != null) req.getHeaders().add("X-" + k, v);
    }
    // OTEL propagation happens via OTel HttpClient propagators
    return ex.execute(req, body);
  }
}
```

### Inbound (server) – read headers into MDC (and TelemetryHolder)

* A **server filter** reads `X-…` headers into MDC (and optionally into holder context/attrs), restoring MDC on exit.

```java
class MdcHeaderServerFilter implements Filter {
  public void doFilter(ServletRequest r, ServletResponse s, FilterChain chain) throws IOException, ServletException {
    Map<String,String> snapshot = MDC.getCopyOfContextMap();
    try {
      HttpServletRequest req = (HttpServletRequest) r;
      putIfPresent("cid", req, "X-cid");
      putIfPresent("tenant", req, "X-tenant");
      chain.doFilter(r, s);
    } finally {
      MDC.setContextMap(snapshot != null ? snapshot : Collections.emptyMap());
    }
  }
  private static void putIfPresent(String mdcKey, HttpServletRequest req, String header) {
    String v = req.getHeader(header);
    if (v != null) MDC.put(mdcKey, v);
  }
}
```

> With the **planned `@PushMdc` and `@PushAllMdc`**, these keys are **pushed/popped with stack semantics** at Flow/Step boundaries so MDC never leaks across scopes.

---

## 7) Micrometer / Prometheus Compatibility

Two complementary paths:

### A) **Micrometer Registry → Obsinity TS** (planned)

* Provide `MeterRegistry` that writes to Obsinity TS.
* Existing Micrometer code continues to work, Prometheus scraping can be kept in parallel if desired.

```java
MeterRegistry registry = ObsinityMicrometerRegistry.create(obsTsClient);
Counter.builder("orders").tag("tenant","acme").register(registry).increment();
```

### B) **Obsinity TS → Prometheus Exporter** (planned)

* Sidecar or embedded endpoint that exposes a `/metrics` Prometheus format, backed by Obsinity TS counters/gauges.
* Lets Prometheus scrape without changing your code.

> Either way, **Micrometer/Prometheus can coexist** with Obsinity’s native TS and with OTEL metrics.

---

## 8) Integration Points (All Together)

| Integration              | How Obsinity Handles It                                                                         |
| ------------------------ | ----------------------------------------------------------------------------------------------- |
| **OTEL Export**          | First‑class exporters (traces/metrics/logs) → OTLP Collector                                    |
| **Obsinity Time Series** | Native client with async batching, tags derived from attributes/context/MDC                     |
| **Micrometer**           | Registry targeting Obsinity TS; optional Prometheus exporter endpoint                           |
| **Cross‑service**        | HTTP client/server interceptors populate headers from MDC; OTEL propagators carry `traceparent` |
| **Logging**              | (Planned) `@PushMdc` / `@PushAllMdc` with stack semantics; redaction & limits                   |
| **Spring Boot**          | Auto-config: scanners, dispatch bus, interceptors, OTEL & Micrometer wiring                     |

---

## 9) Gaps & Considerations

* **Dashboards**: Prometheus/Grafana have mature ecosystems; Obsinity dashboards may require initial setup.
* **Language boundaries**: Non‑Java services should rely on OTEL propagation to interop; MDC header convention must be shared.
* **Cardinality**: MDC and tag cardinality guards needed (planned defaults and warnings).

---

## 10) Roadmap (Concrete)

* **MDC annotations with stack semantics** (`@PushMdc`, `@PushAllMdc`) + async propagation helpers.
* **Obsinity Time Series client** (counters, timers, histograms) + **Micrometer registry** and **Prometheus exporter**.
* **OTEL exporters** (traces/metrics/logs) with auto-mapping from Flow/Step/Attributes/Throwable.
* **HTTP client/server kits**: starters for WebClient/RestTemplate/OkHttp/Servlet filters and Spring WebFlux filters.

---

### TL;DR

* Obsinity is **OTEL‑first** (zero‑friction interop) and **Obsinity‑native** (extra power when you want it).
* **Cross‑service context**: configure HTTP client/server once; MDC + OTEL propagators handle the rest.
* **Metrics**: keep Micrometer/Prometheus, or go native TS (or both) — **no lock‑in**.
