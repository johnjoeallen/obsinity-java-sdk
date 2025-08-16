# Obsinity Telemetry Developer Guide

## Annotation Reference

### Core (structure & selection)

| Annotation               | Target         | Purpose                                                                                                                                                         |
| ------------------------ | -------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `@Flow`                  | Method / Class | Starts a **flow** (top-level event). Activates scope so nested `@Step` calls become child events.                                                               |
| `@Step`                  | Method         | Emits a **step** event inside the active flow; auto-promoted to a flow if none is active.                                                                       |
| `@Kind`                  | Method / Class | Sets OTEL `SpanKind` (`SERVER`, `CLIENT`, `PRODUCER`, `CONSUMER`, `INTERNAL`).                                                                                  |
| `@OrphanAlert`           | Method / Class | Controls log level when a `@Step` is auto-promoted due to no active `@Flow`.                                                                                    |
| `@OnEvent`               | Method         | Declares an event handler; filters by **lifecycle**, **name**, **kind**, throwable filters, **mode**. Matching is **exact** with dot-chop fallback (see below). |
| `@TelemetryEventHandler` | Class          | Marks a bean that contains `@OnEvent` methods. **Handlers are grouped per bean** at runtime.                                                                    |
| `@RequiredAttributes`    | Method         | Handler runs **only if** all listed **attributes** are present.                                                                                                 |
| `@RequiredEventContext`  | Method         | Handler runs **only if** all listed **context** keys are present.                                                                                               |
| `@BindEventThrowable`    | Parameter      | Injects the event’s `Throwable` (or cause) if present.                                                                                                          |

---

### Push Annotations (Flows/Steps WRITE values)

| Annotation          | Target    | Purpose                                                      |
| ------------------- | --------- | ------------------------------------------------------------ |
| `@PushAttribute`    | Parameter | Saves the parameter value into **attributes** under the key. |
| `@PushContextValue` | Parameter | Saves the parameter value into **context** under the key.    |

---

### Pull Annotations (`@OnEvent` handlers READ values)

> **Note:** Pull annotations **require `name = "..."`**. The `value` alias is not supported for pulls.

| Annotation                 | Target    | Purpose                                                                 |
| -------------------------- | --------- | ----------------------------------------------------------------------- |
| `@PullAttribute(name=)`    | Parameter | Injects a single **attribute** by key.                                  |
| `@PullAllAttributes`       | Parameter | Injects an **unmodifiable Map** of **all attributes** on the event.     |
| `@PullContextValue(name=)` | Parameter | Injects a single **context** value by key.                              |
| `@PullAllContextValues`    | Parameter | Injects an **unmodifiable Map** of **all context** values on the event. |

---

## `@Flow` — Start a top-level telemetry flow

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
If no flow is active, the step is **auto-promoted** to a flow.

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

Controls log level for auto-promoted steps.

```java
@Step
@OrphanAlert(level = OrphanAlert.Level.WARN)
public void standaloneStep(...) { /* ... */ }
```

---

## `@OnEvent` — Event handler

Run this method when an event matches the configured filters.

### Name selection & fallback

* `name` — **exact** match for a logical event key (e.g., `"orders.create"`).
* If no exact match is found, the dispatcher **dot-chops** the emitted name until it finds a bucket:
  `a.b.c` → `a.b` → `a` → `""` (blank).
* **You must define a blank selector (`name = ""`) for every lifecycle your handler class participates in.**
  Startup will fail if a lifecycle is used without a corresponding blank selector in that class.
* `namePrefix` is **not supported**.

### Lifecycle & Kind

* `lifecycle` — one or more `Lifecycle` phases.
* `kinds` — optional filter for OTEL `SpanKind`s.

### Exception filters (optional)

* `throwableTypes` — allowed throwable classes.
* `includeSubclasses` — include subclasses for matching.
* `messageRegex` — regex on `Throwable.getMessage()`.
* `causeType` — FQCN of expected `Throwable.getCause()` type.

### **Dispatch Mode** (mandatory)

| Mode     | Runs on normal events? | Runs on error events? | Parameter rules                                                                                    |
| -------- | ---------------------- | --------------------- | -------------------------------------------------------------------------------------------------- |
| `NORMAL` | ✅                      | ❌                     | Must **not** declare `@BindEventThrowable`.                                                        |
| `ERROR`  | ❌                      | ✅                     | Must declare **exactly one** `@BindEventThrowable` param of type `Throwable`/`Exception`/subclass. |
| `ALWAYS` | ✅                      | ✅                     | May declare **at most one** `@BindEventThrowable` param.                                           |

**Execution order**

* **No exception:** run all matching **NORMAL**, then all matching **ALWAYS**.
* **With exception:** run the single **best ERROR** (see below), then all matching **ALWAYS**.
  **NORMAL is suppressed** on error.

**Choosing the “best” `ERROR` handler**

1. Prefer handlers with matching `throwableTypes`; pick the one with the **shortest class-distance** to the actual exception.
2. If none declare types, use the first **catch-all** `ERROR`.

### Duplicate rules (per handler class)

* Duplicate = same **lifecycle + name + mode + method signature** → **startup fails**.
* Different parameters (different method signatures) are **not** duplicates.
* **Special rule:** Only **one** **blank** (`name = ""`) `ERROR` handler is allowed per class (even if signatures differ). A second one **fails**.

### Batch dispatch

