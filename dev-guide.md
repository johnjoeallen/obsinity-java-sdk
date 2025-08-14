# Obsinity Telemetry Developer Guide

**Annotations:**
`@Flow`, `@Step`, `@Kind`, `@OrphanAlert`,
`@BindEventAttribute`, `@BindContextValue`, `@BindAllContextValues`, `@BindEventThrowable`,
`@RequiredAttributes`, `@RequiredEventContext`,
`@OnEvent`, `@TelemetryEventHandler`

This guide explains:

* How to instrument code using Obsinity’s annotations.
* How **Step auto‑promotion** works when no active Flow exists.
* How to bind method parameters to **persisted attributes**, **ephemeral context values**, and **errors**.
* How to set values programmatically via `TelemetryContext`.
* How to plug in custom event handlers using `@OnEvent` filtering.
* **Attribute & context lifecycles**.
* Which annotations control **selection** vs **binding**.
* Failure & logging policy for handler invocations.

---

## Overview

| Term                  | Description                                                                                             |
| --------------------- | ------------------------------------------------------------------------------------------------------- |
| **Flow event**        | Top‑level telemetry unit for a logical operation (e.g., `checkout.process`).                            |
| **Child event**       | Event emitted by a `@Step` inside an active Flow.                                                       |
| **Auto‑promotion**    | When a `@Step` is called with **no active Flow**, it’s promoted to a Flow event.                        |
| **Orphan alert**      | Log emitted on auto‑promotion; level controlled by `@OrphanAlert` (default `ERROR`).                    |
| **Attributes**        | Persisted key/value pairs recorded on the event.                                                        |
| **Event Context**     | Ephemeral, in‑process key/value pairs **not** serialized or stored.                                     |
| **Telemetry Context** | API for adding attributes or context values to the active telemetry scope.                              |
| **Event handler**     | Method annotated with `@OnEvent` that receives telemetry events filtered by lifecycle, name, kind, etc. |

---

## Core Annotations for Instrumentation

### Naming via Constants (recommended)

Define event names/regexes once and share across producers/consumers:

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

@Kind(SpanKind.SERVER)
public class OrderService {

  @Flow(name = EVENT_CHECKOUT_PROCESS)
  public Receipt checkout(
      @BindEventAttribute("order.id") String orderId,
      @BindEventAttribute("order.total") BigDecimal total) {

    validate(orderId, total);
    charge(orderId, total);
    persist(orderId);
    return new Receipt();
  }
```

### `@Step`

Represents a unit of work within a Flow; **auto‑promoted** to a Flow if no active Flow exists.

```java
  @Step(name = STEP_CHECKOUT_VALIDATE)
  @OrphanAlert(level = OrphanAlert.Level.WARN) // log WARN when promoted
  public void validate(
      @BindEventAttribute("order.id") String orderId,
      @BindEventAttribute("order.total") BigDecimal total) { /* ... */ }

  @Step(name = STEP_CHECKOUT_CHARGE)
  @Kind(SpanKind.CLIENT)
  public void charge(
      @BindEventAttribute("order.id") String orderId,
      @BindEventAttribute("payment.method") String method) { /* ... */ }

  @Step(name = STEP_CHECKOUT_PERSIST)
  public void persist(@BindEventAttribute("order.id") String orderId) { /* ... */ }
}
```

### `@OrphanAlert`

Controls the log level when a `@Step` is invoked without an active `@Flow` and is **auto‑promoted**.
Levels: `ERROR` (default), `WARN`, `INFO`, `DEBUG`.

### `@Kind`

Sets OTEL `SpanKind` (SERVER, CLIENT, PRODUCER, CONSUMER, INTERNAL) at class or method level.

---

## Binding vs Selection

**Selection** determines whether a handler runs.
**Binding** determines what values are injected into its parameters.

### Selection (on `@OnEvent` and helpers)

* `name` **or** `nameRegex`
* `lifecycle` (array of `Lifecycle`)
* `kinds` (array of `SpanKind`)
* Throwable filters: `requireThrowable`, `throwableTypes`, `includeSubclasses`, `messageRegex`, `causeType`
* `@RequiredAttributes({"key1","key2"})`
* `@RequiredEventContext({"key1","key2"})`

### Binding (parameter annotations / types)

* `@BindEventAttribute("key")` → persisted attribute
* `@BindContextValue("key")` → ephemeral context value
* `@BindAllContextValues` → full context map (`Map<String,Object>`, read‑only)
* `@BindEventThrowable` → event’s `Throwable` (or cause)
* `TelemetryHolder` → current event
* `List<TelemetryHolder>` → batch for `ROOT_FLOW_FINISHED`

---

## Programmatic Attribute & Context Setting

```java
@Service
@RequiredArgsConstructor
class PaymentService {
  private final TelemetryContext telemetry;

