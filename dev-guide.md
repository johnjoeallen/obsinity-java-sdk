# Obsinity Telemetry Developer Guide

## Annotation Reference

### Core (structure & selection)

| Annotation           | Target         | Purpose                                                                                             |
| -------------------- | -------------- | --------------------------------------------------------------------------------------------------- |
| `@Flow`              | Method         | Starts a **flow** (root execution). Activates context so nested `@Step` calls become child events.  |
| `@Step`              | Method         | Emits a **step** inside the active flow; auto‑promoted to a flow if none is active.                 |
| `@Kind`              | Method / Class | Sets OTEL `SpanKind` (`SERVER`, `CLIENT`, `PRODUCER`, `CONSUMER`, `INTERNAL`).                      |
| `@OrphanAlert`       | Method / Class | Controls log level when a `@Step` is auto‑promoted because no active `@Flow` exists.                |
| `@OnEventLifecycle`  | **Class**      | Component‑level filter restricting **visible lifecycles** (e.g., `ROOT_FLOW_FINISHED`). Repeatable. |
| `@OnEventLifecycles` | **Class**      | Container for multiple `@OnEventLifecycle` entries.                                                 |
| `@OnEventScope`      | Method / Class | Declares name/prefix/kind filters (**scope**) for matching events.                                  |
| `@OnEventScopes`     | Method / Class | Container for multiple `@OnEventScope` entries.                                                     |

### Flow receivers (flow‑centric handlers)

| Annotation            | Target | Purpose                                                                     |
| --------------------- | ------ | --------------------------------------------------------------------------- |
| `@EventReceiver`      | Class  | Marks a bean containing flow/step event handlers.                           |
| `@OnFlowStarted`      | Method | Handle when a flow **starts** (exact name or prefix; alias `value`/`name`). |
| `@OnFlowCompleted`    | Method | Handle when a matched flow **finishes** (success or failure).               |
| `@OnFlowSuccess`      | Method | Handle only when a matched flow **succeeds**.                               |
| `@OnFlowFailure`      | Method | Handle only when a matched flow **fails**; can bind the throwable.          |
| `@OnFlowNotMatched`   | Method | Component‑scoped fallback when **no** `@OnFlow*` in this receiver matched.  |
| `@GlobalFlowFallback` | Class  | Global fallback receiver when **no receiver in the app** matched a flow.    |

### Outcome filtering

| Type / Annotation | Target | Purpose                                      |
| ----------------- | ------ | -------------------------------------------- |
| `enum Outcome`    | —      | `SUCCESS`, `FAILURE`.                        |
| `@OnOutcome`      | Method | Restrict a handler to a single outcome.      |
| `@OnOutcomes`     | Method | Container for multiple `@OnOutcome` entries. |

### Attribute & context I/O (producer‑side “push”)

| Annotation          | Target    | Purpose                                                                                                        |
| ------------------- | --------- | -------------------------------------------------------------------------------------------------------------- |
| `@PushAttribute`    | Parameter | Save a method parameter value into **attributes** (saved on the event). Supports `value`/`name`, `omitIfNull`. |
| `@PushContextValue` | Parameter | Save a method parameter value into **event context** (ephemeral).                                              |

### Parameter binding (consumer‑side “pull”)

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

## Selection & Matching (dot‑chop, scope, lifecycle, kind)

**Name matching (dot‑chop):** handlers declared with a **prefix** match deeper names by chopping from the right:
`a.b.c` → `a.b` → `a` → `""`.
Declare a **blank** selector (`name=""`) to terminate the chain cleanly when using prefixes.

**Scope filters (`@OnEventScope`):**

* `prefix` / alias `value`/`name`: event name prefix or exact name.
* `kind`: one or more `SpanKind` values; if set, only those kinds match.
* May be placed on the **class** to scope all methods, and/or on individual methods for narrower filters.

**Lifecycle filters (class level):** annotate the receiver class with `@OnEventLifecycle(LIFECYCLE)` (or `@OnEventLifecycles`) to constrain which lifecycles are even visible. Handlers inside will never see events outside this set (e.g., a receiver only for `ROOT_FLOW_FINISHED`).

**Outcome filters:** use `@OnOutcome(Outcome.SUCCESS)` / `FAILURE` (or the flow‑specific shorthands `@OnFlowSuccess` / `@OnFlowFailure` / `@OnFlowCompleted`).

---

## Handler Parameter Binding Cheat‑Sheet

* Attributes: `@PullAttribute("user.id") String userId`, `@PullAllAttributes Map<String,Object> attrs`
* Context: `@PullContextValue("session") Session s`, `@PullAllContextValues Map<String,Object> ctx`
* Throwable (failures): `@BindEventThrowable IllegalStateException ex`
* Kind/lifecycle aren’t parameters; set with `@Kind` (method/class), and `@OnEventLifecycle` on the **class**.
* Producer push (from application code): `@PushAttribute("order.id") String id`, `@PushContextValue("tenant") String t`

---

## Quick Rules to Avoid Startup Failures (updated)

1. **Declare outcomes explicitly** on handlers that care about result: use `@OnOutcome`, or the shorthands `@OnFlowSuccess` / `@OnFlowFailure` / `@OnFlowCompleted` for flow receivers.
2. For **each lifecycle** allowed on a receiver class, include a **blank selector** (`name = ""`) if you rely on dot‑chop fallback.
3. **Do not duplicate** handlers with the **same** (lifecycle visibility via class + name/prefix + outcome set + method signature).
4. If a handler expects a failure, declare exactly one `@BindEventThrowable` parameter typed to a `Throwable` (or subtype). Success‑only handlers should not declare it.
5. Use `@RequiredAttributes` / `@RequiredEventContext` for gating — missing keys prevent invocation.
6. When scoping at **class level** via `@OnEventScope`, remember method‑level scopes **refine** (intersect) the class scope.

