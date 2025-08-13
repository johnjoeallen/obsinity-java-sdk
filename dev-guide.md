# Obsinity Telemetry Developer Guide

**Annotations:**
`@Flow`, `@Step`, `@Kind`, `@PromotionAlert`, `@Attr`, `@AllAttrs`, `@Err`, `@RequireAttrs`, `@OnEvent`, `@TelemetryEventHandler`

This guide explains:

* How to instrument code using Obsinity’s annotations.
* How **Step promotion** works.
* How to bind method parameters to telemetry attributes.
* How to set attributes programmatically via `TelemetryContext`.
* How to plug in custom event handlers using `@OnEvent` filtering.
* **Attribute lifecycle** from event creation to storage.
* Which annotations control **selection** vs **binding**.
* Failure & logging policy for handler invocations.

It is designed for developers who want rich, **OpenTelemetry‑aligned** instrumentation with minimal boilerplate.

---

## Overview

| Term                  | Description                                                                                             |
| --------------------- | ------------------------------------------------------------------------------------------------------- |
| **Flow event**        | Top‑level telemetry unit for a logical operation (e.g., `checkout.process`).                            |
| **Child event**       | Event emitted by a `@Step` inside an active Flow.                                                       |
| **Promotion**         | When a `@Step` is called with **no active Flow**, it’s promoted to a Flow event.                        |
| **Promotion warning** | Log message emitted at promotion; level controlled by `@PromotionAlert`.                                |
| **Attributes**        | Key/value pairs attached to the current telemetry event.                                                |
| **Telemetry Context** | API for adding attributes to the active telemetry scope.                                                |
| **Event handler**     | Method annotated with `@OnEvent` that receives telemetry events filtered by lifecycle, name, kind, etc. |

---

## Core Annotations for Instrumentation

### Naming via Constants (recommended)

Define event names and common regexes once as `public static final String` constants. Reference them from both **producers** (`@Flow`, `@Step`) and **consumers** (`@OnEvent`) to avoid drift.

```java
public final class TelemetryNames {
    private TelemetryNames() {}
    public static final String EVENT_CHECKOUT_PROCESS   = "checkout.process";
    public static final String STEP_CHECKOUT_VALIDATE   = "checkout.validate";
    public static final String STEP_CHECKOUT_CHARGE     = "checkout.charge";
    public static final String STEP_CHECKOUT_PERSIST    = "checkout.persist";

    public static final String EVENT_INVENTORY_RESERVE  = "inventory.reserve";
    public static final String REGEX_PAYMENT_PREFIX     = "^payment\\.";
}
```

### `@Flow`

Starts a **Flow event** and activates context so nested `@Step` calls become child events.

```java
import static com.example.telemetry.TelemetryNames.*;

@Kind(spanKind = SpanKind.SERVER)
public class OrderService {

    @Flow(name = EVENT_CHECKOUT_PROCESS)
    public Receipt checkout(@Attr("order.id") String orderId,
                            @Attr("order.total") BigDecimal total) {
        validate(orderId, total);
        charge(orderId, total);
        persist(orderId);
        return new Receipt();
    }

    @Step(name = STEP_CHECKOUT_VALIDATE)
    public void validate(@Attr("order.id") String orderId,
                         @Attr("order.total") BigDecimal total) { /* ... */ }

    @Step(name = STEP_CHECKOUT_CHARGE)
    @Kind(spanKind = SpanKind.CLIENT)
    public void charge(@Attr("order.id") String orderId,
                       @Attr("payment.method") String method) { /* ... */ }

    @Step(name = STEP_CHECKOUT_PERSIST)
    public void persist(@Attr("order.id") String orderId) { /* ... */ }
}
```

### `@Step`

Represents a unit of work within a Flow; **promoted** to a Flow if no active Flow. You can tune the promotion log level:

```java
@Step(name = TelemetryNames.STEP_CHECKOUT_VALIDATE)
@PromotionAlert(level = java.util.logging.Level.WARNING)
public void validate(@Attr("order.id") String orderId,
                     @Attr("order.total") BigDecimal total) { /* ... */ }
```

### `@Kind`

Sets OTEL `SpanKind` (SERVER, CLIENT, PRODUCER, CONSUMER, INTERNAL) on class or method.

### `@Attr`, `@AllAttrs`, `@Err`

* `@Attr("key")` — bind a single attribute to the parameter type (converted when invoked).
* `@AllAttrs` — bind the full attribute map (`Map<String,Object>`).
* `@Err` — bind the throwable/cause associated with the event (if any).

> **Note:** `@Attr` is **binding‑only** and does not control whether a handler is selected. Use `@RequireAttrs` for selection by attribute presence.

---

## Selection vs Binding Annotations

**Selection** controls whether a handler is invoked:

| Annotation / Property         | Purpose                                                          |
| ----------------------------- | ---------------------------------------------------------------- |
| `@OnEvent.lifecycle`          | Match specific lifecycles (omit to match all).                   |
| `@OnEvent.name` / `nameRegex` | Exact or regex event name filter (both blank → match all names). |
| `@OnEvent.kinds`              | Match OTEL `SpanKind`s.                                          |
| `@OnEvent.throwableTypes`     | Match error/exception types.                                     |
| `@OnEvent.requireThrowable`   | Require an error to be present.                                  |
| `@OnEvent.includeSubclasses`  | Include subclasses of `throwableTypes`.                          |
| `@OnEvent.causeType`          | Match cause by fully qualified class name.                       |
| `@RequireAttrs`               | Require that specific attributes exist.                          |

**Binding** controls what parameters get injected after selection:

| Annotation / Param Type | Purpose                                                                 |
| ----------------------- | ----------------------------------------------------------------------- |
| `@Attr("key")`          | Inject a single attribute (converted to parameter type).                |
| `@AllAttrs`             | Inject the entire attribute map.                                        |
| `@Err`                  | Inject the error/cause.                                                 |
| `TelemetryHolder`       | Inject the current event.                                               |
| `List<TelemetryHolder>` | Inject the batch for `ROOT_FLOW_FINISHED` (no extra annotation needed). |

**Name filters across lifecycles:** `name` / `nameRegex` can be used with any lifecycle selection. Omit `lifecycle` to match **all** lifecycles. Omit `name`/**and** `nameRegex` to match **all** names.

---

## Programmatic Attribute Setting

When you need to add attributes after method entry:

```java
@Service
@RequiredArgsConstructor
class PaymentService {
    private final TelemetryContext telemetry;

    @Step(name = TelemetryNames.STEP_CHECKOUT_CHARGE)
    public void charge(String userId, long amountCents) {
        telemetry.put("user.id", userId);
        telemetry.put("amount.cents", amountCents);
        // ...
    }
}
```

---

## Attribute Lifecycle

```
@Flow/@Step entry
  ↓
Bind @Attr / @AllAttrs / @Err parameters
  ↓
(Optional) TelemetryContext.put(...)
  ↓
Merge into TelemetryHolder
  ↓
Emit event
  ↓
Serialize → @OnEvent handlers → Export/Store
```

---

## Event Handling with `@OnEvent`

### Example 1 — Basic handler class

```java
import static com.example.telemetry.TelemetryNames.*;

@TelemetryEventHandler
@Component
public class CheckoutEventHandlers {

    /** Event name constant for the checkout process root flow. */
    public static final String CHECKOUT = EVENT_CHECKOUT_PROCESS;

    /**
     * Handles completion of the root flow {@link #CHECKOUT} and all its nested flows.
     *
     * <p><strong>Selection:</strong></p>
     * <ul>
     *   <li>Lifecycle: {@link Lifecycle#ROOT_FLOW_FINISHED}</li>
     *   <li>Event name: exact match {@link #CHECKOUT}</li>
     *   <li>Kind: any</li>
     * </ul>
     *
     * <p><strong>Binding:</strong></p>
     * <ul>
     *   <li>Single parameter of type {@code List<TelemetryHolder>} containing the root flow and all nested flows.</li>
     *   <li>Each holder has {@code endTimestamp} set.</li>
     * </ul>
     */
    @OnEvent(lifecycle = {Lifecycle.ROOT_FLOW_FINISHED}, name = CHECKOUT)
    public void rootDone(List<TelemetryHolder> flows) { /* … */ }

    /**
     * Handles completion of any flow, regardless of name.
     *
     * <p><strong>Selection:</strong></p>
     * <ul>
     *   <li>Lifecycle: {@link Lifecycle#FLOW_FINISHED}</li>
     *   <li>No name or regex filter → matches all event names.</li>
     * </ul>
     *
     * <p><strong>Binding:</strong></p>
     * <ul>
     *   <li>{@code @Attr("order.id")} binds the {@code order.id} attribute value (may be {@code null}).</li>
     *   <li>{@code TelemetryHolder} binds the full event object.</li>
     * </ul>
     */
    @OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})
    public void finished(@Attr("order.id") String orderId, TelemetryHolder holder) { /* … */ }

    /**
     * Handles server-kind finished flows and inspects all attributes.
     *
     * <p><strong>Selection:</strong></p>
     * <ul>
     *   <li>Lifecycle: {@link Lifecycle#FLOW_FINISHED}</li>
     *   <li>Kind: {@link SpanKind#SERVER}</li>
     * </ul>
     *
     * <p><strong>Binding:</strong></p>
     * <ul>
     *   <li>{@code @AllAttrs} binds the complete attribute map.</li>
     * </ul>
     */
    @OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED}, kinds = {SpanKind.SERVER})
    public void serverOnly(@AllAttrs Map<String, Object> attrs) { /* … */ }
}
```

---

### Example 2 — Advanced handler class

```java
import static com.example.telemetry.TelemetryNames.*;

