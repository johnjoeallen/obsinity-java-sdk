Alright — here’s the **complete merged document**.
It keeps your original structure, adds the **extensive annotation descriptions**, and preserves all the **Java examples** so it’s a single, self-contained developer reference.

---

# Obsinity Telemetry Developer Guide

## Annotation Reference

### Core (structure & selection)

| Annotation               | Target         | Purpose                                                                                           |
| ------------------------ | -------------- | ------------------------------------------------------------------------------------------------- |
| `@Flow`                  | Method / Class | Starts a **flow** (top-level event). Activates scope so nested `@Step` calls become child events. |
| `@Step`                  | Method         | Emits a **step** event inside the active flow; auto-promoted to a flow if none is active.         |
| `@Kind`                  | Method / Class | Sets OTEL `SpanKind` (`SERVER`, `CLIENT`, `PRODUCER`, `CONSUMER`, `INTERNAL`).                    |
| `@OrphanAlert`           | Method / Class | Controls log level when a `@Step` is auto-promoted due to missing active `@Flow`.                 |
| `@OnEvent`               | Method         | Declares an **event handler**; filters by lifecycle, name, prefix, kind, throwable, etc.          |
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

## Detailed Annotation Descriptions

### `@Flow` — Start a top-level telemetry flow

**Purpose**
Marks the start of a **root telemetry flow** (top-level event).
All `@Step` calls made inside it become child events.

**Target**

* Method
* Class (applies to all public methods)

**Key parameters**

* `name` *(String, optional)* — event name. Defaults to method name if omitted.
* All `@PushAttribute` / `@PushContextValue` parameters are stored on the **root event**.

**Behavior**

* Creates a new `TelemetryHolder` root scope.
* If applied at class level, all public methods start a new flow unless they are themselves annotated differently.
* Child steps inherit lifecycle from the flow.

**Example**

```java
@Kind(SpanKind.SERVER)
public class OrderService {
  @Flow(name = "checkout.process")
  public Receipt checkout(@PushAttribute("order.id") String id) { /* ... */ }
}
```

---

### `@Step` — Emit a telemetry step

**Purpose**
Records a smaller **unit of work** inside an active flow.

**Target**

* Method only

**Key parameters**

* `name` *(String, optional)* — defaults to method name.

**Behavior**

* If no active flow exists, the step is **auto-promoted** to a flow.
* Promotion logging is controlled by `@OrphanAlert`.
* Steps can push attributes and context just like flows.

**Example**

```java
@Step(name = "checkout.validate")
public void validate(@PushAttribute("order.id") String id) { /* ... */ }
```

---

### `@Kind` — Set span kind

**Purpose**
Sets the OTEL `SpanKind` for a flow or step:
`SERVER`, `CLIENT`, `PRODUCER`, `CONSUMER`, `INTERNAL`.

**Target**

* Method
* Class (method overrides class setting)

**Behavior**

* Guides OTEL exporters and trace visualizations.
* Defaults to `INTERNAL` if not specified.

**Example**

```java
@Step(name = "checkout.charge")
@Kind(SpanKind.CLIENT)
public void chargeCard(...) { /* ... */ }
```

---

### `@OrphanAlert` — Log when a step is promoted

**Purpose**
Controls **log level** for auto-promoted steps (no active `@Flow`).

**Target**

* Method
* Class

**Key parameters**

* `level` *(enum)* — `ERROR` (default), `WARN`, `INFO`, `DEBUG`, `TRACE`.

**Behavior**

* Useful for catching instrumentation gaps in development.
* Logged once per promotion occurrence.

**Example**

```java
@Step
@OrphanAlert(level = OrphanAlert.Level.WARN)
public void standaloneStep(...) { /* ... */ }
```

---

### `@OnEvent` — Event handler

**Purpose**
Run this method when an event matches selection rules.

**Target**

* Method

**Selection criteria (groups)**

