# Agent Log - Heartbeat Scheduler

## Task List

Tasks are grouped by phase. Improvement tasks (from `heartbeat-scheduler-improvements.md`) and Phases 1-4 are complete. The original Phases 5-8 (from `heartbeat-java-implementation-plan.md`) have been superseded by the **revised portfolio plan** in `PORTFOLIO_PLAN.md`, which prioritises a compiler-side bytecode rewriter (R1) and rigorous empirical verification (R3) over reinventing work-stealing infrastructure.

---

### Improvement Tasks (fix existing Phases 1-4)

#### Critical: Promotion Correctness
1. **Store task reference in PromotionPoint** (P-1, blocks CR-1)
   - Add `HeartbeatTask<?>` field to `PromotionPoint`
   - Update `PromotionTracker.promoteOldest()` to return the associated task
   - Update `HeartbeatTask.fork()` to pass task when creating PromotionPoint

2. **Fix fork() to promote the correct (oldest) task** (CR-1)
   - Use the task reference from PromotionPoint to promote the right task
   - Add test verifying the oldest forked task is the one promoted

3. **Fix PromotionTracker.removeFrame() promotion side-effect** (CR-2, P-8)
   - Extract internal `removeTail()` from `promoteOldest()`
   - `removeFrame()` should not mark frames as promoted
   - Update statistics accounting

#### Race Conditions and Safety
4. **Fix promotedFuture race condition in join()** (CR-4)
   - Replace volatile field with AtomicReference or add synchronization
   - Prevent double-execution when promotion happens between null check and sequential exec

5. **Fix VirtualThreadExecutor shutdown safety** (CR-5, P-12)
   - Use AtomicBoolean for shutdown flag
   - Catch RejectedExecutionException
   - Implement AutoCloseable (P-3, CR-10)

#### Polling and Timing
6. **Make PollingStrategy configurable in HeartbeatConfig** (P-2, CR-6)
   - Add polling strategy factory to HeartbeatConfig.Builder
   - VirtualThreadExecutor.createContext() uses config's strategy
   - Default to a reasonable polling interval instead of every(1)

7. **Cache JDK ContinuationScope** (P-6, CR-7)
   - Store jdk.internal.vm.ContinuationScope as a field in ContinuationScope
   - Avoid allocation on every yield()
   - _Also relevant to Phase 7 (hot path optimization)_

#### Config Fixes
8. **Fix HeartbeatConfig.Builder order dependency** (CR-8, P-11)
   - Store targetOverheadPercent and resolve lazily in build()

#### Code Cleanup
9. **Remove dead else branch in HeartbeatTask.call()** (CR-12)

10. **Fix HeartbeatContinuation.hasYielded semantics** (CR-11)
    - Set flag before Continuation.yield(), not after resume

#### Test Coverage
11. **Add direct unit tests for HeartbeatContinuation** (P-4)
    - Test yield/resume cycles, isDone, error cases

12. **Add direct unit tests for CountBasedPolling and TimeBasedPolling** (P-5)
    - Edge cases, reset, boundary conditions

13. **Add promotion assertion to integration tests** (P-7)
    - Assert totalPromotions > 0 for deep recursive tasks

14. **Add test for fork() without HeartbeatContext** (P-9)
    - Verify IllegalStateException with clear message

15. **Add test for exception propagation through forked tasks** (P-10)
    - Exception in child task propagates through join()

---

### Phase R0: Repo Hygiene

Pre-flight cleanup before adding the compiler-side rewriter. The current README references `FibonacciExample` and `ParallelSumExample` running instructions but neither class exists on disk — first impression is broken.

16. **Add FibonacciExample**
    - `org.heartbeat.scheduler.examples.FibonacciExample`
    - `main(String[] args)` accepts `n`, executes `FibTask(n)` via `VirtualThreadExecutor`
    - Print result + executor statistics (promotions, tasks executed)

17. **Add ParallelSumExample**
    - `org.heartbeat.scheduler.examples.ParallelSumExample`
    - Recursive divide-and-conquer over an int array
    - Configurable size + threshold via args
    - Compare against sequential baseline; print speedup

18. **Add RecursiveSumExample**
    - `org.heartbeat.scheduler.examples.RecursiveSumExample`
    - Skewed split divide-and-conquer (e.g., (n-1) and 1) for testing promotion fairness
    - Print final value + promotion stats

