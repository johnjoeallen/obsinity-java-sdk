# Obsinity Telemetry Developer Guide

*(Updated for August 2025 ruleset)*

---

## Annotation Reference

### Core (structure & selection)

| Annotation           | Target         | Purpose                                                                                              |
| -------------------- | -------------- | ---------------------------------------------------------------------------------------------------- |
| `@Flow`              | Method         | Starts a **flow**. May be root or nested. Activates context so nested `@Step` calls become children. |
| `@Step`              | Method         | Emits a **step** inside the active flow; auto-promoted to a flow if none is active.                  |
| `@Kind`              | Method / Class | Sets OTEL `SpanKind` (`SERVER`, `CLIENT`, `PRODUCER`, `CONSUMER`, `INTERNAL`).                       |
| `@OrphanAlert`       | Method / Class | Controls log level when a `@Step` is auto-promoted because no active `@Flow` exists.                 |
| `@OnFlowLifecycle`  | **Class**      | Component-level filter restricting **visible lifecycles** (e.g., `ROOT_FLOW_FINISHED`). Repeatable.  |
| `@OnFlowLifecycles` | **Class**      | Container for multiple `@OnFlowLifecycle` entries.                                                  |
| `@OnAllLifecycles`   | **Class**      | Shorthand: make the receiver visible for **all phases**.                                             |
| `@OnEventScope`      | Method / Class | Declares name/prefix/kind filters (**scope**) for matching events. Repeatable.                                     |

### Flow receivers (flow-centric handlers)

| Annotation            | Target | Purpose                                                                     |
| --------------------- | ------ | --------------------------------------------------------------------------- |
| `@EventReceiver`      | Class  | Marks a bean containing flow/step event handlers.                           |
| `@OnFlowStarted`      | Method | Handle when a flow **starts** (exact name or prefix; alias `value`/`name`). |
| `@OnFlowCompleted`    | Method | Handle when a matched flow **finishes** (success or failure).               |
| `@OnFlowSuccess`      | Method | Handle only when a matched flow **succeeds** (non-root).                    |
| `@OnFlowFailure`      | Method | Handle only when a matched flow **fails** (non-root).                       |
| `@OnFlowNotMatched`   | Method | Component-scoped fallback when **no** `@OnFlow*` in this receiver matched.  |
| `@GlobalFlowFallback` | Class  | Global fallback receiver when **no receiver in the app** matched a flow.    |

### Outcome filtering

| Type / Annotation | Target | Purpose                                             |
| ----------------- | ------ |-----------------------------------------------------|
| `enum Outcome`    | —      | `SUCCESS`, `FAILURE`.                               |
| `@OnOutcome`      | Method | Restrict a handler to a single outcome. Repeatable. |

### Attribute & context I/O (producer-side “push”)

| Annotation          | Target    | Purpose                                                                                                        |
| ------------------- | --------- | -------------------------------------------------------------------------------------------------------------- |
| `@PushAttribute`    | Parameter | Save a method parameter value into **attributes** (saved on the event). Supports `value`/`name`, `omitIfNull`. |
| `@PushContextValue` | Parameter | Save a method parameter value into **event context** (ephemeral).                                              |

### Parameter binding (consumer-side “pull”)

| Annotation              | Target    | Purpose                                                                 |
| ----------------------- | --------- | ----------------------------------------------------------------------- |
| `@PullAttribute`        | Parameter | Bind a single attribute key to the parameter (supports `value`/`name`). |
| `@PullAllAttributes`    | Parameter | Bind the **entire attributes map** to the parameter.                    |
| `@PullContextValue`     | Parameter | Bind a single event context key to the parameter.                       |
| `@PullAllContextValues` | Parameter | Bind the **entire event context map** to the parameter.                 |
| `@BindEventThrowable`   | Parameter | Bind the event’s `Throwable` (when present) to the parameter.           |

### Preconditions (handler gating)

| Annotation              | Target | Purpose                                                                            |
| ----------------------- | ------ | ---------------------------------------------------------------------------------- |
| `@RequiredAttributes`   | Method | Require one or more attribute keys be present **before** invoking the handler.     |
| `@RequiredEventContext` | Method | Require one or more event context keys be present **before** invoking the handler. |

---

## Selection & Matching (dot-chop, scope, lifecycle, kind)

* **Dot-chop**: `a.b.c` → `a.b` → `a` → `""`.
* **Class lifecycle filters**: `@OnFlowLifecycle` restricts what phases are visible. `@OnAllLifecycles` overrides to allow all.
* **Scope filters**: `@OnEventScope` (class & method).
* **Outcome filters**:

    * `@OnFlowSuccess` = flow success only (not root).
    * `@OnFlowFailure` = flow failure only (not root).
    * `@OnFlowCompleted` = flow finished (root or subflow). May combine with `@OnOutcome`.

---

## Quick Rules

1. **No “COMPLETED” phase**: every flow finish is either SUCCESS or FAILURE.

    * Use `@OnFlowCompleted` for “always”.
    * Use `@OnOutcome` to narrow further.
2. **Root flows**: only `@OnFlowCompleted` can handle them.
3. **Do not duplicate** handlers with identical `(lifecycle visibility + name/prefix + outcome set + signature)`.
4. **Throwable binding**: only on failure handlers.
5. **Failure specificity**: if multiple `@OnFlowFailure` are eligible, **most specific throwable binding wins** (`IllegalArgumentException` > `RuntimeException` > `Exception` > unbound).
6. **Class scopes refine downwards**: class-level + method-level must both pass.
7. **Fallbacks**:

    * `@OnFlowNotMatched` → component-scoped fallback.
    * `@GlobalFlowFallback` → only if **no receiver** matched the flow at all.

---

## Examples

### Flow & Steps

