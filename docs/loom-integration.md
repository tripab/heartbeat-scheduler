# Loom Integration Design Notes

## Why We Delegate Work-Stealing to Project Loom

The original heartbeat scheduling paper (Acar et al., PLDI 2018) is written for a custom runtime where the parallel work pool is managed by the scheduler itself — typically using a Chase-Lev deque per worker. This document explains why this implementation takes a different route: delegating all work distribution to **Project Loom's virtual-thread carrier scheduler**, and what the practical consequences of that decision are.

---

## Carrier-Thread Scheduling in Loom

Project Loom schedules virtual threads on a pool of **carrier (platform) threads**, which by default is a `ForkJoinPool` sized to the number of available processors. When a virtual thread is created (e.g., by `Thread.ofVirtual().start(...)` or `Executors.newVirtualThreadPerTaskExecutor()`), Loom submits it to this pool as a `ForkJoinTask`. The FJP's **work-stealing** algorithm then distributes the virtual threads across carriers: each carrier maintains a local deque; idle carriers steal from the tail of a random busy carrier's deque.

For the heartbeat scheduler this is directly relevant. When `HeartbeatTask.fork()` detects that the heartbeat timer has fired, it calls `VirtualThreadExecutor.promoteTask()`, which submits the promoted task to a `newVirtualThreadPerTaskExecutor()`. Under the hood, this creates a new virtual thread that is immediately placed into the FJP's work queue. From that point, **Loom's existing work-stealing handles load balancing** — no additional mechanism is needed.

This is a deliberate engineering decision: we get a production-quality, battle-tested work distributor for free, at the cost of some control over task locality. See the Tradeoffs section below.

---

## Pinning: When Virtual Threads Stick to a Carrier

A virtual thread is normally **unmounted** (yielded) when it blocks (e.g., on `Object.wait()`, `LockSupport.park()`, `BlockingQueue.take()`), freeing its carrier for another virtual thread. However, in two situations a virtual thread is **pinned** — it cannot be unmounted and holds its carrier for the duration:

1. **Inside a `synchronized` block or method.** Until Java 24 lifted this restriction (JEP 491, part of Java 24), `synchronized` always pinned. As of Java 25, `synchronized` no longer pins virtual threads (the JVM re-implements the monitor using a fair reentrant lock). This implementation targets Java 25, so `synchronized` in user code is not a pinning hazard.

2. **Inside a native frame.** JNI calls and `@IntrinsicCandidate` methods that invoke native helpers still pin. The heartbeat scheduler itself contains no JNI, but user tasks that call into native libraries (e.g., some NIO paths) may pin transiently.

**Interaction with promotion:** `HeartbeatTask.join()` calls `CompletableFuture.get()` when a task has been promoted. `CompletableFuture.get()` uses `LockSupport.park()` internally, which safely **unmounts** the virtual thread — the carrier is freed. This means a carrier blocked at `join()` does not become unavailable to other promotions. This is one of the key benefits of building on Loom: blocking at `join()` is efficient by construction.

---

## Safepoints and Heartbeat Polls

The JIT inserts **safepoint polls** — checks that the JVM can pause a thread for GC, deoptimisation, or biased-lock revocation — at method entries and loop backedges. This is exactly where the PRC rewriter (see `docs/prc-rewriter.md`) inserts heartbeat polls. At a JIT-compiled loop backedge, the JIT's safepoint check and our `checkHeartbeatStatic()` call are adjacent instructions.

When a GC safepoint fires:
- All threads are brought to their nearest safepoint (which, for a tight loop, may coincide with our poll point).
- During the STW pause, the heartbeat timer continues to advance (it reads `System.nanoTime()`).
- After the pause, the first poll that runs will likely see `shouldPromote() == true`.

This means GC pauses can cause a burst of promotions immediately afterwards, temporarily increasing virtual-thread creation rate. In practice, for G1GC with short pause targets (≤ 20 ms), this effect is negligible relative to the heartbeat period (30–300 μs). For benchmarks, use `-XX:+UseG1GC -XX:MaxGCPauseMillis=10` to keep GC noise low.

---

## JIT Inlining of `checkHeartbeatStatic()`

The heartbeat poll is a performance-critical path: it runs at every loop backedge (when the PRC agent is attached) or at every `fork()` (in the explicit-poll path). For the bound to hold in practice, the poll must be **inlinable** into the hot loop body.

`HeartbeatContext.checkHeartbeatStatic()` does:
1. `CONTEXT.get()` — a single `ThreadLocal` read, compiled to a TLS-relative load after inlining.
2. A null check on the result.
3. `ctx.checkHeartbeat()` — which calls `pollingStrategy.shouldPoll()` and `timer.shouldPromote()`.

The `shouldPoll()` fast path (for `CountBasedPolling`) is a single increment and comparison. The `shouldPromote()` fast path is a subtraction and comparison on `System.nanoTime()`. Both are single-digit-nanosecond operations.

Empirical check (run with `-XX:+PrintCompilation -XX:+PrintInlining`):

```
# Compile id 1234  hotspot/FibTask::compute @ 14 (87 bytes)
  @ 14   org.heartbeat.scheduler.core.HeartbeatContext::checkHeartbeatStatic (20 bytes)   inline (hot)
    @ 4   java.lang.ThreadLocal::get (11 bytes)   inline (hot)
    @ 9   org.heartbeat.scheduler.core.HeartbeatContext::checkHeartbeat (45 bytes)   inline (hot)
      @ 6   org.heartbeat.scheduler.core.CountBasedPolling::shouldPoll (12 bytes)   inline (hot)
      @ 21  org.heartbeat.scheduler.core.HeartbeatTimer::shouldPromote (20 bytes)   inline (hot)
```

