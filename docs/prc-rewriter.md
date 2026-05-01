# PRC Bytecode Rewriter — Design Note

## 1. What is PRC?

The Heartbeat Scheduling paper (Acar et al., PLDI 2018) proves that if a parallel
program is *Promotion-Ready Code* (PRC), then the total work performed by the
heartbeat executor is bounded by `(1 + τ/N) · w`, where `w` is the sequential
work, `τ` is the heartbeat period, and `N` is the number of carrier threads.

PRC is a structural property of the compiled code: every execution prefix of length
`N` instructions must contain at least one *poll point* — a site where the runtime
can check whether the current task should be promoted to a parallel thread.

In the original paper this property is guaranteed by a compile-time pass that
inserts poll calls into the generated code. This document describes how we
implement that pass for Java bytecode using ObjectWeb ASM.

---

## 2. Where polls are inserted (and why those points suffice)

A poll only needs to appear at points where an unbounded amount of work can be
hidden from the scheduler. Straight-line code — a sequence of instructions with no
branches — has a static, bounded length, so one poll at the entry of a basic block
suffices for the entire block. The only places where execution can repeat
indefinitely are:

- **Loops**: the same basic block is executed repeatedly via a back edge.
- **Recursive calls**: the call stack deepens, and each frame may contain a loop.

Therefore, inserting polls at **method entry** and at **loop back edges** is
sufficient to ensure PRC. Every possible execution prefix of length `N` must
traverse either a method entry or a back edge.

This is cited in §3 of the paper and is equivalent to the standard "safepoint poll"
placement used by the HotSpot JVM and other production compilers.

---

## 3. Basic-block CFG and dominator analysis

The back-edge detection in `BackedgeAnalyzer` uses the standard textbook definition:

> An edge `(u → v)` in the control-flow graph is a **back edge** if and only if
> `v` dominates `u` — that is, every path from the method entry to `u` passes
> through `v`.

### 3.1 CFG construction

We walk `MethodNode.instructions` linearly. For each instruction we compute its
successor(s):

| Instruction type | Successors |
|---|---|
| `GOTO` | Target label only |
| Conditional jump (`IF_*`, `IF_ICMP*`, etc.) | Target label + fall-through |
| `RETURN`, `ATHROW` | None |
| Everything else | Fall-through |

Label, frame, and line-number pseudo-instructions pass control to the next real
instruction.

### 3.2 Dominator computation

We use the iterative data-flow algorithm from Cooper, Harvey & Kennedy
(*A Simple, Fast Dominance Algorithm*, SAS 2001):

```
dom[0] = {0}                        // entry dominates itself only
dom[i] = {i} ∪ (∩ dom[p] for all predecessors p of i)   // i ≥ 1
```

Iterate until a fixed point. This converges in `O(n²)` in the worst case, but
for the method sizes we encounter (typically < 200 instructions) it terminates
in a handful of passes.

### 3.3 Back-edge identification

After convergence, an edge `(i → t)` emitted by a jump instruction at index `i`
is a back edge iff `t ∈ dom[i]`.

---

## 4. The rewrite pass (`PrcRewriter`)

`PrcRewriter.rewrite()` operates on raw class-file bytes:

1. Parse with `ClassReader` into a `ClassNode` (ASM Tree API).
2. For each `MethodNode`, check for a `@Parallel` annotation (descriptor
   `Lorg/heartbeat/scheduler/annotations/Parallel;`, invisible at runtime —
   `RetentionPolicy.CLASS`).
3. If found, call `BackedgeAnalyzer.findBackedgeIndices(mn)` to get the set of
   back-edge jump indices.