19. **Update README running instructions**
    - Fix classpath references to use `org.heartbeat.scheduler.examples.*`
    - Add JVM `--add-exports` flag to example invocations
    - Verify all README commands work end-to-end

---

### Phase R1: Compiler-side — PRC Bytecode Rewriter

The headline portfolio artifact. Auto-inserts `HeartbeatContext.checkHeartbeatStatic()` calls at method entries and loop backedges of `@Parallel` methods. Implements the paper's Promotion-Ready Code transformation in modern Java tooling. Design note: `docs/prc-rewriter.md` (task 27).

20. **Define @Parallel and @HeartbeatPoll annotations**
    - `org.heartbeat.scheduler.annotations.Parallel`
    - `org.heartbeat.scheduler.annotations.HeartbeatPoll` with `every()` parameter
    - `RetentionPolicy.CLASS` so they survive into bytecode

21. **Add static checkHeartbeat helper**
    - `HeartbeatContext.checkHeartbeatStatic()`: cheap entry point for rewritten code
    - No-op when no context is set (instrumented code remains safe outside the executor)

22. **Implement BackedgeAnalyzer**
    - `org.heartbeat.scheduler.agent.BackedgeAnalyzer`
    - ASM-based control-flow analysis on basic blocks
    - Compute dominator tree; identify backedges (u→v where v dominates u)
    - Return bytecode offsets at which to insert polls

23. **Implement PrcRewriter**
    - `org.heartbeat.scheduler.agent.PrcRewriter`
    - ASM ClassNode/MethodNode walker
    - Insert `INVOKESTATIC` at method entry of `@Parallel` methods
    - Insert `INVOKESTATIC` at backedges from BackedgeAnalyzer
    - Use `ClassWriter.COMPUTE_FRAMES` to avoid stack-map breakage

24. **Implement Java agent**
    - `org.heartbeat.scheduler.agent.PrcAgent` with `premain` entry point
    - `org.heartbeat.scheduler.agent.PrcClassTransformer` implementing `ClassFileTransformer`
    - Skip non-`@Parallel` methods quickly (annotation prefilter)
    - Configure jar manifest with `Premain-Class`

25. **Add ASM-tree unit tests for PrcRewriter**
    - Verify rewritten bytecode passes `java -Xverify:all`
    - Verify INVOKESTATIC inserted at expected offsets
    - Verify StackMapTable and exception tables preserved
    - Cover edge cases (no loops, nested loops, try/catch in loops)

26. **Add agent integration test**
    - Run `FibonacciExample` under the agent without explicit `checkHeartbeat()` calls
    - Assert promotions occur (`HeartbeatContext.totalPromotions > 0`)
    - Differential: same result with and without the agent

27. **Write docs/prc-rewriter.md design note**
    - Basic-block dominator analysis explanation
    - Why backedges suffice (cite paper §3)
    - ASM tree-API tradeoffs; agent vs annotation processor

---

### Phase R2: Loom Delegation, Focused Benchmarks, JFR

Delegate work-stealing to Loom's carrier scheduler instead of building a custom Chase-Lev deque. Replace the original PBBS port plan with a focused 4-benchmark suite. Design note: `docs/loom-integration.md` (task 28).

28. **Write docs/loom-integration.md**
    - Why we delegate work-stealing to Loom (no custom deque)
    - Pinning, safepoints, JIT inlining, ScopedValue vs ThreadLocal

29. **Implement FibBench (JMH)**
    - Recursive Fibonacci, regular split, sanity baseline

30. **Implement QuicksortBench (JMH)**
    - Divide-and-conquer with skewed splits; tests promotion fairness

31. **Implement MatmulBench (JMH)**
    - Regular parfor-style; tests bulk parallelism

32. **Implement BfsBench (JMH)**
    - Irregular synthetic graph; tests load balancing under Loom

33. **Add JFR event source**
    - `org.heartbeat.scheduler.jfr.HeartbeatEvents`
    - PromotionEvent, PollCheckEvent, JoinBlockedEvent
    - Wire emission into HeartbeatContext / VirtualThreadExecutor

34. **Implement scripts/visualize-jfr.py**
    - Parse JFR JSON output
    - Emit Gantt-style chart of promotions per carrier thread over time

---

### Phase R3: Empirical Verification + Writeup

