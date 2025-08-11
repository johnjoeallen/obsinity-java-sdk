# Obsinity Telemetry Developer Guide

**Annotations:** `@Flow`, `@Step`, `@Kind`, `@PromotionAlert`, `@Attribute`

This guide explains:

* How to instrument code using Obsinity’s annotations.
* How **Step promotion** works.
* How to bind method parameters to telemetry attributes.
* How to set attributes programmatically via `TelemetryContext`.
* How to plug in custom receivers using lifecycle callbacks.
* **Attribute lifecycle** from event creation to storage.

It is designed for developers who want rich, **OpenTelemetry-aligned** instrumentation with minimal boilerplate.

---

## Overview

### Key Concepts

| Term                  | Description                                                                                                  |
| --------------------- | ------------------------------------------------------------------------------------------------------------ |
| **Flow event**        | The top-level telemetry unit for a logical operation (e.g., `checkout.process`).                             |
| **Child event**       | An event emitted by a `@Step` inside an active Flow.                                                         |
| **Promotion**         | When a `@Step` is called with **no active Flow**, it is **promoted** to a Flow event.                        |
| **Promotion warning** | One log message emitted at promotion; level controlled by `@PromotionAlert`.                                 |
| **Attributes**        | Key/value pairs attached to the current telemetry event; used for search, filtering, and analytics.          |
| **Telemetry Context** | Programmatic API for adding attributes to the active telemetry scope without knowing if it’s a Flow or Step. |

---

## Annotations

### `@Flow` *(method only)*

Starts a **Flow event** and activates context so nested `@Step` calls become **child events**.

**Naming (OTEL style):**

* Lowercase, dot-separated, stable (e.g., `checkout.process`, `inventory.reserve`, `http.get.user`).
* Should reflect the **logical operation**, not implementation details.
* Can inherit `@Kind` from the class or override at the method level.

**Attribute binding:**

* `@Flow` works with `@Attribute` parameters to add key/value pairs immediately when the Flow starts.

**Example**

```java
@Flow(name = "checkout.process")
@Kind(spanKind = SpanKind.SERVER)
public Receipt checkout(
    @Attribute("order.id") String orderId,
    @Attribute("order.total") BigDecimal total
) {
    validate(orderId, total); // child event
    charge(orderId, total);   // child event
    return new Receipt();
}
```

---

### `@Step` *(method only)*

Represents a **unit of work** within a Flow.

* **Inside an active Flow** → emits a **child event**.
* **With no active Flow** → promoted to a Flow event:

    * Name = step’s OTEL-style name.
    * Logs a promotion warning (level from `@PromotionAlert`, or **ERROR** if absent).
    * Runs exactly like a Flow event from that point onward.

**Example**

```java
@Step(name = "checkout.validate")
public void validate(@Attribute("order.id") String orderId) {
    // Child event (in Flow) or Flow event (promotion)
}
```

---

### `@Kind` *(class or method)*

Sets default telemetry kind / OTEL `SpanKind`:

* `SERVER` — Server-side processing.
* `CLIENT` — Outgoing request to another service.
* `PRODUCER` / `CONSUMER` — Messaging.
* `INTERNAL` — Internal work with no remote call.

**Usage:**

* Apply to the **class** for defaults.
* Override at the **method** level when needed.

**Example**

```java
@Kind(spanKind = SpanKind.CLIENT)
public class PaymentClient {
    @Flow(name = "payment.charge")
    public void charge() { /* ... */ }
}
```

---

### `@PromotionAlert` *(only on `@Step` methods)*

Controls the **log level** for the promotion warning when a `@Step` is promoted.

* **Only applies** if there’s no active Flow.
* **Levels:** `StandardLevel.INFO`, `StandardLevel.WARNING`, `StandardLevel.SEVERE` (ERROR).
* **Default:** If absent, logs at **ERROR**.

**Example**

```java
@Step(name = "order.validate")
@PromotionAlert(level = StandardLevel.INFO)
public void validate(@Attribute("order.id") String orderId) { /* ... */ }
```

---

### `@Attribute` *(parameter-level)*

Binds a method parameter to the current event’s attributes.

**Key rules:**

