package org.heartbeat.scheduler.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

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

    /** The static poll method the rewriter injects calls to. */
    static final String CONTEXT_OWNER =
            "org/heartbeat/scheduler/core/HeartbeatContext";
    static final String CHECK_METHOD = "checkHeartbeatStatic";
    static final String CHECK_DESC   = "()Z";

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
     * Strategy: insert before each backedge jump and at method entry.
     * We insert a poll that returns a boolean but we discard the value (POP).
     * The actual promotion side-effect happens inside checkHeartbeatStatic()
     * via HeartbeatTask.yield().
     *
     * Insert order: backedges first (using indices from the unmodified list),
     * then prepend the entry poll. This avoids invalidating the backedge
     * indices while inserting the entry poll.
     */
    private static void rewriteMethod(MethodNode mn) {
        Set<Integer> backedgeIndices = BackedgeAnalyzer.findBackedgeIndices(mn);

        // Convert instruction list to an array snapshot so indices remain stable.
        AbstractInsnNode[] insns = mn.instructions.toArray();

        // Insert poll before each backedge jump (iterate in reverse order so
        // insertion doesn't shift later indices).
        int[] sorted = backedgeIndices.stream().mapToInt(Integer::intValue)
                .boxed().sorted((a, b) -> b - a).mapToInt(Integer::intValue).toArray();
        for (int idx : sorted) {
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
}
