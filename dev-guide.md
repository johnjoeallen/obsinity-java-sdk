# Obsinity Telemetry — Combined Developer & Practical Guide

---

## Quick Primer

* **Flows & steps:** Use `@Flow` to open a flow (root/subflow). Use `@Step` to advance work inside the open flow; if no flow is open, a step may auto-open one—guard with `@OrphanAlert`.&#x20;
* **Scopes:** Narrow what a receiver can see using `@OnEventScope` (prefix/name, kind). Class-level scope can be refined at method level.&#x20;
* **Lifecycle & outcomes:** Use class-level `@OnEventLifecycle` to decide which phases are even visible. Split success/failure with `@OnOutcome` or shorthands.&#x20;

---

## Annotation Reference (combined)

### Core (structure & selection)

| Annotation                         | Target         | Purpose                                                                                                          |
| ---------------------------------- | -------------- | ---------------------------------------------------------------------------------------------------------------- |
| `@Flow`                            | Method         | **Open a new flow** (root if none open, otherwise a subflow). Produces lifecycle signals for receivers.          |
| `@Step`                            | Method         | **Advance within the open flow**; may auto-open if none active (pair with `@OrphanAlert`).                       |
| `@Kind`                            | Method / Class | Set OTEL `SpanKind` (`SERVER`, `CLIENT`, `PRODUCER`, `CONSUMER`, `INTERNAL`).                                    |
| `@OrphanAlert`                     | Method / Class | Control log level when a `@Step` auto-opens a flow.                                                              |
| `@OnEventLifecycle`                | **Class**      | Restrict **visible lifecycles** for all handlers in a receiver. Repeatable via container. **Class-level only.**  |
| `@OnEventScope` / `@OnEventScopes` | Method / Class | Name/prefix and `SpanKind` filter (**scope**). Class scope can be refined at method level.                       |

### Flow receivers (flow-centric handlers)

| Annotation                          | Target | Purpose                                                                                                              |
| ----------------------------------- | ------ | -------------------------------------------------------------------------------------------------------------------- |
| `@EventReceiver`                    | Class  | Declares a bean containing flow handlers.                                                                            |
| `@OnFlowStarted`                    | Method | Handle **flow start** (exact or prefix; supports `value`/`name` alias).                                              |
| `@OnFlowCompleted`                  | Method | Handle **flow finish** (success or failure).                                                                         |
| `@OnFlowSuccess` / `@OnFlowFailure` | Method | Outcome-specific shorthands for completed flows.                                                                     |
| `@OnOutcome` / `@OnOutcomes`        | Method | Explicit success/failure filtering.                                                                                  |
| `@OnFlowNotMatched`                 | Method | Receiver-scoped fallback when no `@OnFlow*` method matched after dot-chop. (**Replaces any blank selector usage.**)  |
| `@GlobalFlowFallback`               | Class  | App-wide fallback when **no receiver** matched, including their `@OnFlowNotMatched`.                                 |

### Attribute & context I/O (producer “push”)

| Annotation          | Target    | Purpose                                                                                 |
| ------------------- | --------- | --------------------------------------------------------------------------------------- |
| `@PushAttribute`    | Parameter | Save a parameter to **attributes** (persisted). Supports `value`/`name`, `omitIfNull`.  |
| `@PushContextValue` | Parameter | Save a parameter to **event context** (ephemeral).                                      |

### Parameter binding (consumer “pull”)

| Annotation              | Target    | Purpose                                            |
| ----------------------- | --------- | -------------------------------------------------- |
| `@PullAttribute`        | Parameter | Bind a single attribute (supports `value`/`name`). |
| `@PullAllAttributes`    | Parameter | Bind the full attributes map.                      |
| `@PullContextValue`     | Parameter | Bind a single context value.                       |
| `@PullAllContextValues` | Parameter | Bind the full context map.                         |
| `@BindEventThrowable`   | Parameter | Bind the `Throwable` for failure outcomes.         |

### Preconditions (handler gating)

| Annotation              | Target | Purpose                                         |
| ----------------------- | ------ | ----------------------------------------------- |
| `@RequiredAttributes`   | Method | Require attribute keys before invoking handler. |
| `@RequiredEventContext` | Method | Require context keys before invoking handler.   |

---

## Matching Model (name dot-chop, scope, lifecycle, outcome)

