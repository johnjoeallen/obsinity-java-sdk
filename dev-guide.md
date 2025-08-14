# Obsinity Telemetry Developer Guide

## Annotation Reference

| Annotation               | Target         | Purpose                                                                                              |
| ------------------------ | -------------- | ---------------------------------------------------------------------------------------------------- |
| `@Flow`                  | Method / Class | Starts a **flow** (top‑level event). Activates scope so nested `@Step` calls become child events.    |
| `@Step`                  | Method         | Emits a **step** event inside the active flow; may be **auto‑promoted** to a flow if none is active. |
| `@Kind`                  | Method / Class | Sets OTEL `SpanKind` (`SERVER`, `CLIENT`, `PRODUCER`, `CONSUMER`, `INTERNAL`).                       |
| `@OrphanAlert`           | Method / Class | Controls log level when a `@Step` runs without an active `@Flow` (auto‑promotion).                   |
| `@BindEventAttribute`    | Parameter      | Injects a single **persisted attribute** by key.                                                     |
| `@BindContextValue`      | Parameter      | Injects a single **ephemeral context** value by key.                                                 |
| `@BindAllAttributes`     | Parameter      | Injects an **unmodifiable Map** of **all attributes** on the current event.                          |
| `@BindAllContextValues`  | Parameter      | Injects an **unmodifiable Map** of **all context values** on the current event.                      |
| `@BindEventThrowable`    | Parameter      | Injects the event’s `Throwable` (or cause) if present.                                               |
| `@RequiredAttributes`    | Method         | Handler runs **only if** all listed attributes are present.                                          |
| `@RequiredEventContext`  | Method         | Handler runs **only if** all listed context keys are present.                                        |
| `@OnEvent`               | Method         | Declares an **event handler**; filters by lifecycle, name/regex, kind, throwable, etc.               |
| `@TelemetryEventHandler` | Class          | Marks a bean that contains `@OnEvent` methods (discovered by the dispatcher).                        |

---

**This guide covers:**

* How to instrument code using Obsinity’s annotations.
* How **Step auto‑promotion** works when no active Flow exists.
* Binding method parameters to **attributes**, **context values**, **all attributes/context**, and **errors**.
* Setting values programmatically via `TelemetryContext`.
* Writing `@OnEvent` handlers (selection vs. binding).
* Attribute & context lifecycles.
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

Starts a **Flow** and activates scope so nested `@Step` calls become child events.

```java
@Kind(SpanKind.SERVER)
public class OrderService {

  @Flow(name = TelemetryNames.EVENT_CHECKOUT_PROCESS)
  public Receipt checkout(
      @BindEventAttribute("order.id") String orderId,
      @BindEventAttribute("order.total") BigDecimal total) {

    validate(orderId, total);
    charge(orderId, total);
    persist(orderId);
    return new Receipt();
  }
}
```

### `@Step`

Represents a unit of work within a Flow; **auto‑promoted** to a Flow if no active Flow exists.

```java
public class OrderService {

  @Step(name = TelemetryNames.STEP_CHECKOUT_VALIDATE)
  @OrphanAlert(level = OrphanAlert.Level.WARN) // log WARN when promoted
  public void validate(
      @BindEventAttribute("order.id") String orderId,
      @BindEventAttribute("order.total") BigDecimal total) { /* ... */ }

  @Step(name = TelemetryNames.STEP_CHECKOUT_CHARGE)
  @Kind(SpanKind.CLIENT)
  public void charge(
      @BindEventAttribute("order.id") String orderId,
      @BindEventAttribute("payment.method") String method) { /* ... */ }

  @Step(name = TelemetryNames.STEP_CHECKOUT_PERSIST)
  public void persist(@BindEventAttribute("order.id") String orderId) { /* ... */ }
}
```

### `@OrphanAlert`

Controls the log level when a `@Step` is invoked without an active `@Flow` and is **auto‑promoted**.
Levels: `ERROR` (default), `WARN`, `INFO`, `DEBUG`.

### `@Kind`

Sets OTEL `SpanKind` at class or method level (`SERVER`, `CLIENT`, `PRODUCER`, `CONSUMER`, `INTERNAL`).

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
* `@BindAllAttributes` → **read‑only** full attribute map (`Map<String,Object>`)
* `@BindAllContextValues` → **read‑only** full context map (`Map<String,Object>`)
* `@BindEventThrowable` → event’s `Throwable` (or cause)
* `TelemetryHolder` → current event
* `List<TelemetryHolder>` → batch for `ROOT_FLOW_FINISHED`

**Note:** `@BindAllAttributes` and `@BindAllContextValues` inject **unmodifiable snapshots**.

---

## Programmatic Attribute & Context Setting

```java
@Service
@RequiredArgsConstructor
class PaymentService {
  private final TelemetryContext telemetry;

  @Step(name = TelemetryNames.STEP_CHECKOUT_CHARGE)
  public void charge(String userId, long amountCents) {
    telemetry.putAttribute("user.id", userId);           // persisted
    telemetry.putAttribute("amount.cents", amountCents); // persisted
    telemetry.putContextValue("retry", false);           // ephemeral
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
Bind parameters (@BindEventAttribute, @BindContextValue, @BindAllAttributes, @BindAllContextValues, @BindEventThrowable, ...)
  ↓
Persisted attributes serialized/exported; context values are ephemeral and discarded at scope end
```

