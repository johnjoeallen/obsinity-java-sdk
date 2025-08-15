# Obsinity Telemetry Developer Guide

## Annotation Reference

### Core (structure & selection)

| Annotation               | Target         | Purpose                                                                                           |
| ------------------------ | -------------- | ------------------------------------------------------------------------------------------------- |
| `@Flow`                  | Method / Class | Starts a **flow** (top-level event). Activates scope so nested `@Step` calls become child events. |
| `@Step`                  | Method         | Emits a **step** event inside the active flow; auto‑promoted to a flow if none is active.         |
| `@Kind`                  | Method / Class | Sets OTEL `SpanKind` (`SERVER`, `CLIENT`, `PRODUCER`, `CONSUMER`, `INTERNAL`).                    |
| `@OrphanAlert`           | Method / Class | Controls log level when a `@Step` is auto‑promoted due to no active `@Flow`.                      |
| `@OnEvent`               | Method         | Declares an event handler; filters by lifecycle, name/prefix, kind, throwable filters, **mode**.  |
| `@TelemetryEventHandler` | Class          | Marks a bean that contains `@OnEvent` methods (dispatcher scans only these classes).              |
| `@RequiredAttributes`    | Method         | Handler runs **only if** all listed **attributes** are present.                                   |
| `@RequiredEventContext`  | Method         | Handler runs **only if** all listed **context** keys are present.                                 |
| `@BindEventThrowable`    | Parameter      | Injects the event’s `Throwable` (or cause) if present.                                            |

---

### Push Annotations (Flows/Steps WRITE values)

| Annotation          | Target    | Purpose                                                      |
| ------------------- | --------- | ------------------------------------------------------------ |
| `@PushAttribute`    | Parameter | Saves the parameter value into **attributes** under the key. |
| `@PushContextValue` | Parameter | Saves the parameter value into **context** under the key.    |

---

### Pull Annotations (`@OnEvent` handlers READ values)

| Annotation              | Target    | Purpose                                                                 |
| ----------------------- | --------- | ----------------------------------------------------------------------- |
| `@PullAttribute`        | Parameter | Injects a single **attribute** by key.                                  |
| `@PullAllAttributes`    | Parameter | Injects an **unmodifiable Map** of **all attributes** on the event.     |
| `@PullContextValue`     | Parameter | Injects a single **context** value by key.                              |
| `@PullAllContextValues` | Parameter | Injects an **unmodifiable Map** of **all context** values on the event. |

---

## `@Flow` — Start a top‑level telemetry flow

Starts a **root telemetry flow**. All `@Step` calls made inside it become child events.

```java
@Kind(SpanKind.SERVER)
public class OrderService {
  @Flow(name = "checkout.process")
  public Receipt checkout(@PushAttribute("order.id") String id) { /* ... */ }
}
```

---

## `@Step` — Emit a telemetry step

Records a smaller **unit of work** inside an active flow.
If no flow is active, the step is **auto‑promoted** to a flow.

```java
@Step(name = "checkout.validate")
public void validate(@PushAttribute("order.id") String id) { /* ... */ }
```

---

## `@Kind` — Set span kind

Sets OTEL `SpanKind` for a flow or step.

```java
@Step(name = "checkout.charge")
@Kind(SpanKind.CLIENT)
public void chargeCard(...) { /* ... */ }
```

---

## `@OrphanAlert` — Log on step promotion

Controls log level for auto‑promoted steps.

```java
@Step
@OrphanAlert(level = OrphanAlert.Level.WARN)
public void standaloneStep(...) { /* ... */ }
```

---

## `@OnEvent` — Event handler

Run this method when an event matches the configured filters.

### Name selection (choose one)

* `name` — exact match
* `namePrefix` — startsWith match
  *(If both are set, `name` takes precedence.)*

### Lifecycle & Kind

* `lifecycle` — restrict to `Lifecycle` phases.
* `kinds` — restrict to OTEL `SpanKind`s.

### Exception filters (optional)

* `throwableTypes` — allowed throwable classes
* `includeSubclasses` — include subclasses
* `messageRegex` — regex applied to `Throwable.getMessage()`
* `causeType` — FQCN of expected `Throwable.getCause()` type

### **Dispatch Mode** (mandatory)

