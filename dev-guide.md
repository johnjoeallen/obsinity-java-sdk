# Obsinity Telemetry Developer Guide

---

## Annotation Reference

### Core (structure & selection)

| Annotation           | Target         | Purpose                                                                                                                             |
| -------------------- | -------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| `@Flow`              | Method         | Starts a **flow** (root or subflow) and **sends** lifecycle signals; activates context so nested `@Step` calls become child events. |
| `@Step`              | Method         | **Records** a **step** inside the active flow; auto‑promoted to a flow if none is active.                                           |
| `@Kind`              | Method / Class | Sets OTEL `SpanKind` (`SERVER`, `CLIENT`, `PRODUCER`, `CONSUMER`, `INTERNAL`).                                                      |
| `@OrphanAlert`       | Method / Class | Controls log level when a `@Step` is auto‑promoted because no active `@Flow` exists.                                                |
| `@OnEventLifecycle`  | **Class**      | Component‑level filter restricting **visible lifecycles** (e.g., `ROOT_FLOW_FINISHED`). Repeatable.                                 |
| `@OnEventLifecycles` | **Class**      | Container for multiple `@OnEventLifecycle` entries.                                                                                 |
| `@OnEventScope`      | Method / Class | Declares name/prefix/kind filters (**scope**) for matching events.                                                                  |
| `@OnEventScopes`     | Method / Class | Container for multiple `@OnEventScope` entries.                                                                                     |

### Flow receivers (flow‑centric handlers)

| Annotation            | Target | Purpose                                                                              |
| --------------------- | ------ | ------------------------------------------------------------------------------------ |
| `@EventReceiver`      | Class  | Marks a bean containing flow/step event handlers.                                    |
| `@OnFlowStarted`      | Method | Handle when a flow **starts** (exact name or prefix; alias `value`/`name`).          |
| `@OnFlowCompleted`    | Method | Handle when a matched flow **finishes** (success or failure).                        |
| `@OnFlowSuccess`      | Method | Handle only when a matched flow **succeeds**.                                        |
| `@OnFlowFailure`      | Method | Handle only when a matched flow **fails**.                                           |
| `@OnEveryFlow`        | Method | **Handle any flow** that passes class‑level scope/lifecycle (no name/prefix needed). |
| `@OnFlowNotMatched`   | Method | Component‑scoped fallback when **no** `@OnFlow*` in this receiver matched.           |
| `@GlobalFlowFallback` | Class  | Global fallback receiver when **no receiver in the app** matched a flow.             |

### Outcome filtering

| Type / Annotation | Target | Purpose                                          |
| ----------------- | ------ | ------------------------------------------------ |
| `enum Outcome`    | —      | `SUCCESS`, `FAILURE`.                            |
| `@OnOutcome`      | Method | Run once per flow completion with the `Outcome`. |
| `@OnOutcomes`     | Method | Container for multiple `@OnOutcome` entries.     |

### Attribute & context I/O (producer‑side “push”)

| Annotation          | Target    | Purpose                                                                                                            |
| ------------------- | --------- | ------------------------------------------------------------------------------------------------------------------ |
| `@PushAttribute`    | Parameter | **Save** a method parameter value into **attributes** (saved on the event). Supports `value`/`name`, `omitIfNull`. |
| `@PushContextValue` | Parameter | **Save** a method parameter value into **event context** (ephemeral).                                              |

### Parameter binding (consumer‑side “pull”)

| Annotation                                       | Target    | Purpose                                              |
| ------------------------------------------------ | --------- | ---------------------------------------------------- |
| `@PullAttribute`                                 | Parameter | Bind a single attribute key to the parameter.        |
| `@AllAttrs` *(alias: `@PullAllAttributes`)*      | Parameter | Bind the **entire attributes map** to the parameter. |
| `@PullContextValue`                              | Parameter | Bind a single event context key to the parameter.    |
| `@AllContext` *(alias: `@PullAllContextValues`)* | Parameter | Bind the **entire event context map**.               |

---

## Selection & Matching (dot‑chop, scope, lifecycle, kind)

**Name matching (dot‑chop):** handlers declared with a **prefix** match deeper names by chopping from the right:
`a.b.c` → `a.b` → `a` → *(stop)*.

**No blank selector pattern.** When the chain ends, treat as **not matched** and prefer **`@OnFlowNotMatched`** for fallbacks. For match‑all behavior on flows, use **`@OnEveryFlow`** (not an empty `name`).

**Scope filters (`@OnEventScope`):**

* `prefix` / alias `value`/`name`: event name prefix or exact name.
* `kind`: one or more `SpanKind` values; if set, only those kinds match.
* May be placed on the **class** to scope all methods, and/or on individual methods for narrower filters.

