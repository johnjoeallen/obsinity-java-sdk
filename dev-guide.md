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
    public void charge() { /* ... */ }
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

## Minimal Spring Boot Sample (with AOP + annotations)

### `Application.java`

```java
package com.example.telemetrydemo;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@SpringBootConfiguration
@EnableAutoConfiguration
@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
@ComponentScan // scans com.example.telemetrydemo.*
public class Application {
    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(Application.class, args);
    }
}
```

### Demo domain

```java
package com.example.telemetrydemo.domain;

import java.math.BigDecimal;

public record Payment(String method, BigDecimal amount, String currency) {}
public record Receipt(String id) {}
```

### `OrderService.java`

```java
package com.example.telemetrydemo.service;

import com.example.telemetrydemo.domain.Payment;
import com.example.telemetrydemo.domain.Receipt;
import com.obsinity.telemetry.annotations.Attribute;
import com.obsinity.telemetry.annotations.Flow;
import com.obsinity.telemetry.annotations.Kind;
import com.obsinity.telemetry.annotations.PromotionAlert;
import com.obsinity.telemetry.annotations.Step;
import io.opentelemetry.api.trace.SpanKind;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.logging.StandardLevel;

@Service
@Kind(spanKind = SpanKind.SERVER) // default SpanKind for methods here
public class OrderService {

    @Flow(name = "checkout.process")
    public Receipt checkout(@Attribute("order.id") String orderId) {
        validate(orderId, new BigDecimal("99.99"));  // child event
        charge(orderId, new Payment("card", new BigDecimal("99.99"), "EUR")); // child event (CLIENT)
        persist(orderId); // child event
        return new Receipt(UUID.randomUUID().toString());
    }

    @Step(name = "checkout.validate")
    @PromotionAlert(level = StandardLevel.WARNING) // if called outside Flow, warn when promoted
    public void validate(@Attribute("order.id") String orderId,
                         @Attribute("order.total") BigDecimal total) {
        // … validation logic …
    }

    @Step(name = "checkout.charge")
    @Kind(spanKind = SpanKind.CLIENT)
    public void charge(@Attribute("order.id") String orderId,
                       @Attribute("payment") Payment payment) {
        // … call payment provider …
    }

    @Step(name = "checkout.persist")
    public void persist(@Attribute("order.id") String orderId) {
        // … save to DB …
    }
}
```

### `OrderController.java`

```java
package com.example.telemetrydemo.web;

import com.example.telemetrydemo.domain.Receipt;
import com.example.telemetrydemo.service.OrderService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service) { this.service = service; }

    @PostMapping("/{orderId}/checkout")
    public Receipt checkout(@PathVariable String orderId) {
        return service.checkout(orderId);
    }

    // Intentionally calls a @Step directly (no @Flow) to demo promotion + PromotionAlert
    @PostMapping("/{orderId}/validate")
    public void validate(@PathVariable String orderId) {
        service.validate(orderId, null);
    }
}
```

---

## Simple Receiver (logs a single message)

A minimal `TelemetryReceiver` that **just logs one concise line per event**:

```java
package com.example.telemetrydemo.telemetry;

import com.obsinity.telemetry.model.TelemetryHolder;
import com.obsinity.telemetry.receivers.TelemetryReceiver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SimpleLoggingReceiver implements TelemetryReceiver {
    private static final Logger log = LoggerFactory.getLogger(SimpleLoggingReceiver.class);

    @Override
    public void receive(TelemetryHolder t) {
        // Adjust field access according to your SDK’s TelemetryHolder API
        String name = safe(() -> t.getName());
        String spanKind = safe(() -> String.valueOf(t.getSpanKind()));
        Long durationMs = safe(() -> t.getDurationMs());
        boolean error = safe(() -> t.isError());
        log.info("[telemetry] name={}, spanKind={}, durationMs={}, error={}",
                 name, spanKind, durationMs, error);
    }

    private static <T> T safe(java.util.function.Supplier<T> s) {
        try { return s.get(); } catch (Throwable e) { return null; }
    }
}
```

> If you later want richer logs (trace/span IDs, attributes, counters), extend this receiver or add another one. Multiple receivers can coexist.

---

## Best Practices

* **Naming**: Always use OTEL-style lowercase, dot-separated names for `@Flow` and `@Step`.
* **Attributes**: Keep keys stable; avoid excessive cardinality.
* **PromotionAlert**:

    * INFO for benign entry-points.
    * WARNING for unexpected but non-critical promotions.
    * ERROR (default) for serious misuses.
* **Complex Objects**: Ensure objects annotated with `@Attribute` are serializable by your configured `ObjectMapper`.
