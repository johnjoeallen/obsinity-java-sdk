# Proposal: Multi‑Queue Dispatch with Least‑Loaded Flow Assignment

## Objective

Use **multiple worker queues/threads** to scale event handling while preserving **per‑flow ordering**. Assign each new root flow to the **least‑loaded** queue at the moment it starts.

---

## Design Overview

* **Ingress:** single bounded queue accepting all signals.
* **Routing Map:** `rootFlowId → queueIndex`, stored on first sight of a root.
* **Workers:** `N` bounded queues, each drained by one worker thread.
* **Ordering:** all events for the same `rootFlowId` always go to the same worker (FIFO per flow).
* **Balancing:** on first event of a root, pick the **queue with the fewest items**.

---

## Routing Logic

1. **Root assignment (first event of a flow)**

    * Scan worker queues for `.size()`.
    * Choose the **minimum size** (tie‑break by lowest index).
    * Save mapping `{rootFlowId → queueIndex}`.

2. **Subsequent events**

    * Look up `queueIndex` and enqueue to that queue.

3. **Flow completion**

    * Remove mapping on `ROOT_FLOW_FINISHED`.

---

## Backpressure (configurable)

* **BLOCK:** producer waits if target queue is full.
* **DROP\_OLDEST:** evict oldest on target queue to keep recent events hot.
* **DROP\_NEWEST:** reject current enqueue to protect latency.

---

## Minimal Configuration

* `workerCount`: e.g., `min(cores, 8)`
* `ingressCapacity`: e.g., `65k`
* `workerQueueCapacity`: e.g., `8k`
* `batchMax`: e.g., `128` (drain up to this many per loop)

---

## Metrics (essential)

* Per‑worker queue depth and signals/sec
* Ingress depth and drops (by policy)
* Active flow mappings
* Handler latency (p50/p95/p99)

---

## Sketch (Java‑ish)

```java
final int N = workerCount;
final BlockingQueue<Signal> ingress = new ArrayBlockingQueue<>(ingressCapacity);
final List<BlockingQueue<Signal>> workerQs = IntStream.range(0, N)
    .mapToObj(i -> new ArrayBlockingQueue<Signal>(workerQueueCapacity))
    .toList();
final ConcurrentMap<String,Integer> flowToQueue = new ConcurrentHashMap<>();

int pickLeastLoaded() {
  int idx = 0, best = Integer.MAX_VALUE;
  for (int i = 0; i < N; i++) {
    int sz = workerQs.get(i).size();
    if (sz < best) { best = sz; idx = i; }
  }
  return idx;
}

void route(Signal s) throws InterruptedException {
  int idx = flowToQueue.computeIfAbsent(s.rootFlowId(), k -> pickLeastLoaded());
  // apply backpressure policy here
  workerQs.get(idx).put(s);
}

void completeFlow(String root) { flowToQueue.remove(root); }
```

---

## Why this works

* **Scales** with `N` workers.
* **Preserves causality per flow** (all events for a flow stay ordered).
* **Adapts to load** (new flows land on the least‑busy queue at assignment time).

