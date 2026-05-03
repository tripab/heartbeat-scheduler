package org.heartbeat.scheduler.agent;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.objectweb.asm.Opcodes.*;

/**
 * Unit tests for BackedgeAnalyzer, exercising the dominator-based backedge
 * detection algorithm directly on hand-built MethodNode CFGs.
 *
 * Each test constructs a MethodNode with a specific control-flow shape
 * (using ASM tree nodes), calls findBackedgeIndices, and asserts which
 * instruction indices are (or are not) returned as backedges.
 */
class BackedgeAnalyzerTest {

    /** Index of a specific instruction within mn.instructions (identity comparison). */
    private static int indexOf(MethodNode mn, AbstractInsnNode target) {
        int i = 0;
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn == target) return i;
            i++;
        }
        throw new AssertionError("instruction not found in method");
    }

    private static MethodNode emptyMethod() {
        return new MethodNode(0, "test", "()V", null, null);
    }

    // =========================================================================
    // Tests
    // =========================================================================

    /** MethodNode with no instructions at all returns empty set without throwing. */
    @Test
    void emptyMethodReturnsEmpty() {
        assertThat(BackedgeAnalyzer.findBackedgeIndices(emptyMethod())).isEmpty();
    }

    /** Straight-line method (no jumps) reports no backedges. */
    @Test
    void straightLineNoBackedges() {
        MethodNode mn = emptyMethod();
        mn.instructions.add(new InsnNode(ICONST_0));
        mn.instructions.add(new InsnNode(IRETURN));

        assertThat(BackedgeAnalyzer.findBackedgeIndices(mn)).isEmpty();
    }

    /** Forward conditional jump (if-else without loop) is not a backedge. */
    @Test
    void forwardConditionalJumpNoBackedges() {
        MethodNode mn = emptyMethod();
        LabelNode thenLabel = new LabelNode();
        LabelNode endLabel  = new LabelNode();

        mn.instructions.add(new InsnNode(ICONST_0));
        mn.instructions.add(new JumpInsnNode(IFEQ, thenLabel));
        mn.instructions.add(new InsnNode(ICONST_1));
        mn.instructions.add(new JumpInsnNode(GOTO, endLabel));
        mn.instructions.add(thenLabel);
        mn.instructions.add(new InsnNode(ICONST_2));
        mn.instructions.add(endLabel);
        mn.instructions.add(new InsnNode(RETURN));

        assertThat(BackedgeAnalyzer.findBackedgeIndices(mn)).isEmpty();
    }

    /** Simple while-loop with GOTO back: the GOTO is the only backedge. */
    @Test
    void gotoWhileLoopGotoIsBackedge() {
        MethodNode mn = emptyMethod();
        LabelNode loopLabel = new LabelNode();
        LabelNode endLabel  = new LabelNode();

        mn.instructions.add(new InsnNode(ICONST_0));
        mn.instructions.add(new VarInsnNode(ISTORE, 0));
        mn.instructions.add(loopLabel);
        mn.instructions.add(new VarInsnNode(ILOAD, 0));
        mn.instructions.add(new IntInsnNode(BIPUSH, 10));
        mn.instructions.add(new JumpInsnNode(IF_ICMPGE, endLabel));
        mn.instructions.add(new IincInsnNode(0, 1));
        JumpInsnNode gotoBack = new JumpInsnNode(GOTO, loopLabel);
        mn.instructions.add(gotoBack);
        mn.instructions.add(endLabel);
        mn.instructions.add(new InsnNode(RETURN));

        Set<Integer> backedges = BackedgeAnalyzer.findBackedgeIndices(mn);
        assertThat(backedges).containsExactly(indexOf(mn, gotoBack));
    }

    /** do-while loop with conditional backward jump: the conditional jump is a backedge. */
    @Test
    void doWhileLoopConditionalJumpIsBackedge() {
        MethodNode mn = emptyMethod();
        LabelNode loopLabel = new LabelNode();

        mn.instructions.add(new InsnNode(ICONST_0));
        mn.instructions.add(new VarInsnNode(ISTORE, 0));
        mn.instructions.add(loopLabel);
        mn.instructions.add(new IincInsnNode(0, 1));
        mn.instructions.add(new VarInsnNode(ILOAD, 0));
        mn.instructions.add(new IntInsnNode(BIPUSH, 10));
        JumpInsnNode condBack = new JumpInsnNode(IF_ICMPLT, loopLabel);
        mn.instructions.add(condBack);
        mn.instructions.add(new InsnNode(RETURN));

        Set<Integer> backedges = BackedgeAnalyzer.findBackedgeIndices(mn);
        assertThat(backedges).containsExactly(indexOf(mn, condBack));
    }

    /** Nested loops produce exactly two backedges — one per loop. */
    @Test
    void nestedLoopsTwoBackedges() {
        MethodNode mn = emptyMethod();
        LabelNode outerLoop = new LabelNode();
        LabelNode outerEnd  = new LabelNode();
        LabelNode innerLoop = new LabelNode();
        LabelNode innerEnd  = new LabelNode();

        mn.instructions.add(new InsnNode(ICONST_0));
        mn.instructions.add(new VarInsnNode(ISTORE, 0));
        mn.instructions.add(outerLoop);
        mn.instructions.add(new VarInsnNode(ILOAD, 0));
        mn.instructions.add(new IntInsnNode(BIPUSH, 5));
        mn.instructions.add(new JumpInsnNode(IF_ICMPGE, outerEnd));

        mn.instructions.add(new InsnNode(ICONST_0));
        mn.instructions.add(new VarInsnNode(ISTORE, 1));
        mn.instructions.add(innerLoop);
        mn.instructions.add(new VarInsnNode(ILOAD, 1));
        mn.instructions.add(new IntInsnNode(BIPUSH, 5));
        mn.instructions.add(new JumpInsnNode(IF_ICMPGE, innerEnd));
        mn.instructions.add(new IincInsnNode(1, 1));
        JumpInsnNode innerBack = new JumpInsnNode(GOTO, innerLoop);
        mn.instructions.add(innerBack);
        mn.instructions.add(innerEnd);
        mn.instructions.add(new IincInsnNode(0, 1));
        JumpInsnNode outerBack = new JumpInsnNode(GOTO, outerLoop);
        mn.instructions.add(outerBack);
        mn.instructions.add(outerEnd);
        mn.instructions.add(new InsnNode(RETURN));

        Set<Integer> backedges = BackedgeAnalyzer.findBackedgeIndices(mn);
        assertThat(backedges).containsExactlyInAnyOrder(
                indexOf(mn, innerBack),
                indexOf(mn, outerBack));
    }

    /**
     * LOOKUPSWITCH case-label edges are modelled in the CFG. A pure dispatch
     * with all forward targets still yields no backedges.
     */
    @Test
    void lookupSwitchNoBackedges() {
        MethodNode mn = new MethodNode(0, "test", "(I)V", null, null);
        LabelNode case0 = new LabelNode();
        LabelNode case1 = new LabelNode();
        LabelNode def   = new LabelNode();
        LabelNode end   = new LabelNode();

        mn.instructions.add(new VarInsnNode(ILOAD, 1));
        mn.instructions.add(new LookupSwitchInsnNode(def, new int[]{0, 1},
                new LabelNode[]{case0, case1}));
        mn.instructions.add(case0);
        mn.instructions.add(new JumpInsnNode(GOTO, end));
        mn.instructions.add(case1);
        mn.instructions.add(new JumpInsnNode(GOTO, end));
        mn.instructions.add(def);
        mn.instructions.add(end);
        mn.instructions.add(new InsnNode(RETURN));

        assertThat(BackedgeAnalyzer.findBackedgeIndices(mn)).isEmpty();
    }

    /** LOOKUPSWITCH with a case target back to the loop header is a backedge. */
    @Test
    void lookupSwitchBackToDominatingHeaderIsBackedge() {
        MethodNode mn = new MethodNode(0, "test", "(I)V", null, null);
        LabelNode loopHeader = new LabelNode();
        LabelNode end = new LabelNode();

        mn.instructions.add(new InsnNode(ICONST_0));
        mn.instructions.add(loopHeader);
        mn.instructions.add(new VarInsnNode(ILOAD, 1));
        LookupSwitchInsnNode lookupSwitch = new LookupSwitchInsnNode(
                end,
                new int[]{0},
                new LabelNode[]{loopHeader});
        mn.instructions.add(lookupSwitch);
        mn.instructions.add(end);
        mn.instructions.add(new InsnNode(RETURN));

        Set<Integer> backedges = BackedgeAnalyzer.findBackedgeIndices(mn);
        assertThat(backedges).containsExactly(indexOf(mn, lookupSwitch));
    }

    /** TABLESWITCH with a case target back to the loop header is a backedge. */
    @Test
    void tableSwitchBackToDominatingHeaderIsBackedge() {
        MethodNode mn = new MethodNode(0, "test", "(I)V", null, null);
        LabelNode loopHeader = new LabelNode();
        LabelNode end = new LabelNode();

        mn.instructions.add(new InsnNode(ICONST_0));
        mn.instructions.add(loopHeader);
        mn.instructions.add(new VarInsnNode(ILOAD, 1));
        TableSwitchInsnNode tableSwitch = new TableSwitchInsnNode(
                0,
                1,
                end,
                loopHeader,
                end);
        mn.instructions.add(tableSwitch);
        mn.instructions.add(end);
        mn.instructions.add(new InsnNode(RETURN));

        Set<Integer> backedges = BackedgeAnalyzer.findBackedgeIndices(mn);
        assertThat(backedges).containsExactly(indexOf(mn, tableSwitch));
    }

    /**
     * Exception handler entry points are not modelled as CFG predecessors
     * (TryCatchBlocks are ignored). Consequently:
     * - The main-path GOTO back to the loop header IS detected as a backedge.
     * - The handler's GOTO back to the same header is NOT detected, because the
     *   handler is unreachable in the modelled CFG and the loop header therefore
     *   does not appear in its dominator set.
     *
     * The method-entry poll provides coverage for the handler path.
     */
    @Test
    void exceptionHandlerMainBackedgeDetectedHandlerBackedgeSkipped() {
        MethodNode mn = emptyMethod();
        LabelNode loopLabel    = new LabelNode();
        LabelNode loopEnd      = new LabelNode();
        LabelNode tryStart     = new LabelNode();
        LabelNode tryEnd       = new LabelNode();
        LabelNode handlerLabel = new LabelNode();

        mn.instructions.add(new InsnNode(ICONST_0));
        mn.instructions.add(new VarInsnNode(ISTORE, 0));
        mn.instructions.add(loopLabel);
        mn.instructions.add(new VarInsnNode(ILOAD, 0));
        mn.instructions.add(new IntInsnNode(BIPUSH, 10));
        mn.instructions.add(new JumpInsnNode(IF_ICMPGE, loopEnd));
        mn.instructions.add(tryStart);
        mn.instructions.add(new IincInsnNode(0, 1));
        mn.instructions.add(tryEnd);
        JumpInsnNode mainBack = new JumpInsnNode(GOTO, loopLabel);
        mn.instructions.add(mainBack);
        mn.instructions.add(handlerLabel);
        mn.instructions.add(new InsnNode(POP));
        JumpInsnNode handlerBack = new JumpInsnNode(GOTO, loopLabel);
        mn.instructions.add(handlerBack);
        mn.instructions.add(loopEnd);
        mn.instructions.add(new InsnNode(RETURN));

        mn.tryCatchBlocks = new ArrayList<>();
        mn.tryCatchBlocks.add(
                new TryCatchBlockNode(tryStart, tryEnd, handlerLabel, "java/lang/Exception"));

        Set<Integer> backedges = BackedgeAnalyzer.findBackedgeIndices(mn);
        assertThat(backedges)
                .as("main-path GOTO back must be detected")
                .contains(indexOf(mn, mainBack));
        assertThat(backedges)
                .as("handler GOTO back is not detected — exception edges are not modelled")
                .doesNotContain(indexOf(mn, handlerBack));
    }

    /**
     * Irreducible CFG: a 2-node cycle (A↔B) where both nodes can be entered
     * from outside. Neither A nor B dominates the other, so the backward edge
     * B→A is not classified as a backedge by the dominator-based algorithm.
     *
     * <pre>
     *   entry
     *     IFNE ──────────────────► nodeB
     *     (fall-through) ─► nodeA
     *                           GOTO ──► nodeB
     *                                      GOTO ──► nodeA   ← cycle, NOT a backedge
     * </pre>
     *
     * This is expected: the dominator analysis only classifies natural-loop
     * (reducible) backedges. Irreducible loops are not instrumented; the
     * method-entry poll provides base coverage.
     */
    @Test
    void irreducibleCfgCycleEdgeNotABackedge() {
        MethodNode mn = new MethodNode(0, "test", "(I)V", null, null);
        LabelNode nodeA = new LabelNode();
        LabelNode nodeB = new LabelNode();
        LabelNode dead  = new LabelNode();

        mn.instructions.add(new VarInsnNode(ILOAD, 1));
        // IFNE: fall-through → nodeA, branch → nodeB (two entries into the cycle)
        mn.instructions.add(new JumpInsnNode(IFNE, nodeB));
        mn.instructions.add(nodeA);
        mn.instructions.add(new IincInsnNode(1, 1));
        mn.instructions.add(new JumpInsnNode(GOTO, nodeB));     // A → B
        mn.instructions.add(nodeB);
        mn.instructions.add(new IincInsnNode(1, -1));
        JumpInsnNode gotoA = new JumpInsnNode(GOTO, nodeA);     // B → A (closes cycle)
        mn.instructions.add(gotoA);
        mn.instructions.add(dead);
        mn.instructions.add(new InsnNode(RETURN));

        Set<Integer> backedges = BackedgeAnalyzer.findBackedgeIndices(mn);
        // nodeA does not dominate nodeB (nodeB is also reachable via the IFNE branch),
        // so B→A is not a backedge.
        assertThat(backedges)
                .as("B→A is not a backedge in an irreducible CFG (A does not dominate B)")
                .doesNotContain(indexOf(mn, gotoA));
    }
}