| Mode     | Runs on normal events? | Runs on error events? | Parameter rules                                                                                    |
| -------- | ---------------------- | --------------------- | -------------------------------------------------------------------------------------------------- |
| `NORMAL` | ✅                      | ❌                     | Must **not** declare `@BindEventThrowable`.                                                        |
| `ERROR`  | ❌                      | ✅                     | Must declare **exactly one** `@BindEventThrowable` param of type `Throwable`/`Exception`/subclass. |
| `ALWAYS` | ✅                      | ✅                     | May declare **at most one** `@BindEventThrowable` param.                                           |

> **Startup rule (strict):** For each name selector on a handler class (exact or prefix, including the wildcard selector when no name/prefix is declared), if **any** handler exists, there must be at least **one** `mode = ERROR` handler for that selector that binds `Throwable` or `Exception`.
> The simplest way to satisfy this is to provide a **catch‑all**:
>
> ```java
> @OnEvent(mode = DispatchMode.ERROR)
> public void onAnyError(@BindEventThrowable Exception ex, TelemetryHolder holder) { }
> ```

### Batch dispatch

If `lifecycle = ROOT_FLOW_FINISHED` and a handler parameter is `List<TelemetryHolder>`, all flows belonging to the same root are provided as a batch.

---

## Naming via Constants (recommended)

```java
public final class TelemetryNames {
  private TelemetryNames() {}
  public static final String EVENT_CHECKOUT_PROCESS = "checkout.process";
  public static final String STEP_CHECKOUT_VALIDATE = "checkout.validate";
  public static final String STEP_CHECKOUT_CHARGE   = "checkout.charge";
  public static final String PREFIX_PAYMENT         = "payment.";
}
```

---

## Flows & Steps → **Push** values (WRITE)

### `@Flow`

```java
@Kind(SpanKind.SERVER)
public class OrderService {

  @Flow(name = TelemetryNames.EVENT_CHECKOUT_PROCESS)
  public Receipt checkout(
      @PushAttribute("order.id") String orderId,
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

```java
public class OrderService {

  @Step(name = TelemetryNames.STEP_CHECKOUT_VALIDATE)
  public void validate(@PushAttribute("order.id") String orderId,
                       @PushAttribute("order.total") BigDecimal total) { }

  @Step(name = TelemetryNames.STEP_CHECKOUT_CHARGE)
  @Kind(SpanKind.CLIENT)
  public void charge(@PushAttribute("order.id") String orderId,
                     @PushAttribute("payment.method") String method,
                     @PushContextValue("retry") boolean retry) { }
}
```

---

## `@OnEvent` Handlers → **Pull** values (READ) **with catch‑all error coverage**

```java
@TelemetryEventHandler
@Component
public class CheckoutEventHandlers {

  // Runs after checkout flow finishes (success or failure) — auditing
  @OnEvent(name = TelemetryNames.EVENT_CHECKOUT_PROCESS, lifecycle = {Lifecycle.FLOW_FINISHED}, mode = DispatchMode.ALWAYS)
  public void audit(TelemetryHolder holder, @BindEventThrowable Throwable t) {
    auditService.record(holder, t);
  }

  // Success‑only path
  @OnEvent(name = TelemetryNames.EVENT_CHECKOUT_PROCESS, lifecycle = {Lifecycle.FLOW_FINISHED}, mode = DispatchMode.NORMAL)
  public void finished(@PullAttribute("order.id") String orderId) {
    log.info("Order {} completed", orderId);
  }

  // **Required catch‑all for this selector** (keeps startup happy)
  @OnEvent(name = TelemetryNames.EVENT_CHECKOUT_PROCESS, lifecycle = {Lifecycle.FLOW_FINISHED}, mode = DispatchMode.ERROR)
  public void onAnyError(@BindEventThrowable Exception ex, TelemetryHolder holder) {
    log.error("Checkout failed: {}", holder.name(), ex);
  }
}
```

---

## Prefix family example **with catch‑all**

```java
@TelemetryEventHandler
@Component
public class PaymentHandlers {

  @OnEvent(namePrefix = TelemetryNames.PREFIX_PAYMENT, lifecycle = {Lifecycle.FLOW_FINISHED})
  public void onAnyPaymentFinish(TelemetryHolder holder) { /* ... */ }

