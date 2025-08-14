# Obsinity Telemetry Developer Guide

## Annotation Reference

### Core (structure & selection)

| Annotation               | Target         | Purpose                                                                                           |
| ------------------------ | -------------- | ------------------------------------------------------------------------------------------------- |
| `@Flow`                  | Method / Class | Starts a **flow** (top‑level event). Activates scope so nested `@Step` calls become child events. |
| `@Step`                  | Method         | Emits a **step** event inside the active flow; auto‑promoted to a flow if none is active.         |
| `@Kind`                  | Method / Class | Sets OTEL `SpanKind` (`SERVER`, `CLIENT`, `PRODUCER`, `CONSUMER`, `INTERNAL`).                    |
| `@OrphanAlert`           | Method / Class | Controls log level when a `@Step` is auto‑promoted due to missing active `@Flow`.                 |
| `@OnEvent`               | Method         | Declares an **event handler**; filters by lifecycle, name/regex, kind, throwable, etc.            |
| `@TelemetryEventHandler` | Class          | Marks a bean that contains `@OnEvent` methods (dispatcher scans only these classes).              |
| `@RequiredAttributes`    | Method         | Handler runs **only if** all listed **persisted attributes** are present.                         |
| `@RequiredEventContext`  | Method         | Handler runs **only if** all listed **ephemeral context** keys are present.                       |
| `@BindEventThrowable`    | Parameter      | Injects the event’s `Throwable` (or cause) if present.                                            |

### Push Annotations (Flows/Steps WRITE values)

> Use these **only** in `@Flow` / `@Step` methods.

| Annotation          | Target    | Purpose                                                                           |
| ------------------- | --------- | --------------------------------------------------------------------------------- |
| `@PushAttribute`    | Parameter | **Writes** the parameter value into **persisted attributes** under the given key. |
| `@PushContextValue` | Parameter | **Writes** the parameter value into **ephemeral context** under the given key.    |

### Pull Annotations (`@OnEvent` handlers READ values)

> Use these **only** in `@OnEvent` handler methods.

| Annotation              | Target    | Purpose                                                                                   |
| ----------------------- | --------- | ----------------------------------------------------------------------------------------- |
| `@PullAttribute`        | Parameter | Injects a single **persisted attribute** by key.                                          |
| `@PullAllAttributes`    | Parameter | Injects an **unmodifiable Map** of **all persisted attributes** on the current event.     |
| `@PullContextValue`     | Parameter | Injects a single **ephemeral context** value by key.                                      |
| `@PullAllContextValues` | Parameter | Injects an **unmodifiable Map** of **all ephemeral context values** on the current event. |

---

**This guide covers:**

* How to instrument code using Obsinity’s annotations
* **Push vs Pull** responsibilities
* Step auto‑promotion & orphan alerts
* Handler selection vs parameter binding
* Programmatic APIs and lifecycle
* Failure & logging policy

---

## Overview

| Term               | Description                                                                                   |
| ------------------ | --------------------------------------------------------------------------------------------- |
| **Flow event**     | Top‑level telemetry unit for a logical operation (e.g., `checkout.process`).                  |
| **Child event**    | Event emitted by a `@Step` inside an active Flow.                                             |
| **Auto‑promotion** | When a `@Step` is called with **no active Flow**, it is promoted to a Flow event.             |
| **Orphan alert**   | Log emitted on auto‑promotion; level controlled by `@OrphanAlert` (default `ERROR`).          |
| **Attributes**     | Persisted key/value pairs recorded on the event.                                              |
| **Event Context**  | Ephemeral, in‑process key/value pairs **not** serialized or stored.                           |
| **Event handler**  | Method annotated with `@OnEvent` that receives events filtered by lifecycle, name, kind, etc. |

---

## Naming via Constants (recommended)

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

---

## Flows & Steps → **Push** values (WRITE)

### `@Flow`

Starts a **Flow** and activates scope so nested `@Step` calls become child events.
Use `@Push*` on parameters to set attributes/context **before** the event is emitted.

```java
@Kind(SpanKind.SERVER)
public class OrderService {

  @Flow(name = TelemetryNames.EVENT_CHECKOUT_PROCESS)
  public Receipt checkout(
      @PushAttribute("order.id")   String orderId,
      @PushAttribute("order.total") BigDecimal total,
      @PushContextValue("correlationId") String correlationId) {

    validate(orderId, total);
    charge(orderId, total);
    persist(orderId);
    return new Receipt();
  }
}
```

### `@Step`

Represents a unit of work within a Flow; **auto‑promoted** to a Flow if no active Flow exists.
Use `@Push*` on parameters to set values for the step.

```java
public class OrderService {

  @Step(name = TelemetryNames.STEP_CHECKOUT_VALIDATE)
  @OrphanAlert(level = OrphanAlert.Level.WARN) // log WARN when promoted
  public void validate(
      @PushAttribute("order.id") String orderId,
      @PushAttribute("order.total") BigDecimal total) { /* ... */ }

  @Step(name = TelemetryNames.STEP_CHECKOUT_CHARGE)
  @Kind(SpanKind.CLIENT)
  public void charge(
      @PushAttribute("order.id") String orderId,
      @PushAttribute("payment.method") String method,
      @PushContextValue("retry") boolean retry) { /* ... */ }

  @Step(name = TelemetryNames.STEP_CHECKOUT_PERSIST)
  public void persist(@PushAttribute("order.id") String orderId) { /* ... */ }
}
```

---

## `@OnEvent` Handlers → **Pull** values (READ)

### Selection vs Binding