@TelemetryEventHandler
@Component
public class AdvancedEventHandlers {

    /** Exact event name for inventory reservation. */
    public static final String INVENTORY_RESERVE = EVENT_INVENTORY_RESERVE;

    /** Regex for all payment-related operations. */
    public static final String PAYMENT_PREFIX_RE = REGEX_PAYMENT_PREFIX;

    /**
     * Handles any lifecycle for the event {@link #INVENTORY_RESERVE}.
     *
     * <p><strong>Selection:</strong></p>
     * <ul>
     *   <li>Event name: exact match {@link #INVENTORY_RESERVE}</li>
     *   <li>Lifecycle: any (not specified)</li>
     * </ul>
     *
     * <p><strong>Binding:</strong></p>
     * <ul>
     *   <li>{@code TelemetryHolder} for the matched event.</li>
     * </ul>
     */
    @OnEvent(name = INVENTORY_RESERVE)
    public void anyLifecycleForInventory(TelemetryHolder holder) { /* … */ }

    /**
     * Handles client-kind finished flows whose names start with {@link #PAYMENT_PREFIX_RE}.
     *
     * <p><strong>Selection:</strong></p>
     * <ul>
     *   <li>Lifecycle: {@link Lifecycle#FLOW_FINISHED}</li>
     *   <li>Kind: {@link SpanKind#CLIENT}</li>
     *   <li>Name regex: {@link #PAYMENT_PREFIX_RE}</li>
     * </ul>
     *
     * <p><strong>Binding:</strong></p>
     * <ul>
     *   <li>{@code @Attr("payment.id")} binds the payment ID attribute.</li>
     *   <li>{@code @Attr("amount")} binds the amount attribute.</li>
     *   <li>{@code TelemetryHolder} binds the full event.</li>
     * </ul>
     */
    @OnEvent(
        lifecycle = {Lifecycle.FLOW_FINISHED},
        kinds = {SpanKind.CLIENT},
        nameRegex = PAYMENT_PREFIX_RE
    )
    public void clientPayments(@Attr("payment.id") String paymentId,
                               @Attr("amount") BigDecimal amount,
                               TelemetryHolder holder) { /* … */ }

    /**
     * Handles events with an IO-related error (IOException or subclass).
     *
     * <p><strong>Selection:</strong></p>
     * <ul>
     *   <li>Require throwable: {@code true}</li>
     *   <li>Throwable types: {@code IOException.class}</li>
     *   <li>Include subclasses: {@code true}</li>
     * </ul>
     *
     * <p><strong>Binding:</strong></p>
     * <ul>
     *   <li>{@code @Err} binds the throwable cause.</li>
     *   <li>{@code TelemetryHolder} binds the full event.</li>
     * </ul>
     */
    @OnEvent(
        requireThrowable = true,
        throwableTypes = { java.io.IOException.class },
        includeSubclasses = true
    )
    public void ioFailures(@Err Throwable cause, TelemetryHolder holder) { /* … */ }

