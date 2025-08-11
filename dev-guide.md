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

Obsinity provides lifecycle hooks via `TelemetryReceiver`:

```java
public interface TelemetryReceiver {
    default void flowStarted(TelemetryHolder holder) {}
    default void flowFinished(TelemetryHolder holder) {}
    default void rootFlowFinished(List<TelemetryHolder> completed) {}
}
```

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
