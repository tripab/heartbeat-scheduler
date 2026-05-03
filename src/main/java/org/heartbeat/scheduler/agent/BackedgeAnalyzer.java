package org.heartbeat.scheduler.agent;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
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
        List<Set<Integer>> succs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) succs.add(new HashSet<>());

        for (int i = 0; i < n; i++) {
            AbstractInsnNode insn = insns.get(i);
            int op = insn.getOpcode();
            if (op == -1) {
                // pseudo-instruction (label, frame, line): fall through
                if (i + 1 < n) succs.get(i).add(i + 1);
                continue;
            }
            // Unconditional jumps
            if (op == GOTO) {
                LabelNode target = ((JumpInsnNode) insn).label;
                Integer ti = labelIndex.get(target);
                if (ti != null) succs.get(i).add(ti);
                // no fall-through
                continue;
            }
            // Returns and throws — no successors
            if (isReturn(op) || op == ATHROW) continue;
            // Table/lookup switch — handled via non-JumpInsnNode; fall through for now
            // (loops through switch are rare in heartbeat-annotated code; we conservatively
            // skip them here — the poll at method entry is always present regardless)
            // Conditional jumps
            if (insn instanceof JumpInsnNode ji) {
                Integer ti = labelIndex.get(ji.label);
                if (ti != null) succs.get(i).add(ti);
                // fall-through
                if (i + 1 < n) succs.get(i).add(i + 1);
                continue;
            }
            // Everything else falls through
            if (i + 1 < n) succs.get(i).add(i + 1);
        }

        // Compute dominators using the iterative bit-set algorithm.
        // dom[i] = set of nodes that dominate node i.
        // dom[0] = {0}; dom[i] = {i} ∪ (∩ dom[p] for p in preds[i])
        // We represent dom as a List<Set<Integer>> for clarity; for large methods
        // a bit-set would be faster but method sizes are small.

        // Build predecessor list
        List<Set<Integer>> preds = new ArrayList<>(n);
        for (int i = 0; i < n; i++) preds.add(new HashSet<>());
        for (int i = 0; i < n; i++) {
            for (int s : succs.get(i)) preds.get(s).add(i);
        }

        // Initialise doms
        List<Set<Integer>> dom = new ArrayList<>(n);
        // Entry: dom[0] = {0}
        Set<Integer> entry = new HashSet<>();
        entry.add(0);
        dom.add(entry);
        // All others: dom[i] = universe (all nodes)
        for (int i = 1; i < n; i++) {
            Set<Integer> all = new HashSet<>();
            for (int j = 0; j < n; j++) all.add(j);
            dom.add(all);
        }

        // Iterate until fixed point
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 1; i < n; i++) {
                Set<Integer> newDom = null;
                for (int p : preds.get(i)) {
                    if (newDom == null) {
                        newDom = new HashSet<>(dom.get(p));
                    } else {
                        newDom.retainAll(dom.get(p));
                    }
                }
                if (newDom == null) newDom = new HashSet<>();
                newDom.add(i);
                if (!newDom.equals(dom.get(i))) {
                    dom.set(i, newDom);
                    changed = true;
                }
            }
        }

        // Collect backedge indices: a jump at index i to target t is a backedge
        // iff t ∈ dom[i] (target dominates source).
        Set<Integer> backedges = new HashSet<>();
        for (int i = 0; i < n; i++) {
            AbstractInsnNode insn = insns.get(i);
            if (!(insn instanceof JumpInsnNode ji)) continue;
            Integer ti = labelIndex.get(ji.label);
            if (ti == null) continue;
            if (dom.get(i).contains(ti)) {
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

    private static boolean isReturn(int op) {
        return op == RETURN || op == IRETURN || op == LRETURN
                || op == FRETURN || op == DRETURN || op == ARETURN;
    }
}