Verify the (1+τ/N)·w bound experimentally; compare against ForkJoinPool / plain virtual threads / sequential; write portfolio-quality README.

35. **Configure JMH source set in pom.xml**
    - Add jmh-maven-plugin or jmh-generator-annprocess
    - Standard 5 warmup / 10 measurement / fork=3 / blackhole

36. **Implement BoundsBench (τ/N sweep)**
    - Sweep N over {2τ, 5τ, 10τ, 20τ, 50τ, 100τ}
    - Measure W, w, S, s, promotion count, steal count

37. **Implement comparative benchmark harness**
    - Heartbeat scheduler vs ForkJoinPool vs plain VTs vs sequential
    - Scalability over carrier counts {1, 2, 4, 8, 16}

38. **Implement scripts/plot-results.py**
    - Generate W/w vs (1+τ/N) plot
    - Generate scalability curves
    - Output PNG to docs/results/

39. **Rewrite README for portfolio quality**
    - Algorithm explanation with diagram
    - Quickstart (build, run example, run agent-instrumented example)
    - Loom integration design notes link
    - PRC rewriter section with before/after bytecode snippet
    - Empirical findings + key plot
    - Future work appendix

40. **Record demo GIF**
    - JMC visualizing Fibonacci with promotions firing
    - Embed in README

---

## Progress

| # | Task | Status | Commit |
|---|------|--------|--------|
| - | Implement Phase 4 (VirtualThreadExecutor, fork/join, tests) | done | 7966946 |
| 1 | Store task reference in PromotionPoint | done | 927eb9b |
| 2 | Fix fork() to promote correct task | done | 8515039 |
| 3 | Fix removeFrame() promotion side-effect | done | 7cc1cb6 |
| 4 | Fix promotedFuture race condition | done | add43b2 |
| 5 | Fix shutdown safety + AutoCloseable | done | c6ee12d |
| 6 | Make PollingStrategy configurable | done | a3a28af |
| 7 | Cache JDK ContinuationScope | done | eb97699 |
| 8 | Fix config builder order dependency | done | 0fe14ca |
| 9 | Remove dead else branch | done | 3a45afb |
| 10 | Fix hasYielded semantics | done | 7ca2b0a |
| 11 | Add HeartbeatContinuation tests | done | 5b39757 |
| 12 | Add polling strategy tests | done | 50ccfff |
| 13 | Add promotion assertion to integration tests | done | 6e97804 |
| 14 | Add fork-without-context test | done | 6d11f95 |
| 15 | Add exception propagation test | done | 6d11f95 |
| 16 | Add FibonacciExample | done | 1b1357d |
| 17 | Add ParallelSumExample | done | 715588b |
| 18 | Add RecursiveSumExample | done | ee06b22 |
| 19 | Update README running instructions | done | 54788c6 |
| 20 | Define @Parallel and @HeartbeatPoll annotations | done | 231185c |
| 21 | Add static checkHeartbeat helper | done | 231185c |
| 22 | Implement BackedgeAnalyzer | done | 231185c |
| 23 | Implement PrcRewriter | done | 231185c |
| 24 | Implement Java agent (PrcAgent + transformer) | done | 231185c |
| 25 | Add ASM-tree unit tests for PrcRewriter | done | 231185c |
| 26 | Add agent integration test | done | 231185c |
| 27 | Write docs/prc-rewriter.md design note | done | 231185c |
| 28 | Write docs/loom-integration.md | done | f63d051 |
| 29 | Implement FibBench (JMH) | done | da6c9fb |
| 30 | Implement QuicksortBench (JMH) | done | da6c9fb |
| 31 | Implement MatmulBench (JMH) | done | da6c9fb |
| 32 | Implement BfsBench (JMH) | done | da6c9fb |
| 33 | Add JFR event source | done | f63d051 |
| 34 | Implement scripts/visualize-jfr.py | done | f63d051 |
| 35 | Configure JMH source set in pom.xml | done | d16af82 |
| 36 | Implement BoundsBench (τ/N sweep) | done | a2b9f80 |
| 37 | Implement comparative benchmark harness | done | 2f1a8df |
| 38 | Implement scripts/plot-results.py | done | 00693e2 |
| 39 | Rewrite README for portfolio quality | done | 06a07b3 |
| 40 | Record demo GIF | pending | |
