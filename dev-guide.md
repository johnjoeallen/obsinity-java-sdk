# Obsinity Telemetry Developer Guide

**Annotations:** `@Flow`, `@Step`, `@Kind`, `@PromotionAlert`, `@Attribute`

This doc explains how to instrument code with Obsinity’s annotations, how **Step promotion** works, how to bind method parameters to attributes, and how to plug in receivers using the current lifecycle callbacks.

---

## Overview

### Key Concepts

* **Flow event** — Top-level telemetry event for a logical operation.
* **Child event** — Event emitted by a `@Step` inside an active Flow.
* **Promotion** — A `@Step` called with **no active Flow** is **promoted** to a Flow event.
* **Promotion warning** — One log message emitted at promotion; level controlled by `@PromotionAlert`.
* **Attributes** — Key/value pairs attached to the current telemetry event (used for search, filtering, analytics).

---

## Annotations

### `@Flow` *(method only)*

Starts a **Flow event** and activates context so nested `@Step` calls become **child events**.

* **Naming (OTEL style):** lowercase, dot-separated, stable (e.g., `checkout.process`, `inventory.reserve`, `http.get.user`)
* Inherits `@Kind` from class unless overridden.
* Works with `@Attribute` params to add attributes on entry.

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

* Inside an active Flow → emits a **child event**.
* With **no active Flow** → **promoted** to a Flow event:

    * Name = step’s OTEL-style name.
    * Emits a **promotion warning** (level from `@PromotionAlert`, or **ERROR** if absent).

**Example**

```java
@Step(name = "checkout.validate")
public void validate(@Attribute("order.id") String orderId) {
    // Child event (in Flow) or Flow event (promotion)
}
```

---

### `@Kind` *(class or method)*

Sets default telemetry kind / OTEL `SpanKind` (e.g., `SERVER`, `CLIENT`).

* Put on class for defaults; override per method as needed.

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

Controls the **log level of the promotion warning** when a `@Step` is promoted.

* Applies **only** on promotion (no active Flow).
* **Supported levels:** `StandardLevel.INFO`, `StandardLevel.WARNING`, `StandardLevel.SEVERE` (ERROR).
* **Default:** If absent, behaves as `@PromotionAlert(level = StandardLevel.SEVERE)`.

**Example**

```java
@Step(name = "order.validate")
@PromotionAlert(level = StandardLevel.INFO)
public void validate(@Attribute("order.id") String orderId) { /* ... */ }
```

---

### `@Attribute` *(parameter-level)*

Adds a parameter’s value to the **current TelemetryHolder’s attributes** when the method runs.

* **Key name:** `value()` from the annotation or the Java parameter name.
* **Conversion:**

    * **Simple types** (String, primitives/boxed, enums, BigDecimal, etc.) → `toString()`.
    * **Complex types** → `Map<String,Object>` via configured **`ObjectMapper.convertValue(value, Map.class)`**.
* **Nulls:** Skipped unless `recordNull = true`.

**Example**

```java
@Step(name = "payment.process")
public void process(
    @Attribute("payment") Payment payment,   // complex → mapped to Map by ObjectMapper
    @Attribute("request.id") String requestId // simple → toString()
) { /* ... */ }
```

---

## Promotion Workflow

```
Step called
  ├─ Active Flow?
  │    ├─ YES → Emit child event
  │    └─ NO  → Promote to Flow event
  │              ├─ Log promotion warning (level from @PromotionAlert or ERROR if absent)
  │              └─ Continue as Flow event
```

---

## Logging Behavior (Promotion)

When a `@Step` is promoted:

1. Create Flow event using the step’s name.
2. Emit **one** promotion warning log:

    * Level = from `@PromotionAlert(level=…)` if present.
    * Level = ERROR if absent.
3. Continue execution as a Flow event (no additional logging tied to `@PromotionAlert`).

---

## Telemetry Receivers

The current `TelemetryReceiver` interface provides **three lifecycle callbacks**:

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

### Simple logging receiver

Logs one concise line on start/finish and summarizes the root flow:

```java
package com.example.telemetrydemo.telemetry;

import com.obsinity.telemetry.model.TelemetryHolder;
import com.obsinity.telemetry.receivers.TelemetryReceiver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SimpleLoggingReceiver implements TelemetryReceiver {
    private static final Logger log = LoggerFactory.getLogger(SimpleLoggingReceiver.class);

    @Override
    public void flowStarted(TelemetryHolder h) {
        log.info("[telemetry] START name={}, kind={}, spanKind={}",
                 safe(() -> h.getName()),
                 safe(() -> String.valueOf(h.getKind())),
                 safe(() -> String.valueOf(h.getSpanKind())));
    }

    @Override
    public void flowFinished(TelemetryHolder h) {
        log.info("[telemetry] FINISH name={}, durationMs={}, error={}",
                 safe(() -> h.getName()),
                 safe(() -> h.getDurationMs()),
                 safe(() -> h.isError()));
    }

    @Override
    public void rootFlowFinished(List<TelemetryHolder> completed) {
        // first element is typically the root; adjust if your processor orders differently
        TelemetryHolder root = completed.isEmpty() ? null : completed.get(0);
        log.info("[telemetry] ROOT DONE name={}, totalFlows={}, rootError={}",
                 safe(() -> root != null ? root.getName() : null),
                 completed.size(),
                 safe(() -> root != null && root.isError()));
    }

    private static <T> T safe(java.util.function.Supplier<T> s) { try { return s.get(); } catch (Throwable e) { return null; } }
}
```

---

## Minimal Spring Boot Sample

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
@ComponentScan
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
        validate(orderId, new BigDecimal("99.99")); // child event
        charge(orderId, new Payment("card", new BigDecimal("99.99"), "EUR")); // child (CLIENT)
        persist(orderId); // child event
        return new Receipt(UUID.randomUUID().toString());
    }

    @Step(name = "checkout.validate")
    @PromotionAlert(level = StandardLevel.WARNING) // if promoted, warn
    public void validate(@Attribute("order.id") String orderId,
                         @Attribute("order.total") BigDecimal total) {
        // validation
    }

    @Step(name = "checkout.charge")
    @Kind(spanKind = SpanKind.CLIENT)
    public void charge(@Attribute("order.id") String orderId,
                       @Attribute("payment") Payment payment) {
        // call provider
    }

    @Step(name = "checkout.persist")
    public void persist(@Attribute("order.id") String orderId) {
        // store
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

    // Intentionally calls a @Step directly (no @Flow) to demonstrate promotion + PromotionAlert
    @PostMapping("/{orderId}/validate")
    public void validate(@PathVariable String orderId) {
        service.validate(orderId, null);
    }
}
```

---

## Best Practices

* **Names:** Always OTEL-style lowercase, dot-separated for `@Flow` and `@Step`.
* **Attributes:** Keep keys stable; avoid high-cardinality values unless needed.
* **PromotionAlert:**

    * INFO for benign entry-points
    * WARNING for unexpected/non-critical
    * ERROR (default) for serious misuse
* **Complex Objects:** Ensure your global `ObjectMapper` is configured to convert domain objects cleanly to `Map<String,Object>`.