  // **Catch‑all for the prefix selector**
  @OnEvent(namePrefix = TelemetryNames.PREFIX_PAYMENT, lifecycle = {Lifecycle.FLOW_FINISHED}, mode = DispatchMode.ERROR)
  public void onAnyPaymentError(@BindEventThrowable Exception ex, TelemetryHolder holder) {
    log.warn("Payment error in {}", holder.name(), ex);
  }
}
```

---

## Batch (root flow finished) **with catch‑all**

```java
@TelemetryEventHandler
@Component
public class RootBatchHandlers {

  @OnEvent(lifecycle = {Lifecycle.ROOT_FLOW_FINISHED})
  public void onRootDone(List<TelemetryHolder> flows) { /* batch */ }

  // **Catch‑all for wildcard selector on this class**
  @OnEvent(mode = DispatchMode.ERROR)
  public void onAnyError(@BindEventThrowable Exception ex, TelemetryHolder holder) {
    log.error("Root batch error", ex);
  }
}
```

---

## Global default logging handler **with catch‑all**

```java
@TelemetryEventHandler
@Component
public class LoggingTelemetryEventHandler {

  private final ObjectMapper mapper;

  public LoggingTelemetryEventHandler(ObjectMapper mapper) {
    this.mapper = (mapper != null ? mapper.copy() : new ObjectMapper())
        .enable(SerializationFeature.INDENT_OUTPUT);
  }

  // **Startup-safe catch-all**
  @OnEvent(mode = DispatchMode.ERROR)
  public void onAnyError(@BindEventThrowable Exception ex, TelemetryHolder holder) {
    log.warn("Telemetry ERROR event name={} traceId={} spanId={} ex={}",
      holder.name(), holder.traceId(), holder.spanId(), ex.toString());
  }

  @OnEvent(lifecycle = {Lifecycle.FLOW_STARTED})
  public void onFlowStarted(TelemetryHolder h) {
    log.info("flow-start name={} kind={} traceId={}", h.name(), h.kind(), h.traceId());
    if (log.isDebugEnabled()) log.debug("payload:\n{}", toJson(h));
  }

  @OnEvent(lifecycle = {Lifecycle.FLOW_FINISHED})
  public void onFlowFinished(TelemetryHolder h) {
    log.info("flow-finish name={} kind={} traceId={} events={}",
      h.name(), h.kind(), h.traceId(), (h.events() == null ? 0 : h.events().size()));
    if (log.isDebugEnabled()) log.debug("payload:\n{}", toJson(h));
  }

  private String toJson(TelemetryHolder holder) {
    try { return mapper.writeValueAsString(holder); }
    catch (JsonProcessingException e) { return String.valueOf(holder); }
  }
}
```

---

## Additional End‑to‑End Examples (each includes a catch‑all)

### Minimal Flow + Handlers

```java
@Service
public class MinimalFlowService {
  @Flow(name = "ops.minimal")
  public void run() { }
}

@TelemetryEventHandler
@Component
public class MinimalFlowHandlers {

  @OnEvent(name = "ops.minimal", lifecycle = {Lifecycle.FLOW_STARTED})
  public void onStart() { }

  @OnEvent(name = "ops.minimal", lifecycle = {Lifecycle.FLOW_FINISHED})
  public void onFinish() { }

  // catch‑all for this selector
  @OnEvent(name = "ops.minimal", mode = DispatchMode.ERROR)
  public void onAnyError(@BindEventThrowable Exception ex, TelemetryHolder holder) { }
}
```

### Flow with a Single Step

```java
@Service
public class FlowWithStepService {
  @Flow(name = "checkout.flow") public void checkout() { validateCart(); }
  @Step(name = "checkout.validate") void validateCart() { }
}

@TelemetryEventHandler
@Component
public class FlowWithStepHandlers {

  @OnEvent(name = "checkout.flow", lifecycle = {Lifecycle.FLOW_FINISHED})
  public void onFlowFinished() { }

  @OnEvent(name = "checkout.validate", lifecycle = {Lifecycle.FLOW_FINISHED})
  public void onStepFinished() { }

  // catch‑alls for both selectors
  @OnEvent(name = "checkout.flow", mode = DispatchMode.ERROR)
  public void onFlowError(@BindEventThrowable Exception ex, TelemetryHolder holder) { }