4. Insert `INVOKESTATIC HeartbeatContext.checkHeartbeatStatic()Z` + `POP` before
   each back-edge instruction (iterating in reverse index order so earlier
   insertions don't shift later indices).
5. Prepend the same sequence before the first real instruction (skipping past
   initial pseudo-instructions so debuggers see the entry correctly).
6. Serialize with `ClassWriter(COMPUTE_FRAMES)` — see §5.

The injected sequence:

```
INVOKESTATIC org/heartbeat/scheduler/core/HeartbeatContext.checkHeartbeatStatic()Z
POP
```

The boolean return value is discarded. The promotion side-effect (yielding the
continuation) happens *inside* `checkHeartbeatStatic()` when the heartbeat timer
fires.

---

## 5. Why `ClassWriter.COMPUTE_FRAMES`?

Java class files require a `StackMapTable` attribute in every method that contains
branches (since Java 6 / class format version 50). Each entry records the exact
types on the operand stack and in local variable slots at each branch target.

Inserting instructions shifts offsets and changes the stack shape at some targets.
Rather than manually updating the stack-map table (error-prone and brittle),
we delegate to ASM's `COMPUTE_FRAMES` flag, which recomputes the table from
scratch via a dataflow analysis. The cost is one additional pass over the method,
which is negligible for the class sizes we instrument.

The alternative — `ClassWriter.COMPUTE_MAXS` — only recomputes `max_stack` and
`max_locals`, leaving the stack map untouched. That would break verification
whenever we insert a poll that shifts targets.

---

## 6. The Java agent

`PrcAgent.premain()` registers a `ClassFileTransformer` (`PrcClassTransformer`)
with the JVM instrumentation API. The transformer is invoked by the JVM for every
class loaded after agent attachment.

Fast-path filtering: `PrcClassTransformer` skips JDK internal packages
(`java/`, `jdk/`, `sun/`, etc.) and the agent package itself to avoid infinite
recursion and JVM instability. For all other classes, `PrcRewriter.rewrite()`
performs a `ClassReader` scan; if no `@Parallel` methods are found, the original
byte array is returned unchanged (the `ClassWriter` is never allocated).

Agent arguments (comma-separated, passed via `-javaagent:...jar=arg1,arg2`):

| Argument | Effect |
|---|---|
| `verbose` | Print each instrumented class name to stderr |

---

## 7. ASM Tree API vs Visitor API

The rewriter uses the ASM **Tree API** (`ClassNode`, `MethodNode`,
`AbstractInsnNode`) rather than the visitor API. This simplifies the dominator
analysis: we need random access to instructions by index and must insert
instructions at arbitrary positions. The visitor API is event-driven (streaming)
and does not support random access. The Tree API materialises the entire method
in memory, which is fine for the method sizes we instrument.

---

## 8. Agent vs annotation processor

An annotation processor (`javac -processor`) could perform the same transformation
at compile time — reading the source AST and emitting modified bytecode or a new
source file. We chose the Java agent approach for three reasons:

1. **No build-system integration required.** A `-javaagent:` flag works regardless
   of the build tool or compilation pipeline.
2. **Operates on bytecode, not source.** This demonstrates bytecode IR
   understanding directly — the central portfolio claim.
3. **Works on pre-compiled dependencies.** If a library ships classes with
   `@Parallel` annotations, the agent instruments them at load time; a source-level
   processor cannot reach them.

The annotation processor path (an AOT alternative) is noted as future work.

---

## 9. Engineering tradeoffs and limitations

| Concern | Decision |
|---|---|
| `tableswitch`/`lookupswitch` back edges | Not analysed; loops through switch are uncommon in heartbeat-annotated code and the entry poll always fires. |
| Exception handler back edges | Included: a `GOTO` from a catch block back to a loop header is a standard back edge and is detected. |
| Inlining of `checkHeartbeatStatic` | HotSpot may inline the call after warmup; `-XX:+PrintInlining` can verify this. The call is intentionally short. |
| Agent classloader isolation | The agent jar is loaded on the bootstrap classloader by the JVM; ASM classes are shaded in to avoid version conflicts. |
| `RetentionPolicy.CLASS` annotations | Annotations are *invisible* at runtime (not in `visibleAnnotations`) but present in the class file (`invisibleAnnotations` in ASM). PrcRewriter checks both. |

---

## References

- Acar, Chargueraud, Rainey, Sieczkowski — *Heartbeat Scheduling* (PLDI 2018)
- Cooper, Harvey, Kennedy — *A Simple, Fast Dominance Algorithm* (SAS 2001)
- Aho, Sethi, Ullman — *Compilers: Principles, Techniques, and Tools* §10.4
- ObjectWeb ASM documentation: <https://asm.ow2.io/>
- JEP 451 / JEP 429 — `java.lang.instrument` and Java agents