**A. Name matching (pick one)**

* `name` — exact match
* `namePrefix` — startsWith match

> If both set, `name` takes precedence.

**B. Lifecycle & Kind**

* `lifecycle` — restrict to given `Lifecycle` phases.
* `kinds` — restrict to given `SpanKind`s.

**C. Exception filters** *(optional)*

* `requireThrowable` — must have a throwable
* `throwableTypes` — allowed throwable types
* `includeSubclasses` — match subclasses too
* `messageRegex` — regex for `Throwable.getMessage()`
* `causeType` — fully qualified class name for `Throwable.getCause()`

**D. Batch mode**

* If `lifecycle = ROOT_FLOW_FINISHED` and param is `List<TelemetryHolder>`, all flows in that root are passed together.

**Matching order**

1. Name →
2. Lifecycle →
3. Kind →
4. Exception filters (if applicable)

**Examples**

```java
@OnEvent(namePrefix = "checkout.", lifecycle = {Lifecycle.FLOW_FINISHED})
public void onCheckout(TelemetryHolder h) { /* ... */ }

@OnEvent(requireThrowable = true, throwableTypes = {IOException.class})
public void onIoFailure(@BindEventThrowable Throwable err) { /* ... */ }
```

---

### `@TelemetryEventHandler` — Mark a handler class

**Purpose**
Marks a Spring bean class that contains `@OnEvent` methods.
Dispatcher **only scans** beans with this annotation.

**Target**

* Class

**Example**

```java
@TelemetryEventHandler
@Component
public class CheckoutHandlers { /* @OnEvent methods here */ }
```

---

### `@RequiredAttributes` — Attribute presence filter

**Purpose**
Only run the handler if **all listed attributes** are present.

**Target**

* Method (must also be `@OnEvent`)

**Example**

```java
@OnEvent
@RequiredAttributes({"tenant.id", "region"})
public void multiTenantHandler(...) { /* ... */ }
```

---

### `@RequiredEventContext` — Context presence filter

**Purpose**
Only run the handler if **all listed event context keys** are present.

**Target**

* Method (must also be `@OnEvent`)

**Example**

```java
@OnEvent
@RequiredEventContext({"correlationId"})
public void traceableHandler(...) { /* ... */ }
```

---

### `@BindEventThrowable` — Inject the throwable

**Purpose**
Inject the event's throwable or cause into a handler parameter.

**Target**

* Method parameter

**Behavior**

* Parameter type must be `Throwable` or subclass.
* Works with exception filters on `@OnEvent`.

**Example**

```java
@OnEvent(requireThrowable = true)
public void onError(@BindEventThrowable Throwable cause) { /* ... */ }
```

---

### `@PushAttribute` — Save attribute (flows & steps)

**Purpose**
Saves the method parameter value into the event’s **attributes**.

**Target**

* Method parameter (in a `@Flow` or `@Step`)

**Parameters**

* `value` or `name` — key to save under
* `omitIfNull` — skip if null (default `true`)

**Example**

```java
@Flow
public void start(@PushAttribute("order.id") String id) { /* ... */ }
```

---

### `@PushContextValue` — Save context value (flows & steps)

**Purpose**
Saves the parameter value into the event’s **context**.

**Target**

* Method parameter (in a `@Flow` or `@Step`)

**Example**

```java
@Step
public void markRetry(@PushContextValue("retry") boolean retry) { /* ... */ }
```

---

### `@PullAttribute` — Read single attribute

**Purpose**
Injects one attribute value into a handler parameter.

**Target**

* Method parameter (in an `@OnEvent`)

**Example**

```java
@OnEvent
public void handle(@PullAttribute("order.id") String id) { /* ... */ }
```

---

### `@PullAllAttributes` — Read all attributes

**Purpose**
Injects a read-only map of **all attributes**.

**Target**

* Method parameter (in an `@OnEvent`)

**Example**

