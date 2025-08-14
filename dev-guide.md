# Obsinity Telemetry Developer Guide

## Annotation Reference

### Core (structure & selection)

| Annotation               | Target         | Purpose                                                                                           |
| ------------------------ | -------------- | ------------------------------------------------------------------------------------------------- |
| `@Flow`                  | Method / Class | Starts a **flow** (top-level event). Activates scope so nested `@Step` calls become child events. |
| `@Step`                  | Method         | Emits a **step** event inside the active flow; auto-promoted to a flow if none is active.         |
| `@Kind`                  | Method / Class | Sets OTEL `SpanKind` (`SERVER`, `CLIENT`, `PRODUCER`, `CONSUMER`, `INTERNAL`).                    |
| `@OrphanAlert`           | Method / Class | Controls log level when a `@Step` is auto-promoted due to missing active `@Flow`.                 |
| `@OnEvent`               | Method         | Declares an **event handler**; filters by lifecycle, name/regex, kind, throwable, etc.            |
| `@TelemetryEventHandler` | Class          | Marks a bean that contains `@OnEvent` methods (dispatcher scans only these classes).              |
| `@RequiredAttributes`    | Method         | Handler runs **only if** all listed **attributes** are present.                                   |
| `@RequiredEventContext`  | Method         | Handler runs **only if** all listed **context** keys are present.                                 |
| `@BindEventThrowable`    | Parameter      | Injects the event’s `Throwable` (or cause) if present.                                            |

---

### Push Annotations (Flows/Steps WRITE values)

> Use these **only** in `@Flow` / `@Step` methods.

| Annotation          | Target    | Purpose                                                          |
| ------------------- | --------- | ---------------------------------------------------------------- |
| `@PushAttribute`    | Parameter | **Saves** the parameter value into **attributes** under the key. |
| `@PushContextValue` | Parameter | **Saves** the parameter value into **context** under the key.    |

---

### Pull Annotations (`@OnEvent` handlers READ values)

> Use these **only** in `@OnEvent` handler methods.

| Annotation              | Target    | Purpose                                                                     |
| ----------------------- | --------- | --------------------------------------------------------------------------- |
| `@PullAttribute`        | Parameter | Injects a single **attribute** by key.                                      |
| `@PullAllAttributes`    | Parameter | Injects an **unmodifiable Map** of **all attributes** on the current event. |
| `@PullContextValue`     | Parameter | Injects a single **context** value by key.                                  |
| `@PullAllContextValues` | Parameter | Injects an **unmodifiable Map** of **all context** values on the event.     |

---

## Overview

| Term               | Description                                                                                   |
| ------------------ | --------------------------------------------------------------------------------------------- |
| **Flow event**     | Top-level telemetry unit for a logical operation (e.g., `checkout.process`).                  |
| **Child event**    | Event emitted by a `@Step` inside an active Flow.                                             |
| **Auto-promotion** | When a `@Step` is called with **no active Flow**, it is promoted to a Flow event.             |
| **Orphan alert**   | Log emitted on auto-promotion; level controlled by `@OrphanAlert` (default `ERROR`).          |
| **Attributes**     | Saved key/value pairs recorded on the event.                                                  |
| **Event Context**  | Ephemeral, in-process key/value pairs **not** serialized or stored.                           |
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

```java
@Kind(SpanKind.SERVER) // Flow marked as SERVER span
public class OrderService {

  @Flow(name = TelemetryNames.EVENT_CHECKOUT_PROCESS)
  public Receipt checkout(
      @PushAttribute("order.id")   String orderId,     // Save in attributes
      @PushAttribute("order.total") BigDecimal total,  // Save in attributes
      @PushContextValue("correlationId") String correlationId) { // Save in context

    validate(orderId, total); // Child step 1
    charge(orderId, total);   // Child step 2
    persist(orderId);         // Child step 3
    return new Receipt();
  }
}
```

---

### `@Step`

```java
public class OrderService {

  @Step(name = TelemetryNames.STEP_CHECKOUT_VALIDATE)
  @OrphanAlert(level = OrphanAlert.Level.WARN) // Log WARN if auto-promoted
  public void validate(
      @PushAttribute("order.id") String orderId,       // Save in attributes
      @PushAttribute("order.total") BigDecimal total) { /* ... */ }

  @Step(name = TelemetryNames.STEP_CHECKOUT_CHARGE)
  @Kind(SpanKind.CLIENT)
  public void charge(
      @PushAttribute("order.id") String orderId,
      @PushAttribute("payment.method") String method,  // Save in attributes
      @PushContextValue("retry") boolean retry) { /* ... */ }

  @Step(name = TelemetryNames.STEP_CHECKOUT_PERSIST)
  public void persist(@PushAttribute("order.id") String orderId) { /* ... */ }
}
```

---

## `@OnEvent` Handlers → **Pull** values (READ)

### Selection vs Binding

* **Selection**: `name`, `nameRegex`, `lifecycle`, `kinds`, throwable filters, `@RequiredAttributes`, `@RequiredEventContext`.
* **Binding**: Inject values from attributes, context, throwable, or `TelemetryHolder`.

---

### Examples

