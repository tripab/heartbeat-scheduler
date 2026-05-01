package org.heartbeat.scheduler.agent;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ASM-tree unit tests for PrcRewriter.
 *
 * Uses small in-process fixture classes (loaded as bytes via ClassLoader)
 * to test the rewriter without spawning a subprocess.
 */
class PrcRewriterTest {

    private final PrcRewriter rewriter = new PrcRewriter();

    // -------------------------------------------------------------------------
    // Helper: load raw bytes of a class compiled alongside this test.
    // -------------------------------------------------------------------------

    private byte[] bytesOf(Class<?> cls) throws Exception {
        String path = cls.getName().replace('.', '/') + ".class";
        try (var is = cls.getClassLoader().getResourceAsStream(path)) {
            assertThat(is).isNotNull();
            return is.readAllBytes();
        }
    }

    /** Count INVOKESTATIC calls to checkHeartbeatStatic in the rewritten class. */
    private int countPolls(byte[] bytes, String methodName) {
        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals(methodName)) {
                int count = 0;
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode mi
                            && mi.owner.equals(PrcRewriter.CONTEXT_OWNER)
                            && mi.name.equals(PrcRewriter.CHECK_METHOD)) {
                        count++;
                    }
                }
                return count;
            }
        }
        return -1; // method not found
    }

    /** Assert the rewritten bytes pass CheckClassAdapter (bytecode verifier). */
    private void assertVerifies(byte[] bytes) {
        StringWriter sw = new StringWriter();
        assertThatCode(() -> {
            ClassReader cr = new ClassReader(bytes);
            cr.accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        }).as("Rewritten bytecode must pass CheckClassAdapter").doesNotThrowAnyException();
    }

    // =========================================================================
    // Test fixtures (nested static classes annotated with @Parallel).
    // The @Parallel annotation has RetentionPolicy.CLASS, so it is present in
    // the class file but NOT visible at runtime via reflection. We therefore
    // need to rely on it surviving into the .class file (which it does by
    // definition of CLASS retention) so the rewriter can see it.
    //
    // To make the annotation visible to the rewriter we use a test-local
    // copy of the annotation with RetentionPolicy.RUNTIME for the fixture
    // classes, allowing us to test with real class bytes. Alternatively we
    // inline bytecode generation via ASM — but loading real compiled classes
    // is simpler to understand.
    //
    // Approach used here: generate fixture class bytes with ASM directly so
    // we control the annotation descriptor exactly. This avoids the RUNTIME vs
    // CLASS retention issue entirely.
    // =========================================================================

    /**
     * Build a class file byte array containing one method with the @Parallel
     * annotation (using the exact descriptor PrcRewriter checks).
     *
     * @param methodBody  a Consumer-style callback that populates the method
     */
    private byte[] buildFixtureClass(String className, FixtureBuilder body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);

        // default constructor
        MethodVisitorHelper mv0 = new MethodVisitorHelper(
                cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null));
        mv0.visitVarInsn(Opcodes.ALOAD, 0);
        mv0.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv0.visitInsn(Opcodes.RETURN);
        mv0.visitMaxs(1, 1);
        mv0.visitEnd();

        // annotated method
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "parallelMethod", "()V", null, null);
        // Add @Parallel invisible annotation (CLASS retention → invisible at runtime)
        mv.visitAnnotation("Lorg/heartbeat/scheduler/annotations/Parallel;", false).visitEnd();
        body.build(mv);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Small wrapper to avoid checked-exception noise. */
    private static class MethodVisitorHelper extends org.objectweb.asm.MethodVisitor {
        MethodVisitorHelper(org.objectweb.asm.MethodVisitor mv) { super(Opcodes.ASM9, mv); }
    }

    @FunctionalInterface
    interface FixtureBuilder {
        void build(org.objectweb.asm.MethodVisitor mv);
    }

    // =========================================================================
    // Tests
    // =========================================================================

    /** A method with no loops gets exactly one poll (at entry). */
    @Test
    void noLoop_entryPollInserted() {
        byte[] original = buildFixtureClass("TestNoLoop", mv -> {
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
        });

        byte[] rewritten = rewriter.rewrite(original, null);

        assertThat(rewritten).isNotSameAs(original);
        assertVerifies(rewritten);
        assertThat(countPolls(rewritten, "parallelMethod"))
                .as("no-loop method should get exactly 1 poll (entry)")
                .isEqualTo(1);
    }

    /** A method with a simple loop gets entry poll + backedge poll = 2 polls. */
    @Test
    void simpleLoop_backedgePollInserted() {
        byte[] original = buildFixtureClass("TestSimpleLoop", mv -> {
            // for (int i = 0; i < 10; i++) { /* empty */ }
            // Bytecode:
            //   ICONST_0
            //   ISTORE_0
            //   label_check:
            //   ILOAD_0
            //   BIPUSH 10
            //   IF_ICMPGE label_end
            //   IINC 0 1
            //   GOTO label_check    <-- backedge
            //   label_end:
            //   RETURN
            mv.visitCode();
            org.objectweb.asm.Label check = new org.objectweb.asm.Label();
            org.objectweb.asm.Label end = new org.objectweb.asm.Label();
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 0);
            mv.visitLabel(check);
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitIntInsn(Opcodes.BIPUSH, 10);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, end);
            mv.visitIincInsn(0, 1);
            mv.visitJumpInsn(Opcodes.GOTO, check); // backedge
            mv.visitLabel(end);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(2, 1);
        });

        byte[] rewritten = rewriter.rewrite(original, null);

        assertThat(rewritten).isNotSameAs(original);
        assertVerifies(rewritten);
        assertThat(countPolls(rewritten, "parallelMethod"))
                .as("one-loop method should get 2 polls: entry + backedge")
                .isEqualTo(2);
    }

    /** A method with nested loops gets entry + one poll per backedge. */
    @Test
    void nestedLoops_allBackedgesInstrumented() {
        byte[] original = buildFixtureClass("TestNestedLoops", mv -> {
            // for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) { }
            mv.visitCode();
            org.objectweb.asm.Label outerCheck = new org.objectweb.asm.Label();
            org.objectweb.asm.Label outerEnd   = new org.objectweb.asm.Label();
            org.objectweb.asm.Label innerCheck = new org.objectweb.asm.Label();
            org.objectweb.asm.Label innerEnd   = new org.objectweb.asm.Label();

            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 0); // i = 0
            mv.visitLabel(outerCheck);
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitIntInsn(Opcodes.BIPUSH, 5);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, outerEnd);

            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 1); // j = 0
            mv.visitLabel(innerCheck);
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitIntInsn(Opcodes.BIPUSH, 5);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, innerEnd);
            mv.visitIincInsn(1, 1);
            mv.visitJumpInsn(Opcodes.GOTO, innerCheck); // inner backedge

            mv.visitLabel(innerEnd);
            mv.visitIincInsn(0, 1);
            mv.visitJumpInsn(Opcodes.GOTO, outerCheck); // outer backedge

            mv.visitLabel(outerEnd);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(2, 2);
        });

        byte[] rewritten = rewriter.rewrite(original, null);

        assertVerifies(rewritten);
        // 1 entry + 2 backedges = 3 polls
        assertThat(countPolls(rewritten, "parallelMethod"))
                .as("nested-loop method should get 3 polls: entry + 2 backedges")
                .isEqualTo(3);
    }

    /** Methods without @Parallel annotation must not be modified. */
    @Test
    void nonAnnotatedMethod_notModified() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "TestNoAnnotation", null,
                "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "notParallel", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        byte[] original = cw.toByteArray();

        byte[] result = rewriter.rewrite(original, null);
        assertThat(result).isSameAs(original);
    }

    /** Rewritten bytes survive exception-table preservation check (CheckClassAdapter). */
    @Test
    void tryCatchInLoop_verifies() {
        byte[] original = buildFixtureClass("TestTryCatch", mv -> {
            mv.visitCode();
            org.objectweb.asm.Label loopStart = new org.objectweb.asm.Label();
            org.objectweb.asm.Label loopEnd   = new org.objectweb.asm.Label();
            org.objectweb.asm.Label tryStart  = new org.objectweb.asm.Label();
            org.objectweb.asm.Label tryEnd    = new org.objectweb.asm.Label();
            org.objectweb.asm.Label handler   = new org.objectweb.asm.Label();

            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 0);
            mv.visitLabel(loopStart);
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitIntInsn(Opcodes.BIPUSH, 3);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd);

            mv.visitLabel(tryStart);
            mv.visitIincInsn(0, 1);
            mv.visitLabel(tryEnd);
            mv.visitJumpInsn(Opcodes.GOTO, loopStart); // backedge
            mv.visitLabel(handler);
            mv.visitInsn(Opcodes.POP); // discard exception
            mv.visitJumpInsn(Opcodes.GOTO, loopStart); // backedge from handler

            mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Exception");
            mv.visitLabel(loopEnd);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(2, 1);
        });

        byte[] rewritten = rewriter.rewrite(original, null);
        assertVerifies(rewritten);
    }
}
