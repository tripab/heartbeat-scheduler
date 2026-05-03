package org.heartbeat.scheduler.agent;

import org.heartbeat.scheduler.core.HeartbeatContext;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * PRC (Promotion-Ready Code) bytecode rewriter.
 *
 * Walks the bytecode of @Parallel-annotated methods and inserts calls to
 * HeartbeatContext.checkHeartbeatStatic() at:
 *  - Method entry (first instruction of the first basic block)
 *  - Loop backedges (identified by BackedgeAnalyzer)
 *
 * Uses ClassWriter.COMPUTE_FRAMES to avoid manual stack-map management.
 */
public class PrcRewriter {

    /** Internal name of @Parallel, as it appears in class-file descriptors. */
    private static final String PARALLEL_DESC =
            "Lorg/heartbeat/scheduler/annotations/Parallel;";

    /** Internal name of @HeartbeatPoll, as it appears in class-file descriptors. */
    private static final String HEARTBEAT_POLL_DESC =
            "Lorg/heartbeat/scheduler/annotations/HeartbeatPoll;";

    /** The static poll method the rewriter injects calls to. */
    private static final PollTarget CHECK_HEARTBEAT =
            PollTarget.from(HeartbeatContext::checkHeartbeatStatic);
    static final String CONTEXT_OWNER = CHECK_HEARTBEAT.owner();
    static final String CHECK_METHOD = CHECK_HEARTBEAT.name();
    static final String CHECK_DESC = CHECK_HEARTBEAT.descriptor();

    // -------------------------------------------------------------------------

    /**
     * Rewrite {@code classBytes} if it contains any @Parallel methods.
     *
     * @param classBytes  Original class bytes
     * @param classLoader ClassLoader for frame-computation (may be null)
     * @return Rewritten bytes, or the original array if no @Parallel methods found
     */
    public byte[] rewrite(byte[] classBytes, ClassLoader classLoader) {
        ClassReader cr = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        boolean modified = false;
        for (MethodNode mn : cn.methods) {
            if (hasParallelAnnotation(mn)) {
                rewriteMethod(mn);
                modified = true;
            }
        }
        if (!modified) return classBytes;

        // COMPUTE_FRAMES recomputes stack maps from scratch — no manual frame work needed.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return classLoader != null ? classLoader
                        : Thread.currentThread().getContextClassLoader();
            }
        };
        cn.accept(cw);
        return cw.toByteArray();
    }

    // -------------------------------------------------------------------------

    private static boolean hasParallelAnnotation(MethodNode mn) {
        if (mn.visibleAnnotations != null) {
            for (var ann : mn.visibleAnnotations)
                if (PARALLEL_DESC.equals(ann.desc)) return true;
        }
        if (mn.invisibleAnnotations != null) {
            for (var ann : mn.invisibleAnnotations)
                if (PARALLEL_DESC.equals(ann.desc)) return true;
        }
        return false;
    }

    /**
     * Insert poll calls into a single method.
     *
     * Strategy: insert before each selected backedge jump and at method entry.
     * We insert a poll that returns a boolean but we discard the value (POP).
     * The actual promotion side-effect happens inside checkHeartbeatStatic()
     * via HeartbeatTask.yield().
     *
     * If the method carries {@code @HeartbeatPoll(every=N)}, only every N-th
     * backedge (0-indexed, ascending CFG order) receives a poll — reducing
     * polling overhead in tight inner loops. With N=1 (the default) all
     * backedges are polled, preserving existing behaviour.
     *
     * Insert order: backedges first (using indices from the unmodified list),
     * then prepend the entry poll. This avoids invalidating the backedge
     * indices while inserting the entry poll.
     */
    private static void rewriteMethod(MethodNode mn) {
        Set<Integer> backedgeIndices = BackedgeAnalyzer.findBackedgeIndices(mn);
        int everyN = getEveryN(mn);

        // Convert instruction list to an array snapshot so indices remain stable.
        AbstractInsnNode[] insns = mn.instructions.toArray();

        // Sort ascending to apply the every-N selection in CFG order, then
        // descend for safe in-place insertion (later indices first).
        int[] ascending = backedgeIndices.stream().mapToInt(Integer::intValue).sorted().toArray();
        List<Integer> selected = new ArrayList<>();
        for (int i = 0; i < ascending.length; i++) {
            if (i % everyN == 0) selected.add(ascending[i]);
        }
        selected.sort(Comparator.reverseOrder());
        for (int idx : selected) {
            mn.instructions.insertBefore(insns[idx], pollSequence());
        }

        // Prepend entry poll (after any initial labels/frames so debuggers see the entry).
        AbstractInsnNode first = mn.instructions.getFirst();
        while (first != null && first.getOpcode() == -1) {
            first = first.getNext();
        }
        if (first != null) {
            mn.instructions.insertBefore(first, pollSequence());
        } else {
            mn.instructions.insert(pollSequence());
        }
    }

    /**
     * Returns the {@code @HeartbeatPoll(every=N)} value for the given method,
     * or 1 if the annotation is absent (poll every backedge).
     */
    private static int getEveryN(MethodNode mn) {
        int result = findEveryN(mn.visibleAnnotations);
        if (result >= 0) return result;
        result = findEveryN(mn.invisibleAnnotations);
        if (result >= 0) return result;
        return 1; // annotation absent — poll every backedge
    }

    /** Returns the every=N value if found in {@code anns}, or -1 if not present. */
    private static int findEveryN(List<AnnotationNode> anns) {
        if (anns == null) return -1;
        for (AnnotationNode ann : anns) {
            if (HEARTBEAT_POLL_DESC.equals(ann.desc)) {
                if (ann.values != null) {
                    for (int i = 0; i + 1 < ann.values.size(); i += 2) {
                        if ("every".equals(ann.values.get(i))) {
                            Object v = ann.values.get(i + 1);
                            if (v instanceof Integer n) return Math.max(1, n);
                        }
                    }
                }
                return 1; // annotation present, no explicit value — use default
            }
        }
        return -1;
    }

    /**
     * Returns the instruction sequence for a single poll site:
     *   INVOKESTATIC HeartbeatContext.checkHeartbeatStatic()Z
     *   POP
     *
     * We discard the boolean return value; the promotion side-effect is
     * handled inside checkHeartbeatStatic → checkHeartbeat → HeartbeatTask.yield().
     */
    private static InsnList pollSequence() {
        InsnList il = new InsnList();
        il.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                CONTEXT_OWNER,
                CHECK_METHOD,
                CHECK_DESC,
                false));
        il.add(new InsnNode(Opcodes.POP));
        return il;
    }

    @FunctionalInterface
    private interface PollProbe extends Serializable {
        boolean invoke();
    }

    private record PollTarget(String owner, String name, String descriptor) {
        static PollTarget from(PollProbe probe) {
            SerializedLambda lambda = serializedLambda(probe);
            return new PollTarget(
                    lambda.getImplClass(),
                    lambda.getImplMethodName(),
                    lambda.getImplMethodSignature());
        }

        private static SerializedLambda serializedLambda(PollProbe probe) {
            try {
                Method writeReplace = probe.getClass().getDeclaredMethod("writeReplace");
                writeReplace.setAccessible(true);
                Object replacement = writeReplace.invoke(probe);
                if (replacement instanceof SerializedLambda lambda) {
                    return lambda;
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unable to resolve PRC poll target", e);
            }
            throw new IllegalStateException("PRC poll target did not serialize to a lambda");
        }
    }
}