---

## Event Handling with `@OnEvent`

### Example 1 — Basic handler class

```java
@TelemetryEventHandler
@Component
public class CheckoutEventHandlers {

  // Root flow completion with nested flows aggregation
  @OnEvent(lifecycle = {Lifecycle.ROOT_FLOW_FINISHED}, name = TelemetryNames.EVENT_CHECKOUT_PROCESS)
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
@TelemetryEventHandler
@Component
public class AdvancedEventHandlers {

  // Any lifecycle for a specific event name
  @OnEvent(name = TelemetryNames.EVENT_INVENTORY_RESERVE)
  public void anyLifecycleForInventory(TelemetryHolder holder) { /* … */ }

  // Client-kind finished flows whose names start with payment.
  @OnEvent(
    lifecycle = {Lifecycle.FLOW_FINISHED},
    kinds = {SpanKind.CLIENT},
    nameRegex = TelemetryNames.REGEX_PAYMENT_PREFIX
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

  // Multi-tenant completion with required attributes and context
  @OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})
  @RequiredAttributes({"tenant.id", "region"})
  @RequiredEventContext({"correlationId"})
  public void multiTenantFinish(
      @BindEventAttribute("tenant.id") String tenantId,
      @BindEventAttribute("region") String region,
      @BindContextValue("correlationId") String correlationId,
      TelemetryHolder holder) { /* … */ }

  // Inspect all persisted attributes at once (read-only map)
  @OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})
  public void allAttrs(@BindAllAttributes Map<String, Object> attrs) {
    // attrs is unmodifiable
    attrs.forEach((k, v) -> log.info("{}={}", k, v));
  }
}
```

---

## Handler Failure & Logging Policy

* Handler exceptions **do not** abort dispatch; remaining handlers still run.
* Binding failures are logged with handler ID, event name, lifecycle, and key/type details.
* If required selections (throwable presence/type, attributes/context) are not met, the handler is skipped (with diagnostics at debug level).

---

## OTEL `SpanKind` Reference (when to use)

| SpanKind   | Use when your code…                       | Examples                                       |
| ---------- | ----------------------------------------- | ---------------------------------------------- |
| `SERVER`   | Handles an **incoming** request/message   | HTTP controller, gRPC server, message consumer |
| `CLIENT`   | Makes an **outgoing** request             | HTTP/gRPC client call, external API, DB driver |
| `PRODUCER` | **Publishes** to a broker/topic/queue     | Kafka/RabbitMQ/SNS send                        |
| `CONSUMER` | **Receives/processes** a brokered message | Kafka poll, RabbitMQ listener, SQS handler     |
| `INTERNAL` | Performs **in‑process** work              | Cache/recompute, rule evaluation, CPU step     |

**Rules of thumb:** incoming boundary → `SERVER`; outgoing dependency → `CLIENT`; async send/receive → `PRODUCER`/`CONSUMER`; everything else → `INTERNAL`.

---

## Cheat‑Sheet: Common `@OnEvent` Patterns

| Pattern                        | `@OnEvent` Example                                                               | Selection Effect                    | Binding Example                                  |
| ------------------------------ | -------------------------------------------------------------------------------- | ----------------------------------- | ------------------------------------------------ |
| Match any event, any lifecycle | `@OnEvent`                                                                       | All events                          | `TelemetryHolder holder`                         |
| Match by exact name            | `@OnEvent(name = EVENT_CHECKOUT_PROCESS)`                                        | Only that exact name                | `TelemetryHolder holder`                         |
| Match by regex name            | `@OnEvent(nameRegex = "^payment\\.")`                                            | Any name starting with `payment.`   | `@BindEventAttribute("payment.id") String id`    |
| Match by lifecycle only        | `@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})`                                | All finished flows                  | `TelemetryHolder holder`                         |
| Lifecycle + exact name         | `@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED}, name = EVENT_CHECKOUT_PROCESS)` | Specific name at specific lifecycle | `@BindContextValue("debugMode") Boolean d`       |
| Lifecycle + kind               | `@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED}, kinds = {SpanKind.SERVER})`     | Finished server spans               | `@BindAllContextValues Map<String,Object> ctx`   |
| Require attributes             | `@OnEvent @RequiredAttributes({"tenant.id","region"})`                           | Only if both attrs present          | `@BindEventAttribute("tenant.id") String tenant` |
| Require event context          | `@OnEvent @RequiredEventContext({"correlationId"})`                              | Only if context key present         | `@BindContextValue("correlationId") String cid`  |
| Require throwable              | `@OnEvent(requireThrowable = true)`                                              | Only if an error is attached        | `@BindEventThrowable Throwable cause`            |
| Throwable type filter          | `@OnEvent(throwableTypes = {IOException.class}, includeSubclasses = true)`       | IOException or subclass             | `@BindEventThrowable Throwable t`                |
| Batch for `ROOT_FLOW_FINISHED` | `@OnEvent(lifecycle = {Lifecycle.ROOT_FLOW_FINISHED})`                           | Root + all nested flows per root    | `List<TelemetryHolder> flows`                    |
| All attributes (read‑only)     | `@OnEvent @BindAllAttributes`                                                    | Inject full attributes map          | `Map<String,Object> attrs`                       |
| All context (read‑only)        | `@OnEvent @BindAllContextValues`                                                 | Inject full context map             | `Map<String,Object> ctx`                         |
