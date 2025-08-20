# Obsinity Telemetry Developer Guide (Full, regenerated)

---

## Cheat Sheet

* **`@Flow`** — starts a new flow (root or subflow); **sends** lifecycle signals: started, completed, failed.
* **`@Step`** — **records** a step inside the current flow; auto‑promoted to a flow when no flow is active.
* **`@Kind`** — sets OTEL `SpanKind` (`SERVER`, `CLIENT`, `PRODUCER`, `CONSUMER`, `INTERNAL`).
* **`@OnEvent`** — declares an event handler; filter by name/prefix, lifecycle, kind, throwable filters, and **mode**.
* **`@OnOutcome`** — handler hook that runs once per flow outcome (SUCCESS or FAILURE); ideal for sinks/taps.
* **`@PushAttribute`** — client‑side: **save** a parameter into event attributes.
* **`@PullAttribute`** — handler‑side: read a **saved** attribute into a parameter.
* **`@PullContextValue`** — handler‑side: read a transient context value from the event context.
* **Modes** — `SUCCESS`, `FAILURE`.
* **Unmatched** — no blank selector; when dot‑chop ends empty, call **`@FlowNotMatched`**.

---

## Step‑by‑Step Examples

### 1) Hello Flow — minimal producer

```java
@Flow(name = "demo.hello")
public void hello(@PushAttribute(name = "user.id") String userId) {
  // business logic
}
```

**What happens**

1. A new flow starts (ROOT or subflow depending on context) and **sends** `FLOW_STARTED`.
2. Attribute `user.id` is **saved** on the flow.
3. On return, `FLOW_FINISHED` is **sent** (SUCCESS or FAILURE based on exceptions).

---

### 2) Flow with steps and attribute/context saving

```java
@Flow(name = "order.create")
public String createOrder(
  @PushAttribute(name = "user.id") String userId,
  @PushAttribute(name = "order.total") BigDecimal total
) {
  verifyUser(userId);
  String reservationId = reserveInventory();
  TelemetryContext.putContext("reservation.id", reservationId); // transient
  return reservationId;
}

@Step(name = "verify.user")
void verifyUser(String userId) { /* record validation */ }

@Step(name = "reserve.inventory")
String reserveInventory() { /* record reservation */ return UUID.randomUUID().toString(); }
```

**Notes**

* `@Step` **records** child work. If no active flow, the step is auto‑promoted (see `@OrphanAlert`).
* Anything you **save** via `@PushAttribute` or `TelemetryContext.putAttr(...)` becomes available to handlers.
* Context via `TelemetryContext.putContext(...)` is transient and bindable with `@PullContextValue`.

---

### 3) Auto‑promoted step (no active flow)

```java
@Step(name = "cache.warmup")
void warmup() { /* will auto‑promote to a flow if called stand‑alone */ }
```

**Tip**: Use `@OrphanAlert` to tune the log level when promotion occurs.

---

### 4) Handling SUCCESS vs FAILURE

```java
@TelemetryEventHandler
class OrderHandlers {

  @OnEvent(prefix = "order.", lifecycle = Lifecycle.FLOW_FINISHED, mode = Mode.SUCCESS)
  void onSucceeded(@PullAttribute("user.id") String userId, @AllAttrs Map<String,Object> attrs) {
    // write to storage, publish metrics, etc.
  }

  @OnEvent(prefix = "order.", lifecycle = Lifecycle.FLOW_FINISHED, mode = Mode.FAILURE)
  void onFailed(@Err Throwable error, @PullAttribute("user.id") String userId) {
    // alert, dead-letter, retry planning, etc.
  }
}
```

**Guidance**: Prefer **separate** handlers for success and failure.

---

### 5) One hook for either outcome — `@OnOutcome`

```java
@TelemetryEventHandler
class OutcomeTaps {
  @OnOutcome(prefix = "order.")
  void shipToTimeseries(@PullAttribute("user.id") String userId,
                        @AllAttrs Map<String,Object> attrs,
                        Outcome outcome) { // Outcome.SUCCESS or Outcome.FAILURE
    // idempotent sink; runs once per flow completion regardless of outcome
  }
}
```

**When to use**: cross‑cutting sinks/taps (e.g., ship to time‑series, audit) where duplication must be avoided and both outcomes matter.

---

### 6) Prefix matching (dot‑chop) without blank selector

```java
@TelemetryEventHandler
class BillingHandlers {
  @OnEvent(name = "billing.invoice.paid", lifecycle = Lifecycle.FLOW_FINISHED, mode = Mode.SUCCESS)
  void onInvoicePaid(/* ... */) { }

  @OnEvent(prefix = "billing.invoice", lifecycle = Lifecycle.FLOW_FINISHED, mode = Mode.FAILURE)
  void onAnyInvoiceFailure(@Err Throwable err) { }
}
```