```java
@TelemetryEventHandler
@Component
public class CheckoutEventHandlers {

  @OnEvent(lifecycle = {Lifecycle.ROOT_FLOW_FINISHED}, name = TelemetryNames.EVENT_CHECKOUT_PROCESS)
  public void rootDone(List<TelemetryHolder> flows) { /* All flows in root */ }

  @OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})
  @RequiredAttributes({"order.id"})
  public void finished(@PullAttribute("order.id") String orderId, // Read from attributes
                       TelemetryHolder holder) { /* ... */ }

  @OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED}, kinds = {SpanKind.SERVER})
  public void serverOnly(@PullAllContextValues Map<String, Object> ctx) { /* Read all context */ }

  @OnEvent(requireThrowable = true, throwableTypes = { java.io.IOException.class }, includeSubclasses = true)
  public void ioFailures(@BindEventThrowable Throwable cause, // Read Throwable
                         TelemetryHolder holder) { /* ... */ }

  @OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})
  @RequiredAttributes({"tenant.id", "region"})
  @RequiredEventContext({"correlationId"})
  public void multiTenantFinish(
      @PullAttribute("tenant.id") String tenantId,        // Read from attributes
      @PullAttribute("region") String region,            // Read from attributes
      @PullContextValue("correlationId") String correlationId, // Read from context
      TelemetryHolder holder) { /* ... */ }
}
```

---

## Programmatic APIs (optional)

```java
@Service
@RequiredArgsConstructor
class PaymentService {
  private final TelemetryProcessorSupport telemetry;

  @Step(name = TelemetryNames.STEP_CHECKOUT_CHARGE)
  public void charge(String userId, long amountCents) {
    telemetry.putAttr("user.id", userId);           // Save in attributes
    telemetry.putAttr("amount.cents", amountCents); // Save in attributes
    telemetry.putContext("retry", false);           // Save in context
  }
}
```

---

## Attribute & Context Lifecycle

```
@Flow/@Step entry
  ↓
Save values (via @PushAttribute / @PushContextValue) and/or programmatic puts
  ↓
Emit event
  ↓
@OnEvent handlers selected (filters, requirements)
  ↓
Bind parameters (via @PullAttribute / @PullAllAttributes / @PullContextValue / @PullAllContextValues / @BindEventThrowable / ...)
  ↓
Attributes saved to output/export; context values are ephemeral and discarded at scope end
```

---

## Handler Failure & Logging Policy

* Handler exceptions do not stop other handlers.
* Binding failures are logged with handler ID, event name, lifecycle, and key/type details.
* If selection requirements aren’t met, handler is skipped (debug logs).

---

## OTEL `SpanKind` Reference

| SpanKind   | Use when your code…                       | Examples                                       |
| ---------- | ----------------------------------------- | ---------------------------------------------- |
| `SERVER`   | Handles an **incoming** request/message   | HTTP controller, gRPC server, message consumer |
| `CLIENT`   | Makes an **outgoing** request             | HTTP/gRPC client call, external API, DB driver |
| `PRODUCER` | **Publishes** to a broker/topic/queue     | Kafka/RabbitMQ/SNS send                        |
| `CONSUMER` | **Receives/processes** a brokered message | Kafka poll, RabbitMQ listener, SQS handler     |
| `INTERNAL` | Performs **in-process** work              | Cache/recompute, rule evaluation, CPU step     |

---

## Cheat-Sheet: Common `@OnEvent` Patterns

| Pattern                      | Example                                                                          | Selection Effect                 | Binding Example                                 |
| ---------------------------- | -------------------------------------------------------------------------------- | -------------------------------- | ----------------------------------------------- |
| Match any event              | `@OnEvent`                                                                       | All events                       | `TelemetryHolder holder`                        |
| Match by exact name          | `@OnEvent(name = EVENT_CHECKOUT_PROCESS)`                                        | Only that name                   | `TelemetryHolder holder`                        |
| Match by regex name          | `@OnEvent(nameRegex = "^payment\\.")`                                            | Any payment.\* name              | `@PullAttribute("payment.id") String id`        |
| Match by lifecycle only      | `@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})`                                | All finished flows               | `TelemetryHolder holder`                        |
| Lifecycle + exact name       | `@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED}, name = EVENT_CHECKOUT_PROCESS)` | Specific name at lifecycle       | `@PullContextValue("debugMode") Boolean d`      |
| Lifecycle + kind             | `@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED}, kinds = {SpanKind.SERVER})`     | Finished server spans            | `@PullAllContextValues Map<String,Object> ctx`  |
| Require attributes           | `@OnEvent @RequiredAttributes({"tenant.id","region"})`                           | Only if both saved in attributes | `@PullAttribute("tenant.id") String tenant`     |
| Require event context        | `@OnEvent @RequiredEventContext({"correlationId"})`                              | Only if context key present      | `@PullContextValue("correlationId") String cid` |
| Require throwable            | `@OnEvent(requireThrowable = true)`                                              | Only if error attached           | `@BindEventThrowable Throwable cause`           |
| Throwable type filter        | `@OnEvent(throwableTypes = {IOException.class}, includeSubclasses = true)`       | IOException or subclass          | `@BindEventThrowable Throwable t`               |
| Batch for root flow finished | `@OnEvent(lifecycle = {Lifecycle.ROOT_FLOW_FINISHED})`                           | Root + nested flows per root     | `List<TelemetryHolder> flows`                   |
| All attributes (read-only)   | `@OnEvent @PullAllAttributes`                                                    | Inject full attributes map       | `Map<String,Object> attrs`                      |
| All context (read-only)      | `@OnEvent @PullAllContextValues`                                                 | Inject full context map          | `Map<String,Object> ctx`                        |