* Key name is taken from the annotation value or parameter name.
* **Simple types** → stored as `toString()`.
* **Complex types** → converted to `Map<String,Object>` via the configured `ObjectMapper`.
* Nulls skipped unless `recordNull = true`.

**Example**

```java
@Step(name = "payment.process")
public void process(
    @Attribute("payment") Payment payment,   // complex → map
    @Attribute("request.id") String requestId // simple → string
) { /* ... */ }
```

---

## Programmatic Attribute Setting

When you can’t bind attributes via parameters alone — for example, when you need to add context after starting a step — use the `TelemetryContext` bean.

**Why use it?**

* Works whether you’re in a Flow or Step.
* Attributes are automatically applied to the **current scope**.
* Reduces boilerplate for “late” attributes.

**Bean:**

```java
@Component
public class TelemetryContext {
    private final TelemetryProcessorSupport support;
    public TelemetryContext(TelemetryProcessorSupport support) { this.support = support; }

    public void put(String key, Object value) {
        TelemetryHolder holder = support.currentHolder();
        if (holder != null) {
            holder.contextPut(key, value);
        }
    }

    public void putAll(Map<String, ?> map) {
        if (map != null && !map.isEmpty()) {
            TelemetryHolder holder = support.currentHolder();
            if (holder != null) {
                for (Map.Entry<String, ?> e : map.entrySet()) {
                    holder.contextPut(e.getKey(), e.getValue());
                }
            }
        }
    }
}
```

**Example:**

```java
@Service
@RequiredArgsConstructor
class PaymentService {
    private final TelemetryContext telemetry;

    @Step(name = "payment.charge")
    public void charge(String userId, long amountCents) {
        telemetry.put("user.id", userId);
        telemetry.put("amount.cents", amountCents);
        // ...
    }
}
```

---

## Attribute Lifecycle Diagram

```text
┌──────────────────────┐
│ Method Entry (@Flow) │
└───────┬──────────────┘
        │
        ▼
Bind attributes from @Attribute parameters
        │
        ▼
[Optional] Application calls TelemetryContext.put / putAll
        │
        ▼
Attributes merged into current TelemetryHolder
        │
        ▼
Event emitted (Flow or Step)
        │
        ▼
Attributes serialized → Receivers → Storage/Export
```

* **`@Attribute` binding** happens **immediately** on method entry.
* **`TelemetryContext`** calls can happen **any time before event completion**.
* The final attribute set is **merged** and persisted when the event closes.

---

## Promotion Workflow

```
Step called
  ├─ Active Flow?
  │    ├─ YES → Emit child event
  │    └─ NO  → Promote to Flow event
  │              ├─ Log promotion warning
  │              └─ Continue as Flow event
```

---

## Telemetry Receivers

Receivers allow application code to listen to the **flow lifecycle** and perform custom processing when telemetry events are started or finished.
This is useful for:

* Logging flow activity in a consistent, centralized way.
* Forwarding telemetry to external systems (e.g., message queues, observability platforms, audit stores).
* Collecting aggregate metrics.
* Triggering side-effects based on specific flow or event conditions.

### Lifecycle Callbacks

The `TelemetryReceiver` interface defines three lifecycle methods:

```java
package com.obsinity.telemetry.receivers;

import com.obsinity.telemetry.model.TelemetryHolder;
import java.util.List;

/**
 * Receives flow lifecycle notifications.
 */
public interface TelemetryReceiver {
    /** Called when any flow (root or nested) is opened. */
    default void flowStarted(TelemetryHolder holder) {}

    /** Called when any flow (root or nested) is finished. */
    default void flowFinished(TelemetryHolder holder) {}

    /**
     * Called once when a root flow finishes, with all finished flows
     * (root + all nested) that completed within that root. Each holder has endTimestamp set.
     */
    default void rootFlowFinished(List<TelemetryHolder> completed) {}
}
```

**Key points:**

* **`flowStarted`** — called for *every* flow, including child flows created by `@Step`.
* **`flowFinished`** — called for *every* flow when it completes.
* **`rootFlowFinished`** — called **once** when the root flow finishes.
  This callback includes the *root flow* and **all nested flows** in a `List` (in completion order).

---

### Example: Simple Logging Receiver

The following receiver writes concise log lines when flows start and finish, and when a root flow completes. It also logs useful identifiers like `traceId` and `spanId` to allow cross-system correlation.