**Resolution order** for `billing.invoice.paid`: try exact, then `billing.invoice`, then `billing`. If nothing matches, it’s *not matched*.

---

### 7) Binding parameters cleanly

```java
@TelemetryEventHandler
class BindExamples {
  @OnEvent(name = "order.create", lifecycle = Lifecycle.FLOW_FINISHED, mode = Mode.SUCCESS)
  void bindAll(@PullAttribute("user.id") String userId,
               @AllAttrs Map<String,Object> attrs,
               @PullContextValue("reservation.id") String reservationId) { }

  @OnEvent(prefix = "order.", lifecycle = Lifecycle.FLOW_FINISHED, mode = Mode.FAILURE)
  void bindError(@Err Throwable error) { }
}
```

---

### 8) Unmatched flows — `@FlowNotMatched`

```java
@TelemetryEventHandler
class Fallbacks {
  @FlowNotMatched
  void onUnmatched(String flowName) {
    // central diagnostic when no handler matched after dot‑chop
  }
}
```

---

## Annotation Reference (concise)

### Core (structure & selection)

* **`@Flow`** — starts a **flow**; **sends** lifecycle signals.
* **`@Step`** — **records** a step inside the active flow; auto‑promotes when needed.
* **`@Kind`** — sets OTEL `SpanKind`; default `INTERNAL`.
* **`@OnEvent`** — handler selection by name/prefix, lifecycle, kind, throwable filters, and **mode**.
* **`@OnOutcome`** — runs once at flow completion for either outcome; provides `Outcome` to the method.
* **`@TelemetryEventHandler`** — marks a handler class for scanning and validation.
* **`@OrphanAlert`** — controls log level for auto‑promoted steps.

### Modes

* **`SUCCESS`** — event completed without error.
* **`FAILURE`** — event ended with an exception; bind it via `@Err`.

### Lifecycle

`ROOT_FLOW_STARTED`, `FLOW_STARTED`, `STEP_STARTED`, `STEP_FINISHED`, `FLOW_FINISHED`, `ROOT_FLOW_FINISHED`.

> `OnEventLifecycle` applies at **class level**.

---

## Handler Selection & Routing

* **Dot‑chop prefix matching**: `a.b.c` → try `a.b.c`, then `a.b`, then `a`.
* **No blank selector**: once the chop ends empty, treat as *not matched* → `@FlowNotMatched`.
* **No wildcard mixing**: prefer explicit prefixes + `mode`.
* **Validation**: the scope/handler validator rejects invalid intersections and mixed unmatched strategies.

---

## Flow & Step Semantics

* `@Flow` **sends** multiple lifecycle signals; may be root or subflow.
* `@Step` **records** child work; step attributes/context carry into the resulting event.
* Auto‑promotion occurs when a step runs without an active flow; govern logs with `@OrphanAlert`.

---

## Parameter Binding

* **Write** (producer): `@PushAttribute(name|value = "key")` or `TelemetryContext.putAttr(...)` to **save** attributes.
* **Read** (handler): `@PullAttribute`, `@AllAttrs`, `@PullContextValue`, and `@Err` for exceptions.
* **Aliases**: `@Attr` and `@Attribute(name = "...")` for handler parameters.

---

## Error Handling & Fallbacks

* Prefer **separate** SUCCESS/FAILURE handlers.
* Use **`@OnOutcome`** for a single, once‑per‑flow hook that applies to both outcomes.
* A global fallback receiver records a diagnostic when **no** handler ran.

---

## OTEL Mapping & Compatibility

* **Span mapping**: `@Flow` ↔ span (root/sub); `@Step` ↔ child span (or flow when auto‑promoted).
* **Kind**: `@Kind` → `SpanKind`; default `INTERNAL`.
* **Context propagation**: bridge thread‑locals ↔ OTEL Context.
* **Receivers**: OTLP ingestion can be translated into internal events.

---

## Spring Integration (brief)

* Interceptors around `@Flow`/`@Step` to handle start/finish and attribute saving.
* Scanning of `@TelemetryEventHandler`; registration of `@OnEvent`/`@OnOutcome` methods.
* A validator runner ensures scopes/handlers are consistent after scanning.

---

## Configuration Keys (stable)

* `obsinity.flow.orphan-alert` — default log level for auto‑promoted steps.
* `obsinity.handlers.validation.strict` — enable strict validation.
* `obsinity.storage.retention.days` — retention window.
* `obsinity.partitions.rotate.cron` — rotation schedule.
* `obsinity.export.otlp.*` — OTLP exporter options.

> **Note:** This guide intentionally **omits SQL** and build/module details.

---