* **Selection** (on `@OnEvent` and helper annotations) decides if the handler runs:

    * `name` or `nameRegex`
    * `lifecycle` (array of `Lifecycle`)
    * `kinds` (array of `SpanKind`)
    * Throwable filters: `requireThrowable`, `throwableTypes`, `includeSubclasses`
    * `@RequiredAttributes({...})`
    * `@RequiredEventContext({...})`

* **Binding** (handler parameters) injects what you can read:

    * `@PullAttribute("key")`, `@PullAllAttributes`
    * `@PullContextValue("key")`, `@PullAllContextValues`
    * `@BindEventThrowable`
    * `TelemetryHolder`
    * `List<TelemetryHolder>` (for `ROOT_FLOW_FINISHED`)

### Examples

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
  public void finished(@PullAttribute("order.id") String orderId, TelemetryHolder holder) { /* … */ }

  // SERVER kind finished flows; inspect all context values (read-only)
  @OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED}, kinds = {SpanKind.SERVER})
  public void serverOnly(@PullAllContextValues Map<String, Object> ctx) { /* … */ }

  // IO failures only (Throwable required and type-matched)
  @OnEvent(requireThrowable = true, throwableTypes = { java.io.IOException.class }, includeSubclasses = true)
  public void ioFailures(@BindEventThrowable Throwable cause, TelemetryHolder holder) { /* … */ }

  // Multi-tenant completion requiring attributes and context
  @OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})
  @RequiredAttributes({"tenant.id", "region"})
  @RequiredEventContext({"correlationId"})
  public void multiTenantFinish(
      @PullAttribute("tenant.id") String tenantId,
      @PullAttribute("region") String region,
      @PullContextValue("correlationId") String correlationId,
      TelemetryHolder holder) { /* … */ }
}
```

---

## Programmatic APIs (optional)

If you prefer imperative setting in some places (e.g., loops), you can still use the support API.
(Prefer `@Push*` in `@Flow`/`@Step` where possible.)

```java
@Service
@RequiredArgsConstructor
class PaymentService {
  private final TelemetryProcessorSupport telemetry;

  @Step(name = TelemetryNames.STEP_CHECKOUT_CHARGE)
  public void charge(String userId, long amountCents) {
    telemetry.putAttr("user.id", userId);           // persisted
    telemetry.putAttr("amount.cents", amountCents); // persisted
    telemetry.putContext("retry", false);           // ephemeral
  }
}
```

---

## Attribute & Context Lifecycle

```
@Flow/@Step entry
  ↓
Push values (via @PushAttribute / @PushContextValue) and/or programmatic puts
  ↓
Emit event
  ↓
@OnEvent handlers selected (name/regex, lifecycle, kinds, throwable filters,
RequiredAttributes, RequiredEventContext)
  ↓
Bind parameters (via @PullAttribute / @PullAllAttributes / @PullContextValue / @PullAllContextValues / @BindEventThrowable / ...)
  ↓
Persisted attributes serialized/exported; context values are ephemeral and discarded at scope end
```

---

## Handler Failure & Logging Policy

* Handler exceptions **do not** abort dispatch; remaining handlers still run.
* Binding failures are logged with handler ID, event name, lifecycle, and key/type details.
* If required selections (throwable presence/type, attributes/context) are not met, the handler is skipped (diagnostics at debug level).

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

## Cheat‑Sheet: Common `@OnEvent` Patterns (Pull‑only)

| Pattern                        | `@OnEvent` Example                                                               | Selection Effect                    | Binding Example                                 |
| ------------------------------ | -------------------------------------------------------------------------------- | ----------------------------------- | ----------------------------------------------- |
| Match any event, any lifecycle | `@OnEvent`                                                                       | All events                          | `TelemetryHolder holder`                        |
| Match by exact name            | `@OnEvent(name = EVENT_CHECKOUT_PROCESS)`                                        | Only that exact name                | `TelemetryHolder holder`                        |
| Match by regex name            | `@OnEvent(nameRegex = "^payment\\.")`                                            | Any name starting with `payment.`   | `@PullAttribute("payment.id") String id`        |
| Match by lifecycle only        | `@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})`                                | All finished flows                  | `TelemetryHolder holder`                        |
| Lifecycle + exact name         | `@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED}, name = EVENT_CHECKOUT_PROCESS)` | Specific name at specific lifecycle | `@PullContextValue("debugMode") Boolean d`      |
| Lifecycle + kind               | `@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED}, kinds = {SpanKind.SERVER})`     | Finished server spans               | `@PullAllContextValues Map<String,Object> ctx`  |
| Require attributes             | `@OnEvent @RequiredAttributes({"tenant.id","region"})`                           | Only if both attrs present          | `@PullAttribute("tenant.id") String tenant`     |
| Require event context          | `@OnEvent @RequiredEventContext({"correlationId"})`                              | Only if context key present         | `@PullContextValue("correlationId") String cid` |
| Require throwable              | `@OnEvent(requireThrowable = true)`                                              | Only if an error is attached        | `@BindEventThrowable Throwable cause`           |
| Throwable type filter          | `@OnEvent(throwableTypes = {IOException.class}, includeSubclasses = true)`       | IOException or subclass             | `@BindEventThrowable Throwable t`               |
| Batch for `ROOT_FLOW_FINISHED` | `@OnEvent(lifecycle = {Lifecycle.ROOT_FLOW_FINISHED})`                           | Root + all nested flows per root    | `List<TelemetryHolder> flows`                   |
| All attributes (read‑only)     | `@OnEvent @PullAllAttributes`                                                    | Inject full attributes map          | `Map<String,Object> attrs`                      |
| All context (read‑only)        | `@OnEvent @PullAllContextValues`                                                 | Inject full context map             | `Map<String,Object> ctx`                        |