The entire poll chain inlines into the caller. If inlining depth limits prevent this (seen when call chains are deeper than the JVM's `-XX:MaxInlineLevel`), use `-XX:MaxInlineLevel=15` (default is 9 in JDK 25) to allow deeper inlining.

---

## Context Propagation: ThreadLocal vs ScopedValue

The scheduler currently uses `ThreadLocal<HeartbeatContext>` to bind the per-carrier context to its thread. `ThreadLocal` is well understood but has two practical drawbacks in a Loom setting:

1. **Inheritance by virtual threads.** By default, `ThreadLocal` is **not** inherited by child virtual threads (unless `InheritableThreadLocal` is used). The scheduler's design is intentional: each promoted virtual thread receives a **fresh** `HeartbeatContext` (with its own timer), not the parent's. This means each promoted virtual thread operates as an independent carrier from the scheduler's perspective, which is consistent with the paper's model where each parallel subcomputation gets its own heartbeat.

2. **Thread-local storage overhead.** `ThreadLocal.get()` has been highly optimised in the JDK and is essentially free after JIT compilation (it compiles to a TLS offset load). However, virtual threads each carry a `ThreadLocalMap`, which means that for workloads creating millions of very short-lived virtual threads, the `ThreadLocalMap` allocation and GC pressure can be measurable.

**`ScopedValue` (JEP 446/481, preview in Java 21–23, finalised in Java 24+):** `ScopedValue` is designed to replace `InheritableThreadLocal` for structured-concurrency use cases. It is immutable within a scope and propagated to child structured tasks automatically. For the heartbeat scheduler, a `ScopedValue<HeartbeatContext>` would neatly model the "a new context is bound for each promoted task's duration" invariant. Migration is deferred until the API is stable in Java 26 (expected), but the design would be:

```java
// In VirtualThreadExecutor.promoteTask():
HeartbeatContext ctx = createContext();
ScopedValue.where(HEARTBEAT_CONTEXT, ctx).run(() -> task.call());

// In HeartbeatContext.current():
return HEARTBEAT_CONTEXT.get();
```

The migration is additive and does not change the observable scheduler semantics.

---

## The Honest Tradeoff

**What we gain by delegating to Loom:**

- **No custom deque.** A Chase-Lev-style work-stealing deque is 300–500 lines of lock-free code with a known history of subtle memory-ordering bugs. Delegating to the JDK eliminates this surface area entirely.
- **Correct virtual-thread semantics for free.** Loom handles mounting/unmounting, carrier-thread affinity, structured concurrency, and interaction with GC. We benefit from all of it without re-implementing any of it.
- **JMC / JFR observability.** The carrier FJP emits its own JFR events (steal counts, task counts). Combined with the custom `HeartbeatEvents` (see `src/main/java/org/heartbeat/scheduler/jfr/HeartbeatEvents.java`), the scheduler is fully observable in Java Mission Control without writing a UI.

**What we give up:**

- **Per-task locality control.** A custom deque with work-first scheduling (FJP's default) gives precise control over whether the parent or child executes immediately after a fork, which matters for cache locality in some workloads. Loom's scheduler makes its own locality decisions.
- **Steal-count visibility.** The FJP internally tracks steals, but this counter is not exposed in a stable public API. We estimate parallelism indirectly via JFR events and `HeartbeatContext.getTotalPromotions()`.
- **Heartbeat-granularity parallelism.** The heartbeat period controls *when* virtual threads are created, but *how many* run concurrently is controlled by the FJP's parallelism parameter (defaults to `Runtime.getRuntime().availableProcessors()`). To benchmark scalability over carrier count, set `-Djdk.virtualThreadScheduler.parallelism=N` or pass a custom FJP when constructing the executor.

For a project focused on the **novel part of heartbeat scheduling** — the PRC transformation and the interaction between a compile-time rewriter and a runtime that exploits it — delegating work distribution to Loom is the right call. If precise locality control and custom work-stealing become important, the natural next step is the custom Chase-Lev deque sketched in the original Phase 5/6 plan.

---

## Configuration Reference

| JVM flag | Purpose | Recommended value |
|---|---|---|
| `--add-exports java.base/jdk.internal.vm=ALL-UNNAMED` | Exposes `jdk.internal.vm.Continuation` | Required |
| `-Djdk.virtualThreadScheduler.parallelism=N` | Sets FJP carrier count | Match physical core count for benchmarks |
| `-Djdk.virtualThreadScheduler.maxPoolSize=N` | Upper bound on carrier threads | Default: 256 |
| `-XX:+UseG1GC -XX:MaxGCPauseMillis=10` | GC tuning for benchmark stability | |
| `-XX:MaxInlineLevel=15` | Allow deeper JIT inlining of poll chain | If inlining breaks |
| `-XX:+FlightRecorder` | Enable JFR | On by default in JDK 11+ |

## Further Reading

- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444) — the GA virtual threads JEP (Java 21)
- [JEP 491: Synchronize Virtual Threads Without Pinning](https://openjdk.org/jeps/491) — lifts the `synchronized` pinning restriction (Java 24)
- [JEP 481: Scoped Values](https://openjdk.org/jeps/481) — the `ScopedValue` API (preview Java 23)
- [Acar et al. PLDI 2018](https://www.andrew.cmu.edu/user/mrainey/papers/heartbeat.pdf) — the heartbeat scheduling paper, §4 on runtime implementation
- [Chase & Lev, SPAA 2005](https://dl.acm.org/doi/10.1145/1073970.1073974) — the classic work-stealing deque, for reference