---

## Extended Examples

### 1) Flow and steps with attributes/context (producer‑side)

```java
@Flow("checkout.start")
@Kind(SpanKind.SERVER)
public void startCheckout(
    @PushAttribute("user.id") String userId,
    @PushContextValue("session.id") String sessionId
) {
    // application code...
}

@Step("checkout.reserve")
public void reserveInventory(@PushAttribute("sku") String sku) { /* ... */ }

@Step("checkout.payment")
@OrphanAlert(OrphanAlert.Level.WARN)
public void processPayment(@PushAttribute("payment.method") String method) { /* ... */ }
```

### 2) Receiver with class‑level lifecycle + scope + outcomes

```java
@EventReceiver
@OnEventScope(prefix = "checkout")            // receiver-wide scope
@OnEventLifecycle(ROOT_FLOW_FINISHED)         // only see finished flows
public class CheckoutReceiver {

  @OnFlowStarted(name = "checkout.start")     // exact name, fires at flow start (if visible)
  public void onStart(@PullAttribute("user.id") String userId) { /* ... */ }

  @OnFlowStarted("checkout")                  // prefix, fires at start for any checkout.* flow
  public void onAnyCheckoutStart(@PullAllAttributes Map<String,Object> attrs) { /* ... */ }

  @OnFlowCompleted("checkout")                // success or failure at completion
  @OnOutcome(Outcome.SUCCESS)
  public void onAnyCheckoutSucceeded(@PullAllAttributes Map<String,Object> attrs) { /* ... */ }

  @OnFlowCompleted("checkout")
  @OnOutcome(Outcome.FAILURE)
  public void onAnyCheckoutFailed(@BindEventThrowable Throwable ex,
                                  @PullAllAttributes Map<String,Object> attrs) { /* ... */ }

  @OnFlowNotMatched
  public void nothingMatchedHere(String flowName) { /* optional impl-specific signature */ }
}
```

### 3) Global fallback receiver

```java
@GlobalFlowFallback
public class LastResortReceiver {

  @OnFlowCompleted("") // blank selector as terminator
  public void onAnyCompletion(@PullAllAttributes Map<String,Object> attrs) { /* ... */ }
}
```

### 4) Preconditions

```java
@EventReceiver
public class GuardedReceiver {

  @OnFlowStarted("billing.charge")
  @RequiredAttributes({"user.id", "amount"})
  public void charge(@PullAttribute("user.id") String uid,
                     @PullAttribute("amount") BigDecimal amount) { /* ... */ }

  @OnEventLifecycle(ROOT_FLOW_FINISHED) // class-level on the receiver (or move receiver-wide)
  public static class ChargeOutcomes {

    @OnFlowCompleted("billing.charge")
    @RequiredEventContext({"session.id"})
    @OnOutcome(Outcome.FAILURE)
    public void chargeFailed(@BindEventThrowable RuntimeException ex,
                             @PullContextValue("session.id") String session) { /* ... */ }
  }
}
```

### 5) Batch (root flow finished)

```java
@EventReceiver
@OnEventLifecycle(ROOT_FLOW_FINISHED)
public class RootBatchReceiver {

  @OnFlowCompleted("rootFlow")
  public void onRootDone(List<TelemetryHolder> flows) { /* batch */ }

  @OnFlowCompleted("rootFlow")
  @OnOutcome(Outcome.FAILURE)
  public void onAnyRootFailure(@BindEventThrowable Exception ex,
                               TelemetryHolder holder) { /* ... */ }
}
```

### 6) Programmatic API (push without annotations)

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
Dispatcher: class lifecycle filter → scope (class+method) → name (exact or dot-chop) → outcome
  ↓
SUCCESS path: SUCCESS → ALWAYS
FAILURE path: FAILURE → ALWAYS
  ↓
Parameter binding (pull annotations, Throwable injection)
  ↓
Attributes saved; context discarded at scope end
```

---

## OTEL `SpanKind` Reference

| SpanKind   | Use when…                               | Examples                                       |
| ---------- | --------------------------------------- | ---------------------------------------------- |
| `SERVER`   | Handles an **incoming** request/message | HTTP controller, gRPC server, message consumer |
| `CLIENT`   | Makes an **outgoing** request           | HTTP/gRPC client, external API, DB driver      |
| `PRODUCER` | Publishes to a broker/topic/queue       | Kafka/RabbitMQ/SNS send                        |
| `CONSUMER` | Receives/processes a brokered message   | Kafka poll, RabbitMQ listener, SQS handler     |
| `INTERNAL` | Performs in‑process work                | Cache recompute, rule evaluation, CPU step     |

---

## Notes & Terminology

* **Attributes** = saved key/values on the event (carry through serialization / storage).
* **Event context** = ephemeral key/values for in‑process enrichment and handler binding; not saved.
* **Dot‑chop** = right‑to‑left name reduction to find a handler when using prefixes.
* **Receiver scopes** (`@OnEventScope` at class level) reduce the candidate space; method scopes further reduce it.
* **Outcome** replaces earlier “mode” concepts; use `SUCCESS`/`FAILURE` semantics via `@OnOutcome` or flow shorthands.

---

## Troubleshooting

* **Handler never fires** → Check lifecycle visibility (class), outcome filters, and scopes; ensure a blank selector exists when relying on dot‑chop.
* **Parameter nulls** → Verify key names for `@PullAttribute`/`@PullContextValue`; use the `PullAll*` binders to inspect inputs.
* **Multiple matches** → Remove duplicates (same lifecycle visibility + selector + outcome set + signature). Keep one or differentiate parameters.
* **Throwable missing** → The event was successful; don’t declare `@BindEventThrowable` on success‑only handlers.