  @Step(name = TelemetryNames.STEP_CHECKOUT_CHARGE)
  public void charge(String userId, long amountCents) {
    telemetry.putAttribute("user.id", userId);          // persisted
    telemetry.putAttribute("amount.cents", amountCents);// persisted
    telemetry.putContextValue("retry", false);          // ephemeral
  }
}
```

---

## Attribute & Context Lifecycle

```
@Flow/@Step entry
  ↓
(Optional) telemetry.putAttribute(...) / telemetry.putContextValue(...)
  ↓
Emit event
  ↓
@OnEvent handlers selected (name/regex, lifecycle, kinds, throwable filters,
RequiredAttributes, RequiredEventContext)
  ↓
Bind parameters (@BindEventAttribute, @BindContextValue, @BindAllContextValues, @BindEventThrowable, ...)
  ↓
Persisted attributes serialized/exported; context values are ephemeral and discarded at scope end
```

---

## Event Handling with `@OnEvent`

### Example 1 — Basic handler class

```java
import static com.example.telemetry.TelemetryNames.*;

@TelemetryEventHandler
@Component
public class CheckoutEventHandlers {

  // Root flow completion with nested flows aggregation
  @OnEvent(lifecycle = {Lifecycle.ROOT_FLOW_FINISHED}, name = EVENT_CHECKOUT_PROCESS)
  public void rootDone(List<TelemetryHolder> flows) { /* … */ }

  // Any FLOW_FINISHED event with an order.id attribute present
  @OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})
  @RequiredAttributes({"order.id"})
  public void finished(@BindEventAttribute("order.id") String orderId, TelemetryHolder holder) { /* … */ }

  // SERVER kind finished flows; inspect all context values
  @OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED}, kinds = {SpanKind.SERVER})
  public void serverOnly(@BindAllContextValues Map<String, Object> ctx) { /* … */ }
}
```

### Example 2 — Advanced handler class

```java
import static com.example.telemetry.TelemetryNames.*;

@TelemetryEventHandler
@Component
public class AdvancedEventHandlers {

  // Any lifecycle for a specific event name
  @OnEvent(name = EVENT_INVENTORY_RESERVE)
  public void anyLifecycleForInventory(TelemetryHolder holder) { /* … */ }

  // Client-kind finished flows whose names start with payment.
  @OnEvent(
    lifecycle = {Lifecycle.FLOW_FINISHED},
    kinds = {SpanKind.CLIENT},
    nameRegex = REGEX_PAYMENT_PREFIX
  )
  public void clientPayments(
      @BindEventAttribute("payment.id") String paymentId,
      @BindEventAttribute("amount") BigDecimal amount,
      TelemetryHolder holder) { /* … */ }

  // IO failures only (Throwable required and type-matched)
  @OnEvent(
    requireThrowable = true,
    throwableTypes = { java.io.IOException.class },
    includeSubclasses = true
  )
  public void ioFailures(@BindEventThrowable Throwable cause, TelemetryHolder holder) { /* … */ }