1. **Scope first:** `@OnEventScope` (prefix/name, kind) limits what a receiver can even consider. Method-level scope refines (intersects) class scope.&#x20;
2. **Lifecycle visibility:** `@OnEventLifecycle` at class level decides which phases are visible to that receiver’s methods.&#x20;
3. **Name resolution (dot-chop):** try exact, then right-to-left prefix: `a.b.c` → `a.b` → `a` → *(empty)*. When it reaches empty, **invoke `@OnFlowNotMatched`** on that receiver (if present). **Do not** use `@OnFlow...("")`.&#x20;
4. **Outcome:** further restrict using `@OnOutcome` or `@OnFlowSuccess` / `@OnFlowFailure` as needed.&#x20;

---

## Step-By-Step: From Zero to Useful Signals

### 1) Open a flow (root or subflow) and save data

```java
@Service
public class CheckoutService {

  @Flow("checkout.start")                // opens a flow (root/subflow)
  @Kind(SpanKind.SERVER)
  public void start(@PushAttribute("user.id") String userId,
                    @PushContextValue("session.id") String sessionId) {
    // app logic...
  }
}
```

**Achieves:** Opens `checkout.start`; attributes/context saved; lifecycle signals produced for receivers.&#x20;

---

### 2) Advance with steps (and guard auto-open)

```java
@Service
public class ReserveAndPay {

  @Step("checkout.reserve")
  public void reserve(@PushAttribute("sku") String sku) { /* ... */ }

  @Step("checkout.payment")
  @OrphanAlert(OrphanAlert.Level.WARN) // warns if no flow and a new one is auto-opened
  public void pay(@PushAttribute("payment.method") String method) { /* ... */ }
}
```

**Achieves:** Child signals within the open flow; orphan guard if a step would auto-open.&#x20;

---

### 3) Subflow within a parent flow

```java
@Service
@RequiredArgsConstructor
public class OrderOrchestrator {
  private final CheckoutService checkout;
  private final RiskService risk;

  @Flow("orders.place")  // parent flow
  public void place(String userId, String session, String sku, String method) {
    checkout.start(userId, session); // runs inside parent flow
    risk.assess(userId);             // opens a subflow
  }
}

@Service
class RiskService {
  @Flow("risk.assess")   // becomes a subflow since a flow is open
  public void assess(@PushAttribute("user.id") String uid) { /* ... */ }
}
```

**Achieves:** Shows `@Flow` as root or subflow based on context.&#x20;

---

### 4) Observe starts, finishes, outcomes — plus fallback

```java
@EventReceiver
@OnEventScope(prefix = "checkout")                 // receiver-wide scope
@OnEventLifecycle(ROOT_FLOW_FINISHED)              // only finished flows visible
public class CheckoutReceivers {

  @OnFlowStarted(name = "checkout.start")          // exact start
  public void onStart(@PullAttribute("user.id") String userId) { /* ... */ }

  @OnFlowCompleted("checkout") @OnOutcome(Outcome.SUCCESS)
  public void onSuccess(@PullAllAttributes Map<String,Object> attrs) { /* ... */ }

  @OnFlowCompleted("checkout") @OnOutcome(Outcome.FAILURE)
  public void onFailure(@BindEventThrowable Throwable ex,
                        @PullAllAttributes Map<String,Object> attrs) { /* ... */ }

  @OnFlowNotMatched                                     // terminal inside this receiver
  public void localFallback(String flowName) { /* ... */ }
}
```

**Achieves:** Scoped, lifecycle-aware handlers with outcome splitting and a clean local fallback.&#x20;

---

### 5) Guards & failure-only with bound throwable

```java
@EventReceiver
public class ChargeGuards {

  @OnFlowStarted("billing.charge")
  @RequiredAttributes({"user.id", "amount"})
  public void begin(@PullAttribute("user.id") String uid,
                    @PullAttribute("amount") long cents) { /* ... */ }

  @OnEventLifecycle(ROOT_FLOW_FINISHED)
  public static class Endings {
    @OnFlowCompleted("billing.charge")
    @OnOutcome(Outcome.FAILURE)
    @RequiredEventContext({"session.id"})
    public void failed(@BindEventThrowable IllegalStateException ex,
                       @PullContextValue("session.id") String session) { /* ... */ }

    @OnFlowNotMatched
    public void endingsFallback(String flowName) { /* ... */ }
  }
}
```

**Achieves:** Strict preconditions; failure-only handling with throwable binding; scoped fallback.&#x20;

---

### 6) Programmatic “push” (no param annotations)

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

**Achieves:** Same effect as `@Push*` annotations, but explicit via API.&#x20;