  @OnEvent(name = "checkout.validate", mode = DispatchMode.ERROR)
  public void onStepError(@BindEventThrowable Exception ex, TelemetryHolder holder) { }
}
```

### Persisted Attributes: Push in Producer, Pull in Handler

```java
@Service
public class CreateUserService {
  @Flow(name = "users.create")
  public void create(@PushAttribute(name = "user.id") String userId,
                     @PushAttribute(name = "user.role") String role) { }
}

@TelemetryEventHandler
@Component
public class CreateUserHandlers {
  @OnEvent(name = "users.create", lifecycle = {Lifecycle.FLOW_FINISHED})
  public void onCreated(@PullAttribute("user.id") String id,
                        @PullAttribute("user.role") String role) { }

  // catch‑all
  @OnEvent(name = "users.create", mode = DispatchMode.ERROR)
  public void onCreateError(@BindEventThrowable Exception ex, TelemetryHolder holder) { }
}
```

### Error Handling with `@BindEventThrowable`

```java
@Service
public class ErrorProneService {
  @Flow(name = "service.error")
  public void doWork() { throw new RuntimeException("fail"); }
}

@TelemetryEventHandler
@Component
public class ErrorHandlers {

  // error‑only, typed filter
  @OnEvent(name = "service.error", lifecycle = {Lifecycle.FLOW_FINISHED},
           mode = DispatchMode.ERROR, throwableTypes = {RuntimeException.class})
  public void onRuntime(@BindEventThrowable RuntimeException ex, TelemetryHolder holder) { }

  // catch‑all (also satisfies the selector's requirement)
  @OnEvent(name = "service.error", mode = DispatchMode.ERROR)
  public void onAnyError(@BindEventThrowable Exception ex, TelemetryHolder holder) { }
}
```

### Prefix Matching for a Family of Steps

```java
@Service
public class FulfilmentService {
  @Flow(name = "fulfilment.flow") public void fulfilOrder() { pick(); pack(); ship(); }
  @Step(name = "fulfilment.pick.items") void pick() { }
  @Step(name = "fulfilment.pack.box")  void pack() { }
  @Step(name = "fulfilment.ship.parcel") void ship() { }
}

@TelemetryEventHandler
@Component
public class FulfilmentHandlers {
  @OnEvent(namePrefix = "fulfilment.pick.", lifecycle = {Lifecycle.FLOW_FINISHED})
  public void onAnyPick() { }
  @OnEvent(namePrefix = "fulfilment.pack.", lifecycle = {Lifecycle.FLOW_FINISHED})
  public void onAnyPack() { }
  @OnEvent(namePrefix = "fulfilment.ship.", lifecycle = {Lifecycle.FLOW_FINISHED})
  public void onAnyShip() { }

  // single catch‑all for the wildcard selector in this class
  @OnEvent(mode = DispatchMode.ERROR)
  public void onAnyError(@BindEventThrowable Exception ex, TelemetryHolder holder) { }
}
```

---

## Programmatic APIs (write attributes/context without annotations)

```java
@Service
@RequiredArgsConstructor
class PaymentService {
  private final TelemetryProcessorSupport telemetry;

  @Step(name = "payment.charge")
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
Save values (push annotations or telemetry.put*)
  ↓
Emit event
  ↓
Dispatcher selects @OnEvent by: name → lifecycle → kind → throwable filters → mode
  ↓
Parameter binding (pull annotations, Throwable injection)
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

## Quick Rules to Avoid Startup Failures

1. **Pick a `mode`** for every `@OnEvent`: `NORMAL`, `ERROR`, or `ALWAYS`.
2. If a handler class declares any `@OnEvent` for a selector (an exact `name`, a `namePrefix`, or the implicit wildcard when neither is set), ensure there is **at least one**:

   ```java
   @OnEvent(mode = DispatchMode.ERROR)
   public void onAnyError(@BindEventThrowable Exception ex, TelemetryHolder holder) { }
   ```
3. In `ERROR` mode:

    * Declare **exactly one** `@BindEventThrowable`.
    * Parameter type must be `Throwable`, `Exception`, or a subclass.
4. In `NORMAL` mode:

    * **Do not** declare `@BindEventThrowable`.
5. In `ALWAYS` mode:

    * At most one `@BindEventThrowable` is allowed (optional).

---