  // Multi-tenant completion: require both tenant.id and region, plus a correlationId context value
  @OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})
  @RequiredAttributes({"tenant.id", "region"})
  @RequiredEventContext({"correlationId"})
  public void multiTenantFinish(
      @BindEventAttribute("tenant.id") String tenantId,
      @BindEventAttribute("region") String region,
      @BindContextValue("correlationId") String correlationId,
      TelemetryHolder holder) { /* … */ }

  // Aggregate all flows (no root name filter)
  @OnEvent(lifecycle = {Lifecycle.ROOT_FLOW_FINISHED})
  public void allRoots(List<TelemetryHolder> completed) { /* … */ }
}
```

---

## Handler Failure & Logging Policy

* The dispatcher **must not** abort event delivery when a handler throws.
* Failures are **caught and logged** with rich context:

  * `handler.class`, `handler.method`
  * `event.name`, `event.lifecycle`, `span.kind`
  * Binding errors: key, value class, target parameter type
* Dispatch continues to other matched handlers.
* If a required binding (e.g., `@BindContextValue(required=true)`) is missing, log a binding error.

---

## OTEL `SpanKind` Reference (when to use)

| SpanKind     | Use when your code…                               | Examples                                                              |
| ------------ | ------------------------------------------------- | --------------------------------------------------------------------- |
| **SERVER**   | Handles an **incoming** request/message           | HTTP controller; gRPC server; message consumer acting as RPC server   |
| **CLIENT**   | Makes an **outgoing** request to another system   | HTTP/gRPC client call; external API; DB driver spans initiated by app |
| **PRODUCER** | **Publishes** a message to a broker/topic/queue   | Kafka/RabbitMQ/SNS send                                               |
| **CONSUMER** | **Receives/processes** a brokered message         | Kafka poll loop; RabbitMQ listener; SQS handler                       |
| **INTERNAL** | Performs **in‑process** work (no remote boundary) | Cache computation; rule evaluation; CPU‑bound step inside a job       |

**Rules of thumb:** incoming boundary → `SERVER`; outgoing dependency → `CLIENT`; async send/receive → `PRODUCER`/`CONSUMER`; everything else → `INTERNAL`.

---

## Cheat‑Sheet: Common `@OnEvent` Patterns

| Pattern                            | `@OnEvent` Example                                                               | Selection Effect                     | Binding Example                                  |
| ---------------------------------- | -------------------------------------------------------------------------------- | ------------------------------------ | ------------------------------------------------ |
| **Match any event, any lifecycle** | `@OnEvent`                                                                       | All events                           | `TelemetryHolder holder`                         |
| **Match by exact name**            | `@OnEvent(name = EVENT_CHECKOUT_PROCESS)`                                        | Only that exact name                 | `TelemetryHolder holder`                         |
| **Match by regex name**            | `@OnEvent(nameRegex = "^payment\\.")`                                            | Any name starting with `payment.`    | `@BindEventAttribute("payment.id") String id`    |
| **Match by lifecycle only**        | `@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})`                                | All finished flows                   | `TelemetryHolder holder`                         |
| **Lifecycle + exact name**         | `@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED}, name = EVENT_CHECKOUT_PROCESS)` | Specific event in specific lifecycle | `@BindContextValue("debugMode") Boolean d`       |
| **Lifecycle + kind**               | `@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED}, kinds = {SpanKind.SERVER})`     | Finished server spans                | `@BindAllContextValues Map<String,Object> ctx`   |
| **Require attributes**             | `@OnEvent @RequiredAttributes({"tenant.id","region"})`                           | Only if both attrs present           | `@BindEventAttribute("tenant.id") String tenant` |
| **Require event context**          | `@OnEvent @RequiredEventContext({"correlationId"})`                              | Only if context key present          | `@BindContextValue("correlationId") String cid`  |
| **Require throwable**              | `@OnEvent(requireThrowable = true)`                                              | Only if an error is attached         | `@BindEventThrowable Throwable cause`            |
| **Throwable type filter**          | `@OnEvent(throwableTypes = {IOException.class}, includeSubclasses = true)`       | IOException or subclass              | `@BindEventThrowable Throwable t`                |
| **Batch for ROOT\_FLOW\_FINISHED** | `@OnEvent(lifecycle = {Lifecycle.ROOT_FLOW_FINISHED})`                           | Root + all nested flows per root     | `List<TelemetryHolder> flows`                    |