---

## Combination Cookbook (ready-to-drop patterns)

### A) Class lifecycle + scope + outcome split + throwable + fallback

```java
@EventReceiver
@OnEventScope(prefix = "checkout")
@OnEventLifecycle(ROOT_FLOW_FINISHED)
public class CheckoutOutcomes {

  @OnFlowSuccess("checkout")
  public void ok(@PullAllAttributes Map<String,Object> attrs) { /* metrics */ }

  @OnFlowFailure("checkout")
  public void fail(@BindEventThrowable RuntimeException ex,
                   @PullAllAttributes Map<String,Object> attrs) { /* alerting */ }

  @OnFlowNotMatched
  public void localFallback(String flowName) { /* audit */ }
}
```

---

### B) Multiple scopes at class level + method refinement

```java
@EventReceiver
@OnEventScopes({
  @OnEventScope(prefix = "billing"),
  @OnEventScope(prefix = "refunds", kind = {SERVER, CONSUMER})
})
@OnEventLifecycle(ROOT_FLOW_FINISHED)
public class BillingAndRefunds {

  @OnFlowCompleted("billing.payment")
  @OnOutcome(Outcome.SUCCESS)
  public void paymentOk(@PullAllAttributes Map<String,Object> attrs) { /* ... */ }

  @OnFlowCompleted("refunds")
  @OnEventScope(kind = CONSUMER)  // refine to CONSUMER
  @OnOutcome(Outcome.FAILURE)
  public void refundFailed(@BindEventThrowable Throwable ex,
                           @PullContextValue("session.id") String session) { /* ... */ }

  @OnFlowNotMatched
  public void refundsFallback(String flowName) { /* ... */ }
}
```

---

### C) Local vs Global fallback

```java
@EventReceiver
@OnEventScope(prefix = "inventory")
public class InventoryReceiver {

  @OnFlowSuccess("inventory.restock")
  public void restockOk(@PullAllAttributes Map<String,Object> attrs) { /* ... */ }

  @OnFlowNotMatched // handles inventory.* misses inside this receiver
  public void inventoryFallback(String flowName) { /* ... */ }
}

@GlobalFlowFallback
public class LastResort {
  public void capture(String flowName, @PullAllAttributes Map<String,Object> attrs) { /* ... */ }
}
```

---

## OTEL `SpanKind` Cheat-Sheet

| SpanKind   | Use when…                                | Examples                                       |   |
| ---------- | ---------------------------------------- | ---------------------------------------------- | - |
| `SERVER`   | Handling an **incoming** request/message | HTTP controller, gRPC server, message consumer |   |
| `CLIENT`   | Making an **outgoing** call              | HTTP/gRPC client, external API, DB driver      |   |
| `PRODUCER` | Publishing to a broker                   | Kafka/RabbitMQ/SNS send                        |   |
| `CONSUMER` | Receiving from a broker                  | Kafka poll, RabbitMQ listener, SQS handler     |   |
| `INTERNAL` | Pure in-process work                     | Cache recompute, rule evaluation               |   |

---

## Troubleshooting (consolidated)

* **Handler never fires:** Check **class lifecycle** (`@OnEventLifecycle`), **scopes** (`@OnEventScope`), and outcome filters. If name resolution dot-chops to empty, ensure the receiver has **`@OnFlowNotMatched`**.&#x20;
* **Out-of-scope events:** A receiver’s `@OnFlowNotMatched` does **not** trigger for events outside its class/method scope or lifecycle—this is by design.&#x20;
* **Null parameters:** Verify keys for `@PullAttribute` / `@PullContextValue`; temporarily bind with `@PullAllAttributes` / `@PullAllContextValues` to inspect.&#x20;
* **Duplicate handling:** Avoid multiple methods with identical lifecycle visibility + selector + outcome set + signature.
* **Throwable missing on “failure” path:** Ensure the handler actually filters for failure (`@OnOutcome(FAILURE)` or `@OnFlowFailure`) and declares a single `@BindEventThrowable` parameter.&#x20;

---

## Appendix — Aliases & Terminology

* Many annotations support `value`/`name` aliases (e.g., `@OnFlowStarted("x")` ≡ `@OnFlowStarted(name="x")`; `@PushAttribute("k")` ≡ `@PushAttribute(name="k")`).&#x20;
* **Attributes** = persisted key/values on the event. **Event context** = ephemeral, for in-process binding. **Dot-chop** = right-to-left name reduction used when matching prefixes.&#x20;

---