**Lifecycle filters (class level):** annotate the receiver class with `@OnEventLifecycle(LIFECYCLE)` (or `@OnEventLifecycles`) to constrain which lifecycles are even visible.

**Outcome filters:** use `@OnOutcome(Outcome.SUCCESS|FAILURE)` or the shorthands `@OnFlowSuccess` / `@OnFlowFailure` / `@OnFlowCompleted`.

---

## Handler Parameter Binding Cheat‑Sheet

* Attributes: `@PullAttribute("user.id") String userId`, `@AllAttrs Map<String,Object> attrs`
* Context: `@PullContextValue("session") Session s`, `@AllContext Map<String,Object> ctx`
* Kind/lifecycle aren’t parameters; set with `@Kind` (method/class), and `@OnEventLifecycle` on the **class**.
* Producer push (from application code): `@PushAttribute("order.id") String id`, `@PushContextValue("tenant") String t`

---

## Quick Rules to Avoid Startup Failures (updated)

1. **Declare outcomes explicitly** on handlers that care about result: use `@OnOutcome`, or the shorthands `@OnFlowSuccess` / `@OnFlowFailure` / `@OnFlowCompleted`.
2. **Do not rely on a blank selector**. Use **`@OnEveryFlow`** for match‑all flows, or prefixes for groups; when dot‑chop ends with no match, `@OnFlowNotMatched` acts as the fallback.
3. **Do not duplicate** handlers with the **same** (lifecycle visibility via class + name/prefix or `@OnEveryFlow` + outcome set + method signature).
4. Use `@RequiredAttributes` / `@RequiredEventContext` for gating — missing keys prevent invocation.
5. When scoping at **class level** via `@OnEventScope`, remember method‑level scopes **refine** (intersect) the class scope.

---

## Step‑by‑Step Guide

### Step 1 — Record work in your code

Pick method boundaries where telemetry makes sense. Use `@Flow` for the operation and `@Step` inside it.

```java
/** Starts the user registration flow and records two steps: validation and account creation. */
@Flow("user.register")
public void register(@PushAttribute("user.id") String userId) {
  validate(userId);
  createAccount(userId);
}

/** Records the validation step inside the active flow. */
@Step("user.validate") void validate(String userId) { /* record validation */ }

/** Records the account creation step inside the active flow. */
@Step("user.create")   void createAccount(String userId) { /* record creation */ }
```

### Step 2 — Save useful data

Save attributes (persisted with the event) and context (ephemeral, handler‑bindable).

```java
/** Example of programmatic attribute/context saving from application code. */
TelemetryContext.putAttr("tenant", "eu-1");
TelemetryContext.putContext("request.ip", ip);
```

Or push from parameters:

```java
/** Parameter push: values are saved into attributes/context before the event is sent. */
public void register(@PushAttribute("user.id") String userId,
                     @PushContextValue("session.id") String session) { /*...*/ }
```

### Step 3 — Set span kind when it matters

```java
/** Incoming HTTP request handled as a SERVER span. */
@Kind(SpanKind.SERVER)
@Flow("orders.submit")
public void submit(/*...*/) { /* incoming HTTP request */ }
```

Default is `INTERNAL`.

### Step 4 — Decide how you’ll handle outcomes (and match‑all flows)

Use separate handlers for success and failure, a single `@OnOutcome` hook, or the new **`@OnEveryFlow`** for match‑all.

```java
/** Receiver for order outcomes using dedicated success/failure hooks. */
@EventReceiver
class OrderOutcomes {
  /** Runs when the orders.submit flow finishes successfully. */
  @OnFlowSuccess("orders.submit")
  void ok(@AllAttrs Map<String,Object> attrs) { /* save metrics */ }

  /** Runs when the orders.submit flow finishes with failure. */
  @OnFlowFailure("orders.submit")
  void fail(@AllAttrs Map<String,Object> attrs) { /* alert, notify, etc. */ }
}

/** A single, once‑per‑flow outcome hook for all orders.* flows. */
@EventReceiver
@OnEventScope(prefix = "orders")
class OrderOutcomeTap {
  @OnOutcome
  void tap(@AllAttrs Map<String,Object> attrs, Outcome outcome) { /* idempotent sink */ }
}

/** Match‑all (within scope) using @OnEveryFlow instead of blank selectors. */
@EventReceiver
@OnEventLifecycle(ROOT_FLOW_FINISHED) // only finished flows
class AuditAllFlows {
  /** Runs for every finished flow visible to this receiver; outcome is optional parameter. */
  @OnEveryFlow
  void audit(String flowName, Outcome outcome, @AllAttrs Map<String,Object> attrs) { /* audit */ }
}
```

> **No blank selector**: never use empty string ("") to mean "match all". Use **`@OnEveryFlow`** or prefixes.

