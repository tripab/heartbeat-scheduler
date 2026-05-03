package org.heartbeat.scheduler.agent;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/**
 * Identifies loop backedges in a method's control-flow graph.
 *
 * A backedge is an edge (u → v) in the CFG where v dominates u — i.e., every
 * path from the method entry to u passes through v. This is the standard
 * definition (Aho/Sethi/Ullman §10.4) and characterises exactly the edges
 * that close a loop.
 *
 * Algorithm:
 *  1. Build a basic-block CFG from the instruction list.
 *  2. Compute dominators using the iterative data-flow algorithm
 *     (Cooper/Harvey/Kennedy, SAS 2001 — simpler and fast enough for
 *     the method sizes we encounter).
 *  3. An edge (u → v) is a backedge iff v is in Dom(u).
 *  4. Return the instruction index (within MethodNode.instructions) of the
 *     jump that forms each backedge; the rewriter inserts a poll just before
 *     that instruction.
 */
public class BackedgeAnalyzer {

    private BackedgeAnalyzer() {}

    /**
     * Returns the indices (into {@code mn.instructions}) of all backedge jumps.
     * The returned set may be empty if the method contains no loops.
     */
    public static Set<Integer> findBackedgeIndices(MethodNode mn) {
        List<AbstractInsnNode> insns = toList(mn);
        if (insns.isEmpty()) return Set.of();

        // Map each LabelNode to its index in the instruction list.
        Map<LabelNode, Integer> labelIndex = new HashMap<>();
        for (int i = 0; i < insns.size(); i++) {
            if (insns.get(i) instanceof LabelNode ln) {
                labelIndex.put(ln, i);
            }
        }

        // Build CFG: for each instruction index, the set of successor indices.
        int n = insns.size();
        List<BitSet> succs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) succs.add(new BitSet(n));

        for (int i = 0; i < n; i++) {
            AbstractInsnNode insn = insns.get(i);
            int op = insn.getOpcode();
            if (op == -1) {
                // pseudo-instruction (label, frame, line): fall through
                if (i + 1 < n) succs.get(i).set(i + 1);
                continue;
            }
            // Unconditional jumps
            if (op == GOTO) {
                LabelNode target = ((JumpInsnNode) insn).label;
                Integer ti = labelIndex.get(target);
                if (ti != null) succs.get(i).set(ti);
                // no fall-through
                continue;
            }
            // Returns and throws — no successors
            if (isReturn(op) || op == ATHROW) continue;
            if (insn instanceof LookupSwitchInsnNode lookupSwitch) {
                addLabelSuccessor(succs.get(i), labelIndex, lookupSwitch.dflt);
                for (LabelNode label : lookupSwitch.labels) {
                    addLabelSuccessor(succs.get(i), labelIndex, label);
                }
                continue;
            }
            if (insn instanceof TableSwitchInsnNode tableSwitch) {
                addLabelSuccessor(succs.get(i), labelIndex, tableSwitch.dflt);
                for (LabelNode label : tableSwitch.labels) {
                    addLabelSuccessor(succs.get(i), labelIndex, label);
                }
                continue;
            }
            // Conditional jumps
            if (insn instanceof JumpInsnNode ji) {
                Integer ti = labelIndex.get(ji.label);
                if (ti != null) succs.get(i).set(ti);
                // fall-through
                if (i + 1 < n) succs.get(i).set(i + 1);
                continue;
            }
            // Everything else falls through
            if (i + 1 < n) succs.get(i).set(i + 1);
        }

        // Compute dominators using the iterative bit-set algorithm.
        // dom[i] = set of nodes that dominate node i.
        // dom[0] = {0}; dom[i] = {i} ∪ (∩ dom[p] for p in preds[i])

        // Build predecessor list
        List<BitSet> preds = new ArrayList<>(n);
        for (int i = 0; i < n; i++) preds.add(new BitSet(n));
        for (int i = 0; i < n; i++) {
            for (int s = succs.get(i).nextSetBit(0); s >= 0; s = succs.get(i).nextSetBit(s + 1)) {
                preds.get(s).set(i);
            }
        }

        // Initialise doms
        List<BitSet> dom = new ArrayList<>(n);
        // Entry: dom[0] = {0}
        BitSet entry = new BitSet(n);
        entry.set(0);
        dom.add(entry);
        // All others: dom[i] = universe (all nodes)
        for (int i = 1; i < n; i++) {
            BitSet all = new BitSet(n);
            all.set(0, n);
            dom.add(all);
        }

        // Iterate until fixed point
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 1; i < n; i++) {
                BitSet newDom = null;
                for (int p = preds.get(i).nextSetBit(0); p >= 0; p = preds.get(i).nextSetBit(p + 1)) {
                    if (newDom == null) {
                        newDom = (BitSet) dom.get(p).clone();
                    } else {
                        newDom.and(dom.get(p));
                    }
                }
                if (newDom == null) newDom = new BitSet(n);
                newDom.set(i);
                if (!newDom.equals(dom.get(i))) {
                    dom.set(i, newDom);
                    changed = true;
                }
            }
        }

        // Collect backedge indices: a branch at index i to target t is a backedge
        // iff t ∈ dom[i] (target dominates source).
        Set<Integer> backedges = new HashSet<>();
        for (int i = 0; i < n; i++) {
            AbstractInsnNode insn = insns.get(i);
            if (isBackedgeJump(insn, dom.get(i), labelIndex)) {
                backedges.add(i);
            }
        }
        return backedges;
    }

    // -------------------------------------------------------------------------

    private static List<AbstractInsnNode> toList(MethodNode mn) {
        List<AbstractInsnNode> list = new ArrayList<>();
        for (AbstractInsnNode insn : mn.instructions) list.add(insn);
        return list;
    }

    private static void addLabelSuccessor(BitSet succs, Map<LabelNode, Integer> labelIndex, LabelNode label) {
        Integer target = labelIndex.get(label);
        if (target != null) succs.set(target);
    }

    private static boolean isBackedgeJump(
            AbstractInsnNode insn,
            BitSet sourceDom,
            Map<LabelNode, Integer> labelIndex) {
        if (insn instanceof JumpInsnNode jump) {
            return targetDominatesSource(sourceDom, labelIndex, jump.label);
        }
        if (insn instanceof LookupSwitchInsnNode lookupSwitch) {
            if (targetDominatesSource(sourceDom, labelIndex, lookupSwitch.dflt)) return true;
            for (LabelNode label : lookupSwitch.labels) {
                if (targetDominatesSource(sourceDom, labelIndex, label)) return true;
            }
        }
        if (insn instanceof TableSwitchInsnNode tableSwitch) {
            if (targetDominatesSource(sourceDom, labelIndex, tableSwitch.dflt)) return true;
            for (LabelNode label : tableSwitch.labels) {
                if (targetDominatesSource(sourceDom, labelIndex, label)) return true;
            }
        }
        return false;
    }

    private static boolean targetDominatesSource(
            BitSet sourceDom,
            Map<LabelNode, Integer> labelIndex,
            LabelNode label) {
        Integer target = labelIndex.get(label);
        return target != null && sourceDom.get(target);
    }

    private static boolean isReturn(int op) {
        return op == RETURN || op == IRETURN || op == LRETURN
                || op == FRETURN || op == DRETURN || op == ARETURN;
    }
}