```java
@OnEvent
public void handle(@PullAllAttributes Map<String,Object> attrs) { /* ... */ }
```

---

### `@PullContextValue` — Read single context value

**Purpose**
Injects one context value into a handler parameter.

**Target**

* Method parameter (in an `@OnEvent`)

**Example**

```java
@OnEvent
public void handle(@PullContextValue("correlationId") String cid) { /* ... */ }
```

---

### `@PullAllContextValues` — Read all context values

**Purpose**
Injects a read-only map of **all context**.

**Target**

* Method parameter (in an `@OnEvent`)

**Example**

```java
@OnEvent
public void handle(@PullAllContextValues Map<String,Object> ctx) { /* ... */ }
```

---

### `@TelemetryEventHandler`

Marks a bean for event handler scanning.

* Only these beans are inspected for `@OnEvent` methods.

---

### `@RequiredAttributes`

Handler only runs if all listed attributes exist.

* Use for routing logic (e.g., tenant or region filters).

---

### `@RequiredEventContext`

Handler only runs if all listed context keys exist.

* Context is ephemeral and not persisted.

---

### `@BindEventThrowable`

Injects the event’s `Throwable` (if any) into a handler parameter.

* Combine with `requireThrowable = true` for failure-only handlers.

---

### `@PushAttribute`

Writes a parameter to event attributes under the given key.

* Default `omitIfNull = true`.

---

### `@PushContextValue`

Writes a parameter to the event’s context (ephemeral).

---

### `@PullAttribute`

Reads a single attribute by key into a handler parameter.

---

### `@PullAllAttributes`

Injects all attributes as an unmodifiable `Map<String,Object>`.

---

### `@PullContextValue`

Reads a single context value by key.

---

### `@PullAllContextValues`

Injects all context values as an unmodifiable `Map<String,Object>`.

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
  public static final String PREFIX_PAYMENT           = "payment.";
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
      @PushAttribute("order.id") String orderId,
      @PushAttribute("order.total") BigDecimal total,
      @PushContextValue("correlationId") String correlationId) {

    validate(orderId, total); // Step 1
    charge(orderId, total);   // Step 2
    persist(orderId);         // Step 3
    return new Receipt();
  }
}
```

---

### `@Step`

```java
public class OrderService {