### Step 5 — Scope and lifecycle at class level

Make handlers only see what they should.

```java
/** Receiver limited to finished checkout flows under the checkout.* namespace. */
@EventReceiver
@OnEventScope(prefix = "checkout")                 // scope by name/prefix
@OnEventLifecycle(ROOT_FLOW_FINISHED)               // only finished flows are visible
class CheckoutReceiver { /* methods... */ }
```

Method‑level scopes further **refine** the class scope.

### Step 6 — Bind parameters safely

Use `@PullAttribute`, `@AllAttrs`, `@PullContextValue`, and `@AllContext`. Prefer exact keys; fall back to all‑map binders for diagnostics.

### Step 7 — Handle unmatched flows explicitly

```java
/** Component-scoped fallback when nothing in this receiver matched. */
@EventReceiver
class Fallbacks {
  @OnFlowNotMatched
  void nothingMatched(String flowName) { /* diagnostic */ }
}
```

### Step 8 — Validate in runtime and tests

* Enable strict validation to catch overlaps/duplicates.
* Use integration tests to assert that the right handlers fire for success and failure.

---

## Extended Examples

### 1) Flow and steps with attributes/context (producer‑side)

```java
/** Starts the checkout flow and records reservation and payment steps. */
@Flow("checkout.start")
@Kind(SpanKind.SERVER)
public void startCheckout(
    @PushAttribute("user.id") String userId,
    @PushContextValue("session.id") String sessionId
) {
    // application code...
}

/** Records the inventory reservation step. */
@Step("checkout.reserve")
public void reserveInventory(@PushAttribute("sku") String sku) { /* ... */ }

/** Records the payment step; will log WARN if auto-promoted due to missing flow. */
@Step("checkout.payment")
@OrphanAlert(OrphanAlert.Level.WARN)
public void processPayment(@PushAttribute("payment.method") String method) { /* ... */ }
```

### 2) Receiver with class‑level lifecycle + scope + outcomes

```java
/** Receiver focused on finished checkout flows, with success/failure splits. */
@EventReceiver
@OnEventScope(prefix = "checkout")
@OnEventLifecycle(ROOT_FLOW_FINISHED)
public class CheckoutReceiver {

  /** Fires exactly when checkout.start begins. */
  @OnFlowStarted(name = "checkout.start")
  public void onStart(@PullAttribute("user.id") String userId) { /* ... */ }

  /** Fires at start for any checkout.* flow. */
  @OnFlowStarted("checkout")
  public void onAnyCheckoutStart(@AllAttrs Map<String,Object> attrs) { /* ... */ }

  /** Fires on success for any checkout.* flow. */
  @OnFlowCompleted("checkout")
  @OnOutcome(Outcome.SUCCESS)
  public void onAnyCheckoutSucceeded(@AllAttrs Map<String,Object> attrs) { /* ... */ }

  /** Fires on failure for any checkout.* flow. */
  @OnFlowCompleted("checkout")
  @OnOutcome(Outcome.FAILURE)
  public void onAnyCheckoutFailed(@AllAttrs Map<String,Object> attrs) { /* ... */ }

  /** Diagnostic when this receiver matched nothing. */
  @OnFlowNotMatched
  public void nothingMatchedHere(String flowName) { /* diagnostic */ }
}
```

### 3) Match‑all using @OnEveryFlow with lifecycle filters

```java
/** Audits every finished flow across the application. */
@EventReceiver
@OnEventLifecycle(ROOT_FLOW_FINISHED)
public class GlobalAudit {
  @OnEveryFlow
  public void auditAll(String flowName, Outcome outcome, @AllAttrs Map<String,Object> attrs) { /* audit */ }
}
```

### 4) Preconditions

```java
/** Guards that required attributes/context are present before charging. */
@EventReceiver
public class GuardedReceiver {

  /** Only runs if user.id and amount are present. */
  @OnFlowStarted("billing.charge")
  @RequiredAttributes({"user.id", "amount"})
  public void charge(@PullAttribute("user.id") String uid,
                     @PullAttribute("amount") BigDecimal amount) { /* ... */ }

  /** Failure outcome requires session.id in context. */
  @OnEventLifecycle(ROOT_FLOW_FINISHED)
  public static class ChargeOutcomes {

    @OnFlowCompleted("billing.charge")
    @RequiredEventContext({"session.id"})
    @OnOutcome(Outcome.FAILURE)
    public void chargeFailed(@PullContextValue("session.id") String session) { /* ... */ }
  }
}
```

### 5) Programmatic API (push without annotations)