    /**
     * Handles multi-tenant finished flows where both tenant ID and region are present.
     *
     * <p><strong>Selection:</strong></p>
     * <ul>
     *   <li>Lifecycle: {@link Lifecycle#FLOW_FINISHED}</li>
     *   <li>Required attributes: {@code tenant.id}, {@code region}</li>
     * </ul>
     *
     * <p><strong>Binding:</strong></p>
     * <ul>
     *   <li>{@code @Attr("tenant.id")} binds tenant ID.</li>
     *   <li>{@code @Attr("region")} binds region.</li>
     *   <li>{@code TelemetryHolder} binds the full event.</li>
     * </ul>
     */
    @OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})
    @RequireAttrs({"tenant.id", "region"})
    public void multiTenantFinish(@Attr("tenant.id") String tenantId,
                                  @Attr("region") String region,
                                  TelemetryHolder holder) { /* … */ }

    /**
     * Aggregates all completed flows for any root flow (no name filter).
     *
     * <p><strong>Selection:</strong></p>
     * <ul>
     *   <li>Lifecycle: {@link Lifecycle#ROOT_FLOW_FINISHED}</li>
     *   <li>No name filter → all roots match.</li>
     * </ul>
     *
     * <p><strong>Binding:</strong></p>
     * <ul>
     *   <li>{@code List<TelemetryHolder>} contains the root flow and all nested flows for that root.</li>
     * </ul>
     */
    @OnEvent(lifecycle = {Lifecycle.ROOT_FLOW_FINISHED})
    public void allRoots(List<TelemetryHolder> completed) { /* … */ }
}
```

---

## Handler Failure & Logging Policy

* The **dispatcher MUST NOT crash** or abort event delivery when a handler throws.
* Failures are **caught** and **logged** with rich context:

  * `handler.class`, `handler.method`
  * `event.name`, `event.lifecycle`, `span.kind`
  * For binding errors: `attr.key`, `value.class`, `target.type`
* Dispatch **continues to the next matched handler** for the same event.
* Generic type mismatches (e.g., `Map<String,Integer>` → parameter `Map<String,String>`) may fail at runtime and will be **logged**.
* `@Attr` is **binding‑only**; use `@RequireAttrs` (or `@Attr(required=true)` if you’ve wired it to affect selection) to control invocation by attribute presence.

---

## OTEL `SpanKind` Reference (when to use)

| SpanKind     | Use when your code…                               | Examples                                                              |
| ------------ | ------------------------------------------------- | --------------------------------------------------------------------- |
| **SERVER**   | Handles an **incoming** request/message           | HTTP controller; gRPC server; message consumer acting as RPC server   |
| **CLIENT**   | Makes an **outgoing** request to another system   | HTTP/gRPC client call; external API; DB driver spans initiated by app |
| **PRODUCER** | **Publishes** a message to a broker/topic/queue   | Kafka/RabbitMQ/SNS send                                               |
| **CONSUMER** | **Receives/processes** a brokered message         | Kafka poll loop; RabbitMQ listener; SQS handler                       |
| **INTERNAL** | Performs **in‑process** work (no remote boundary) | Cache computation; rule evaluation; CPU‑bound step inside a job       |

**Rules of thumb:**
Incoming boundary → `SERVER`. Outgoing dependency → `CLIENT`. Async send/receive → `PRODUCER`/`CONSUMER`. Everything else → `INTERNAL`.

---

## Best Practices

* **Use constants** for event names and regexes; reference them from both producers and handlers.
* **Name consistently** using OTEL‑style lowercase dotted paths.
* Prefer **exact names** for high‑traffic ops; use **regex** sparingly.
* Use **`@RequireAttrs`** for presence checks; keep **`@Attr`** for binding.
* Keep handlers **small and focused**; compose multiple targeted handlers rather than one catch‑all.
* For `ROOT_FLOW_FINISHED`, declare **`List<TelemetryHolder>`** directly (no extra annotation).

---

## 📌 `@OnEvent` Patterns Cheat-Sheet

| Pattern                             | `@OnEvent` Example                                                               | Selection Effect                        | Binding Example                      |
| ----------------------------------- | -------------------------------------------------------------------------------- | --------------------------------------- | ------------------------------------ |
| **Match any event, any lifecycle**  | `@OnEvent`                                                                       | All events will invoke handler          | `TelemetryHolder holder`             |
| **Match by exact name**             | `@OnEvent(name = EVENT_CHECKOUT_PROCESS)`                                        | Only events with that exact name        | `TelemetryHolder holder`             |
| **Match by regex name**             | `@OnEvent(nameRegex = "^payment\\.")`                                            | Any event name starting with `payment.` | `@Attr("payment.id") String id`      |
| **Match by lifecycle only**         | `@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})`                                | All events with that lifecycle          | `TelemetryHolder holder`             |
| **Match by lifecycle + exact name** | `@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED}, name = EVENT_CHECKOUT_PROCESS)` | Specific event in specific lifecycle    | `@AllAttrs Map<String,Object> attrs` |
| **Match by lifecycle + regex**      | `@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED}, nameRegex = "^checkout\\.")`    | Any checkout.\* event in FLOW\_FINISHED | `TelemetryHolder holder`             |
| **Match by `SpanKind`**             | `@OnEvent(kinds = {SpanKind.SERVER})`                                            | Only SERVER-kind events                 | `@AllAttrs Map<String,Object> attrs` |
| **Require attributes**              | `@OnEvent @RequireAttrs({"tenant.id", "region"})`                                | Only events with both attrs present     | `@Attr("tenant.id") String tenant`   |
| **Require throwable**               | `@OnEvent(requireThrowable = true)`                                              | Only events with an error               | `@Err Throwable cause`               |
| **Throwable type filter**           | `@OnEvent(throwableTypes = {IOException.class}, includeSubclasses = true)`       | Error must be `IOException` or subclass | `@Err Throwable cause`               |
| **Batch for ROOT\_FLOW\_FINISHED**  | `@OnEvent(lifecycle = {Lifecycle.ROOT_FLOW_FINISHED})`                           | All flows under each root flow          | `List<TelemetryHolder> flows`        |

---