  @Step(name = TelemetryNames.STEP_CHECKOUT_VALIDATE)
  @OrphanAlert(level = OrphanAlert.Level.WARN)
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

```java
@TelemetryEventHandler
@Component
public class CheckoutEventHandlers {

  @OnEvent(lifecycle = {Lifecycle.ROOT_FLOW_FINISHED}, name = TelemetryNames.EVENT_CHECKOUT_PROCESS)
  public void rootDone(List<TelemetryHolder> flows) { /* All flows in root */ }

  @OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})
  @RequiredAttributes({"order.id"})
  public void finished(@PullAttribute("order.id") String orderId,
                       TelemetryHolder holder) { /* ... */ }

  @OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED}, kinds = {SpanKind.SERVER})
  public void serverOnly(@PullAllContextValues Map<String, Object> ctx) { /* ... */ }

  @OnEvent(requireThrowable = true, throwableTypes = {java.io.IOException.class}, includeSubclasses = true)
  public void ioFailures(@BindEventThrowable Throwable cause,
                         TelemetryHolder holder) { /* ... */ }

  @OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})
  @RequiredAttributes({"tenant.id", "region"})
  @RequiredEventContext({"correlationId"})
  public void multiTenantFinish(
      @PullAttribute("tenant.id") String tenantId,
      @PullAttribute("region") String region,
      @PullContextValue("correlationId") String correlationId,
      TelemetryHolder holder) { /* ... */ }
}
```

---

## Programmatic APIs

```java
@Service
@RequiredArgsConstructor
class PaymentService {
  private final TelemetryProcessorSupport telemetry;

  @Step(name = TelemetryNames.STEP_CHECKOUT_CHARGE)
  public void charge(String userId, long amountCents) {
    telemetry.putAttr("user.id", userId);
    telemetry.putAttr("amount.cents", amountCents);
    telemetry.putContext("retry", false);
  }
}
```

---

## Lifecycle Diagram

```
@Flow/@Step entry
  ↓
Save values (via push annotations or programmatic puts)
  ↓
Emit event
  ↓
Dispatcher selects @OnEvent handlers
  ↓
Parameter binding (pull annotations, throwable binding)
  ↓
Attributes persisted; context discarded at scope end
```

---

## OTEL `SpanKind` Reference

| SpanKind   | Use when…                               | Examples                                       |
| ---------- | --------------------------------------- | ---------------------------------------------- |
| `SERVER`   | Handles an **incoming** request/message | HTTP controller, gRPC server, message consumer |
| `CLIENT`   | Makes an **outgoing** request           | HTTP/gRPC client, external API, DB driver      |
| `PRODUCER` | Publishes to a broker/topic/queue       | Kafka/RabbitMQ/SNS send                        |
| `CONSUMER` | Receives/processes a brokered message   | Kafka poll, RabbitMQ listener, SQS handler     |
| `INTERNAL` | Performs in-process work                | Cache recompute, rule evaluation, CPU step     |

---

## Cheat-Sheet: Common `@OnEvent` Patterns

| Pattern                    | Example                                                                          | Effect                          | Binding Example                                 |
| -------------------------- | -------------------------------------------------------------------------------- | ------------------------------- | ----------------------------------------------- |
| Match any event            | `@OnEvent`                                                                       | All events                      | `TelemetryHolder holder`                        |
| Exact name                 | `@OnEvent(name = EVENT_CHECKOUT_PROCESS)`                                        | Specific event                  | `TelemetryHolder holder`                        |
| Prefix match               | `@OnEvent(namePrefix = PREFIX_PAYMENT)`                                          | Events starting with "payment." | `@PullAttribute("payment.id") String id`        |
| Lifecycle only             | `@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})`                                | All finished flows              | `TelemetryHolder holder`                        |
| Lifecycle + exact name     | `@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED}, name = EVENT_CHECKOUT_PROCESS)` | Specific lifecycle & name       | `@PullContextValue("debugMode") Boolean d`      |
| Lifecycle + kind           | `@OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED}, kinds = {SpanKind.SERVER})`     | Finished server spans           | `@PullAllContextValues Map<String,Object> ctx`  |
| Require attributes         | `@OnEvent @RequiredAttributes({"tenant.id","region"})`                           | Must have both attributes       | `@PullAttribute("tenant.id") String tenant`     |
| Require event context      | `@OnEvent @RequiredEventContext({"correlationId"})`                              | Must have context key           | `@PullContextValue("correlationId") String cid` |
| Require throwable          | `@OnEvent(requireThrowable = true)`                                              | Error events only               | `@BindEventThrowable Throwable cause`           |
| Throwable type filter      | `@OnEvent(throwableTypes = {IOException.class}, includeSubclasses = true)`       | Specific throwable types        | `@BindEventThrowable Throwable t`               |
| Batch (root flow finished) | `@OnEvent(lifecycle = {Lifecycle.ROOT_FLOW_FINISHED})`                           | All flows in a root batch       | `List<TelemetryHolder> flows`                   |
| All attributes (read-only) | `@OnEvent @PullAllAttributes`                                                    | Inject all attributes           | `Map<String,Object> attrs`                      |
| All context (read-only)    | `@OnEvent @PullAllContextValues`                                                 | Inject all context              | `Map<String,Object> ctx`                        |

---

If you want, I can now make a **companion “cheat-sheet only” version** of this so engineers can keep it beside their IDE for quick reference. That way, this doc stays comprehensive, and the cheat-sheet stays minimal for daily coding.