```java
/** Demonstrates programmatic attribute/context saving inside a step. */
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

## Flow & Step Semantics

* `@Flow` **sends** multiple lifecycle signals; it may be root or subflow.
* `@Step` **records** child work; step attributes/context carry into the resulting event.
* Auto‑promotion occurs when a step runs without an active flow; govern logs with `@OrphanAlert`.
* Use **save** when referring to attributes/context.

---

## Handler Selection & Routing

* **Dot‑chop prefix matching**: `a.b.c` → try `a.b.c`, then `a.b`, then `a`.
* **No blank selector**: when the chop ends empty, treat as *not matched* → `@OnFlowNotMatched`; for match‑all flows use **`@OnEveryFlow`**.
* **No wildcard mixing**: prefer explicit prefixes + outcomes.
* **Validation**: the scope/handler validator rejects invalid intersections and mixed unmatched strategies.

---

## Parameter Binding

* **Write** (producer): `@PushAttribute(name|value = "key")` or `TelemetryContext.putAttr(...)` to **save** attributes; `@PushContextValue` / `putContext(...)` for context.
* **Read** (handler): `@PullAttribute`, `@AllAttrs`, `@PullContextValue`, and `@AllContext`.
* **Aliases**: `@Attr` and `@Attribute(name = "...")` for handler parameters.

---

## Error Handling & Fallbacks

* Prefer **separate** SUCCESS/FAILURE handlers.
* Use **`@OnOutcome`** for a single, once‑per‑flow hook that applies to both outcomes (idempotent sinks).
* Use **`@OnEveryFlow`** when you need a **match‑all flow** handler without specifying names/prefixes.
* A global fallback receiver records a diagnostic when **no** handler ran.

---

## Event Scope (class‑level)

* **Applies only at class level** to narrow which events a component can see.
* **Does not** carry lifecycle or outcome; those live on `@OnEvent`/`@OnOutcome` (method) and `@OnEventLifecycle` (class).
* Typical fields: name/prefix filters and kind (`SpanKind`).

---

## OTEL Mapping & Compatibility

* **Span mapping**: `@Flow` ↔ span (root/sub); `@Step` ↔ child span (or flow when auto‑promoted).
* **Kind**: `@Kind` → `SpanKind`; default `INTERNAL`.
* **Context propagation**: bridge thread‑locals ↔ OTEL Context.
* **Receivers**: OTLP ingestion can be translated into internal events.

---

## Spring Integration

* Interceptors around `@Flow`/`@Step` to handle start/finish and attribute saving.
* Scanning of `@EventReceiver`; registration of `@OnFlow*`, `@OnEveryFlow`, and `@OnOutcome` methods.
* A validator runner ensures scopes/handlers are consistent after scanning.
* **Tip**: If a `SmartInitializingSingleton` validator isn’t firing, ensure it’s not vetoed by conditions and that the runner bean is created.

---

## Configuration Keys (stable)

* `obsinity.flow.orphan-alert` — default log level for auto‑promoted steps.
* `obsinity.handlers.validation.strict` — enable strict validation.
* `obsinity.storage.retention.days` — retention window.
* `obsinity.partitions.rotate.cron` — rotation schedule.
* `obsinity.export.otlp.*` — OTLP exporter options.

---

## Storage, Partition & Retention Management (high level)

* **Schema & migrations** live with the storage code (managed by Flyway/Liquibase) — details purposefully omitted here.
* **Partitions**: create‑next/close‑current, attach indexes, rotate on schedule.
* **Retention**: drop aged partitions; support dry‑run and repair modes.
* **Examples**: runnable tests execute rotation and retention end‑to‑end against Postgres.

---

## Testing

* **Testkit** — JUnit 5 extension, in‑memory bus/exporter fakes, fixture builders for flows/steps.
* **Integration tests** — run the service, hit REST/OTLP controllers, assert storage/query paths.

---

## Notes & Terminology

* **Attributes** = saved key/values on the event (carried through serialization/storage).
* **Event context** = ephemeral key/values for in‑process enrichment and handler binding; not saved.
* **Dot‑chop** = right‑to‑left name reduction to find a handler when using prefixes.
* **Receiver scopes** (`@OnEventScope` at class level) reduce the candidate space; method scopes further reduce it.
* **Outcome** = `SUCCESS`/`FAILURE`; use `@OnOutcome` or flow shorthands for outcome‑specific handling.
* **Match‑all flows** = use `@OnEveryFlow` (not blank selectors).

---

## Troubleshooting

* **Handler never fires** → Check lifecycle visibility (class), outcome filters, and scopes; unmatched flows go to `@OnFlowNotMatched`.
* **Parameter is null** → Verify key names for `@PullAttribute`/`@PullContextValue`; use the *all‑map* binders to inspect inputs.
* **Multiple matches** → Remove duplicates (same lifecycle visibility + selector/`@OnEveryFlow` + outcome set + signature). Keep one or differentiate parameters.