```java
@Flow("checkout.start")                     // Start a checkout flow
@Kind(SpanKind.SERVER)
public void startCheckout(
    @PushAttribute("user.id") String userId,
    @PushContextValue("session.id") String sessionId
) { /* ... */ }

@Step("checkout.reserve")                   // Step within checkout
public void reserveInventory(@PushAttribute("sku") String sku) { /* ... */ }

@Step("checkout.payment")                   // Auto-promoted if no flow is active
@OrphanAlert(OrphanAlert.Level.WARN)
public void processPayment(@PushAttribute("payment.method") String method) { /* ... */ }
```

### Receiver with lifecycle + outcomes

```java
@EventReceiver
@OnEventScope(prefix = "checkout")
@OnFlowLifecycle(ROOT_FLOW_FINISHED)
public class CheckoutReceiver {

  @OnFlowStarted("checkout.start")
  public void onStart(@PullAttribute("user.id") String userId) { /* ... */ }

  @OnFlowCompleted("checkout")
  @OnOutcome(Outcome.SUCCESS)
  public void onSuccess(@PullAllAttributes Map<String,Object> attrs) { /* ... */ }

  @OnFlowCompleted("checkout")
  @OnOutcome(Outcome.FAILURE)
  public void onFailure(@BindEventThrowable Throwable ex,
                        @PullAllAttributes Map<String,Object> attrs) { /* ... */ }
}
```

### Failure Specificity Example

```java
@EventReceiver
public class FailureSpecificReceiver {

  @OnFlowFailure("checkout")
  public void onAnyFailure(@BindEventThrowable Throwable t) { /* fallback */ }

  @OnFlowFailure("checkout")
  public void onRuntime(@BindEventThrowable RuntimeException ex) { /* more specific */ }

  @OnFlowFailure("checkout")
  public void onIllegalArg(@BindEventThrowable IllegalArgumentException ex) { /* most specific */ }
}
```

*If a `new IllegalArgumentException("bad input")` occurs:*
→ Only `onIllegalArg` is invoked.
Dispatcher always prefers the **most specific throwable binding**.

### Global fallback

```java
@GlobalFlowFallback
public class LastResort {
  @OnFlowCompleted("")
  public void onAny(@PullAllAttributes Map<String,Object> attrs) { /* ... */ }
}
```

### Guarded receiver

```java
@EventReceiver
public class GuardedReceiver {
  @OnFlowStarted("billing.charge")
  @RequiredAttributes({"user.id", "amount"})
  public void charge(@PullAttribute("user.id") String uid,
                     @PullAttribute("amount") BigDecimal amount) { /* ... */ }
}
```

### Root batch

```java
@EventReceiver
@OnFlowLifecycle(ROOT_FLOW_FINISHED)
public class RootBatchReceiver {
  @OnFlowCompleted("rootFlow")
  public void onRoot(List<TelemetryHolder> flows) { /* batch */ }
}
```

### Programmatic API

```java
@Service
@RequiredArgsConstructor
class PaymentService {
  private final TelemetryProcessorSupport telemetry;

  @Step("payment.charge")
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
Dispatcher: lifecycle filter → scope → name → outcome
  ↓
Flow Started:
   └── @OnFlowStarted
  ↓
Flow Finished:
   ├── SUCCESS
   │     ├─ @OnFlowSuccess (non-root)
   │     └─ @OnFlowCompleted(@OnOutcome=SUCCESS or none)
   │
   └── FAILURE
         ├─ @OnFlowFailure (non-root, most-specific throwable binding wins)
         └─ @OnFlowCompleted(@OnOutcome=FAILURE or none)
  ↓
“Always” = @OnFlowCompleted without @OnOutcome
  ↓
Parameter binding
  ↓
Attributes saved; context discarded at scope end
```

---

## Dispatcher View

```
Event arrives →
   Lifecycle filter (@OnFlowLifecycle / @OnAllLifecycles)
   → Scope filter (@OnEventScope)
   → Name match (dot-chop, blank terminator)
   → Outcome path:
        SUCCESS →
            @OnFlowSuccess + eligible @OnFlowCompleted
        FAILURE →
            choose most-specific @OnFlowFailure
            + eligible @OnFlowCompleted
   → If no match:
        @OnFlowNotMatched / @GlobalFlowFallback
```
## OTEL `SpanKind` Diagram

```
SERVER  →  CLIENT
   ↓         ↓
CONSUMER    PRODUCER
        ↓
    INTERNAL
```

| SpanKind   | Use when…                               | Examples                                       |
| ---------- | --------------------------------------- | ---------------------------------------------- |
| `SERVER`   | Handles an **incoming** request/message | HTTP controller, gRPC server, message consumer |
| `CLIENT`   | Makes an **outgoing** request           | HTTP/gRPC client, external API, DB driver      |
| `PRODUCER` | Publishes to a broker/topic/queue       | Kafka/RabbitMQ/SNS send                        |
| `CONSUMER` | Receives/processes a brokered message   | Kafka poll, RabbitMQ listener, SQS handler     |
| `INTERNAL` | Performs in-process work                | Cache recompute, rule evaluation, CPU step     |

---

## Notes & Terminology

* **Attributes** = saved key/values on the event (persist).
* **Event context** = ephemeral values, not persisted.
* **Dot-chop** = right-to-left fallback.
* **Receiver scope** = class-level filter + method filter.
* **Outcome** replaces old “mode” concept.

---

## Troubleshooting

* **Handler never fires** → Check lifecycle, scope, name, outcome, blank selector.
* **Null parameters** → Verify key names, use `@PullAll*` to debug.
* **Duplicates** → Only one handler per `(scope + lifecycle + outcome + signature)`.
* **Throwable missing** → Only present in failure events.

---