```java
package com.example.telemetrydemo.telemetry;

import com.obsinity.telemetry.model.TelemetryHolder;
import com.obsinity.telemetry.receivers.TelemetryReceiver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class SimpleLoggingReceiver implements TelemetryReceiver {
    private static final Logger log = LoggerFactory.getLogger(SimpleLoggingReceiver.class);

    @Override
    public void flowStarted(TelemetryHolder h) {
        log.info("[telemetry] START name={}, kind={}, traceId={}, spanId={}",
                 h.name(), h.kind(), h.traceId(), h.spanId());
    }

    @Override
    public void flowFinished(TelemetryHolder h) {
        Long durationMs = null;
        Instant start = h.timestamp();
        Instant end = h.endTimestamp();
        if (start != null && end != null) {
            durationMs = Duration.between(start, end).toMillis();
        }
        int events = (h.events() == null) ? 0 : h.events().size();

        log.info("[telemetry] FINISH name={}, kind={}, traceId={}, spanId={}, durationMs={}, events={}",
                 h.name(), h.kind(), h.traceId(), h.spanId(), durationMs, events);
    }

    @Override
    public void rootFlowFinished(List<TelemetryHolder> completed) {
        String rootName = (completed == null || completed.isEmpty()) ? null : completed.get(0).name();
        String traceId  = (completed == null || completed.isEmpty()) ? null : completed.get(0).traceId();
        int count = (completed == null) ? 0 : completed.size();
        String names = (completed == null) ? "[]"
                : completed.stream().map(TelemetryHolder::name).collect(Collectors.toList()).toString();

        log.info("[telemetry] ROOT DONE traceId={}, root={}, flows={}, names={}",
                 traceId, rootName, count, names);
    }
}
```

---

### How to Use Receivers

1. **Create a class** implementing `TelemetryReceiver`.
2. **Mark it with `@Component`** (or register as a bean) so Spring can discover it.
3. **Implement the callbacks** you care about — you don’t need to implement all three.
4. **Log, export, or trigger side effects** in your callback code.

---

**Example use cases:**

* Export all completed flows to an analytics system.
* Trigger an alert if a specific flow name finishes with `error=true`.
* Measure high-level metrics like “total checkout flows per minute”.
* Capture timing breakdowns for performance tuning.

---

## Minimal Spring Boot Sample

**Domain**

```java
public record Payment(String method, BigDecimal amount, String currency) {}
public record Receipt(String id) {}
```

**Service**

```java
@Service
@Kind(spanKind = SpanKind.SERVER)
public class OrderService {
    @Flow(name = "checkout.process")
    public Receipt checkout(@Attribute("order.id") String orderId) {
        validate(orderId, new BigDecimal("99.99"));
        charge(orderId, new Payment("card", new BigDecimal("99.99"), "EUR"));
        persist(orderId);
        return new Receipt(UUID.randomUUID().toString());
    }
    @Step(name = "checkout.validate")
    @PromotionAlert(level = StandardLevel.WARNING)
    public void validate(@Attribute("order.id") String orderId,
                         @Attribute("order.total") BigDecimal total) {}
    @Step(name = "checkout.charge")
    @Kind(spanKind = SpanKind.CLIENT)
    public void charge(@Attribute("order.id") String orderId,
                       @Attribute("payment") Payment payment) {}
    @Step(name = "checkout.persist")
    public void persist(@Attribute("order.id") String orderId) {}
}
```

**Controller** (with `@Flow`)

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service) { this.service = service; }

    @PostMapping("/{orderId}/checkout")
    @Flow(name = "api.order.checkout") // top-level entry point
    public Receipt checkout(@PathVariable String orderId) {
        return service.checkout(orderId);
    }
}
```

---

## Best Practices

* **Name consistently**: OTEL-style lowercase dotted paths.
* **Stable keys**: Avoid per-request UUIDs or highly dynamic values unless needed.
* **Parameter binding vs TelemetryContext**: Use `@Attribute` for parameters, `TelemetryContext` for runtime/late binding.
* **Promotion alert severity**: INFO for benign, WARNING for unexpected, ERROR for misuse.
* **Keep attributes lean**: Especially for high-volume events.