If `lifecycle = ROOT_FLOW_FINISHED` and a handler parameter is `List<TelemetryHolder>`, the dispatcher provides a batch of holders for the root flow. Only **batch-capable** handlers are invoked.

---

## Naming via Constants (recommended)

```java
public final class TelemetryNames {
  private TelemetryNames() {}
  public static final String EVENT_CHECKOUT_PROCESS = "checkout.process";
  public static final String STEP_CHECKOUT_VALIDATE = "checkout.validate";
  public static final String STEP_CHECKOUT_CHARGE   = "checkout.charge";
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

## `@OnEvent` Handlers → **Pull** values (READ) (+ catch-all recommendation)

```java
@TelemetryEventHandler
@Component
public class CheckoutEventHandlers {

  // Runs after checkout flow finishes (success or failure)
  @OnEvent(name = TelemetryNames.EVENT_CHECKOUT_PROCESS, lifecycle = {Lifecycle.FLOW_FINISHED}, mode = DispatchMode.ALWAYS)
  public void audit(TelemetryHolder holder, @BindEventThrowable Throwable t) {
    auditService.record(holder, t);
  }

  // Success-only path
  @OnEvent(name = TelemetryNames.EVENT_CHECKOUT_PROCESS, lifecycle = {Lifecycle.FLOW_FINISHED}, mode = DispatchMode.NORMAL)
  public void finished(@PullAttribute(name = "order.id") String orderId) {
    log.info("Order {} completed", orderId);
  }

  // Recommended catch-all for this selector
  @OnEvent(name = TelemetryNames.EVENT_CHECKOUT_PROCESS, lifecycle = {Lifecycle.FLOW_FINISHED}, mode = DispatchMode.ERROR)
  public void onAnyError(@BindEventThrowable Exception ex, TelemetryHolder holder) {
    log.error("Checkout failed: {}", holder.name(), ex);
  }
}
```

---

## Family matching via **dot-chop** (replace `namePrefix`)

To handle a family like `payment.authorize`, `payment.capture`, `payment.refund`, declare a handler on the **parent key**, e.g. `payment`. Events named `payment.xyz` will fall back to `payment` if no exact handler exists.

```java
@TelemetryEventHandler
@Component
public class PaymentHandlers {

  // Catches payment.* finishes via dot-chop fallback
  @OnEvent(name = "payment", lifecycle = {Lifecycle.FLOW_FINISHED})
  public void onAnyPaymentFinish(TelemetryHolder holder) { /* ... */ }

  // Optional specific
  @OnEvent(name = "payment.refund", lifecycle = {Lifecycle.FLOW_FINISHED})
  public void onRefund(TelemetryHolder holder) { }

  // Single class-wide catch-all ERROR (blank selector shown below)
  @OnEvent(mode = DispatchMode.ERROR)
  public void onAnyError(@BindEventThrowable Exception ex, TelemetryHolder holder) { }
}
```

---

## Batch (root flow finished)

```java
@TelemetryEventHandler
@Component
public class RootBatchHandlers {

  @OnEvent(lifecycle = {Lifecycle.ROOT_FLOW_FINISHED})
  public void onRootDone(List<TelemetryHolder> flows) { /* batch */ }

  // Single blank ERROR is permitted per class (recommended)
  @OnEvent(mode = DispatchMode.ERROR)
  public void onAnyError(@BindEventThrowable Exception ex, TelemetryHolder holder) {
    log.error("Root batch error", ex);
  }
}
```

---

## Global default logging handler

```java
@TelemetryEventHandler
@Component
public class LoggingTelemetryEventHandler {

  @OnEvent(mode = DispatchMode.ERROR)
  public void onAnyError(@BindEventThrowable Exception ex, TelemetryHolder holder) {
    log.warn("Telemetry ERROR event name={} traceId={} spanId={} ex={}",
      holder.name(), holder.traceId(), holder.spanId(), ex.toString());
  }

  // Required blank selectors for lifecycles this class participates in
  @OnEvent(name = "", lifecycle = {Lifecycle.FLOW_STARTED})
  public void onAnyFlowStarted(TelemetryHolder h) {
    log.info("flow-start name={} kind={} traceId={}", h.name(), h.kind(), h.traceId());
  }

  @OnEvent(name = "", lifecycle = {Lifecycle.FLOW_FINISHED})
  public void onAnyFlowFinished(TelemetryHolder h) {
    log.info("flow-finish name={} kind={} traceId={} events={}",
      h.name(), h.kind(), h.traceId(), (h.events() == null ? 0 : h.events().size()));
  }
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
Dispatcher (per-bean): lifecycle → name (exact or dot-chop) → mode
  ↓
NORMAL path: NORMAL → ALWAYS
ERROR path: best ERROR → ALWAYS
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
2. For **each lifecycle** used by a handler class, declare a **blank selector** handler (`name = ""`).
   This enables dot-chop fallback (e.g., `a.b.c` → `a.b` → `a` → `""`).
3. **Only one** blank `ERROR` handler is allowed **per class** (even with different params).
4. Duplicates (same lifecycle + name + mode + method **signature**) are **not allowed**.
5. `ERROR` mode: declare **exactly one** `@BindEventThrowable` of a `Throwable` subtype.
6. `NORMAL` mode: **do not** declare `@BindEventThrowable`.
7. `ALWAYS` mode: at most **one** `@BindEventThrowable`.
8. For pulls, use `@PullAttribute(name="...")` and `@PullContextValue(name="...")`.

---
