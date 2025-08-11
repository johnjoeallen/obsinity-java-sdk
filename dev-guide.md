# Obsinity Telemetry Developer Guide

**Annotations:** `@Flow`, `@Step`, `@Kind`, `@PromotionAlert`, `@Attribute`

This document explains how to instrument code using Obsinity’s telemetry annotations, how promotion works for `@Step`s, and how attributes can be bound from method parameters.

---

## Overview

### Key Concepts

* **Flow event** — A top-level telemetry event representing a logical operation.
* **Child event** — An event emitted by a `@Step` inside an active Flow.
* **Promotion** — When a `@Step` is called outside an active Flow, it becomes a Flow event automatically.
* **Promotion warning** — A log message emitted immediately when promotion occurs; controlled by `@PromotionAlert`.
* **Attributes** — Key/value pairs attached to the current telemetry event, often used for search, filtering, and analysis.

---

## Annotations

### `@Flow` *(method only)*

Starts a **Flow event** and activates telemetry context so nested `@Step` calls become **child events**.

* **Naming**: Use OTEL semantic style — lowercase, dot-separated, stable keys.
  Examples:

    * `checkout.process`
    * `inventory.reserve`
    * `http.get.user`
* Inherits `@Kind` from the class unless overridden.
* Can also include `@Attribute` parameters to populate event attributes automatically.

**Example:**

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

Emits a **child event** if called inside an active Flow.
If called with **no active Flow**, it is **promoted to a Flow event**:

* Promotion triggers a **promotion warning log** at the level from `@PromotionAlert`, or ERROR by default.
* Name is taken from the annotation and should follow OTEL style.

**Example:**

```java
@Step(name = "checkout.validate")
public void validate(@Attribute("order.id") String orderId) {
    // Runs as child event inside Flow, or Flow event if promoted
}
```

---

### `@Kind` *(class or method)*

Sets default telemetry **kind** and/or OTEL `SpanKind`.

* Place on class to set defaults for all methods.
* Place on method to override.

**Example:**

```java
@Kind(spanKind = SpanKind.CLIENT)
public class PaymentClient {
    @Flow(name = "payment.charge")
    public void charge() { ... }
}
```

---

### `@PromotionAlert` *(only on `@Step` methods)*

Controls the **log level of the promotion warning** when a `@Step` is promoted to a Flow event.

* **Only applies on promotion** (Step called with no active Flow).
* **Supported levels**:

    * `StandardLevel.INFO`
    * `StandardLevel.WARNING`
    * `StandardLevel.SEVERE` (ERROR)
* **Default**: If absent, behaves as `@PromotionAlert(level = StandardLevel.SEVERE)`.

**Example:**

```java
@Step(name = "order.validate")
@PromotionAlert(level = StandardLevel.INFO)
public void validate(@Attribute("order.id") String orderId) {
    // Logs at INFO if promoted
}
```

---

### `@Attribute` *(parameter-level)*

Automatically adds a method parameter’s value to the **current TelemetryHolder’s attributes**.

* **Key naming**:

    * Use annotation `value()` if provided.
    * Otherwise, use Java parameter name.
* **Conversion**:

    * **Simple types** (String, primitives, boxed primitives, enums, BigDecimal, etc.): stored as `value.toString()`.
    * **Complex types**: converted to `Map<String,Object>` using the configured `ObjectMapper`:

      ```java
      Map<String,Object> map = objectMapper.convertValue(value, Map.class);
      telemetryHolder.putAttribute(key, map);
      ```
* **Null handling**: Skip if null unless `recordNull = true`.

**Example:**

```java
@Step(name = "payment.process")
public void process(
    @Attribute("payment") Payment payment,
    @Attribute("request.id") String requestId
) {
    // payment → { "method": "card", "amount": "50.00" }
    // request.id → "abc123"
}
```

---

## Promotion Workflow

```text
Step called
  ├─ Is a Flow active?
  │    ├─ YES → Emit child event
  │    └─ NO  → Promote to Flow event
  │               ├─ Log promotion warning (level from @PromotionAlert or ERROR if absent)
  │               └─ Continue execution as Flow event
```

---

## Logging Behavior

When promotion happens:

1. Create Flow event with Step’s name.
2. Emit a **single promotion warning log**:

    * Level = `@PromotionAlert.level` if present.
    * Level = ERROR if `@PromotionAlert` is absent.
3. Proceed with telemetry event as normal.

---

## Example End-to-End

```java
@Service
@Kind(spanKind = SpanKind.SERVER)
public class OrderService {

    @Flow(name = "order.create")
    public void createOrder(
        @Attribute("order.id") String orderId,
        @Attribute("order.total") BigDecimal total
    ) {
        validate(orderId, total); // child event
    }

    @Step(name = "order.validate")
    @PromotionAlert(level = StandardLevel.WARNING)
    public void validate(
        @Attribute("order.id") String orderId,
        @Attribute("order.total") BigDecimal total
    ) {
        // WARN log if called without Flow
    }
}
```

---

## Best Practices

* **Naming**: Always use OTEL-style lowercase, dot-separated names for `@Flow` and `@Step`.
* **Attributes**: Keep keys stable over time; avoid high-cardinality values unless essential.
* **PromotionAlert**:

    * INFO for benign entry-points.
    * WARNING for unexpected but non-critical promotions.
    * ERROR (default) for serious misuses.
* **Complex Objects**: Ensure they’re serializable via the configured `ObjectMapper`.
