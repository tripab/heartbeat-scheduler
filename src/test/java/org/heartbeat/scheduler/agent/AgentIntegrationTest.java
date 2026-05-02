package org.heartbeat.scheduler.agent;

import org.heartbeat.scheduler.core.HeartbeatConfig;
import org.heartbeat.scheduler.core.HeartbeatContext;
import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.task.HeartbeatTask;
import org.heartbeat.scheduler.testutil.TestConfig;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests for the PRC rewriter + runtime.
 *
 * Tests verify that:
 * 1. checkHeartbeatStatic() is a safe no-op outside the executor.
 * 2. checkHeartbeatStatic() delegates correctly when a context is active.
 * 3. A rewritten @Parallel class (loaded via custom ClassLoader) computes
 *    correct results when run through VirtualThreadExecutor.
 */
class AgentIntegrationTest {

    // -------------------------------------------------------------------------
    // 1. checkHeartbeatStatic() is safe outside executor
    // -------------------------------------------------------------------------

    @Test
    void checkHeartbeatStatic_noContext_returnsFalse() {
        // Must be safe to call from any thread with no context installed.
        assertThatCode(() -> {
            boolean result = HeartbeatContext.checkHeartbeatStatic();
            assertThat(result).isFalse();
        }).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // 2. checkHeartbeatStatic() delegates to current context
    // -------------------------------------------------------------------------

    @Test
    void checkHeartbeatStatic_withContext_delegatesToCheckHeartbeat() {
        HeartbeatConfig config = HeartbeatConfig.newBuilder()
                .heartbeatPeriodMicros(10)  // short period — promotes quickly
                .promotionCostMicros(1)
                .build();
        HeartbeatContext ctx = new HeartbeatContext(config, config.createPollingStrategy());
        HeartbeatContext.setCurrent(ctx);
        try {
            // Spin until the timer fires; checkHeartbeatStatic should return true at some point.
            boolean sawPromotion = false;
            for (int i = 0; i < 100_000; i++) {
                if (HeartbeatContext.checkHeartbeatStatic()) {
                    sawPromotion = true;
                    break;
                }
            }
            assertThat(sawPromotion)
                    .as("checkHeartbeatStatic() should return true once heartbeat period elapses")
                    .isTrue();
        } finally {
            HeartbeatContext.clearCurrent();
        }
    }

    // -------------------------------------------------------------------------
    // 3. Rewritten @Parallel class computes correct results
    // -------------------------------------------------------------------------

    /**
     * Builds a synthetic class (bytecode) that contains a static method annotated
     * with @Parallel and implements a simple countdown loop. After rewriting via
     * PrcRewriter, the method should contain at least 2 poll calls (entry + backedge).
     *
     * The synthetic class is:
     *
     * public class SyntheticWorker {
     *   @Parallel
     *   public static int countDown(int n) {
     *     int sum = 0;
     *     while (n > 0) { sum += n; n--; }
     *     return sum;
     *   }
     * }
     */
    private byte[] buildSyntheticWorkerClass(String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        // default constructor
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // @Parallel static int countDown(int n)
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "countDown", "(I)I", null, null);
        mv.visitAnnotation("Lorg/heartbeat/scheduler/annotations/Parallel;", false).visitEnd();
        mv.visitCode();

        // int sum = 0;
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 1); // slot 1 = sum; slot 0 = n

        Label loopHead = new Label();
        Label loopEnd  = new Label();

        mv.visitLabel(loopHead);
        // if (n <= 0) goto loopEnd
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitJumpInsn(Opcodes.IFLE, loopEnd);
        // sum += n
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, 1);
        // n--
        mv.visitIincInsn(0, -1);
        mv.visitJumpInsn(Opcodes.GOTO, loopHead); // backedge
        mv.visitLabel(loopEnd);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void rewrittenClass_producesCorrectResult() throws Exception {
        String internalName = "SyntheticWorkerForTest";
        byte[] original = buildSyntheticWorkerClass(internalName);

        PrcRewriter rewriter = new PrcRewriter();
        byte[] rewritten = rewriter.rewrite(original, null);

        assertThat(rewritten)
                .as("@Parallel class should be modified by the rewriter")
                .isNotSameAs(original);

        // Load the rewritten class
        ClassLoader loader = new SingleClassLoader(internalName.replace('/', '.'), rewritten);
        Class<?> cls = loader.loadClass(internalName.replace('/', '.'));
        Method countDown = cls.getMethod("countDown", int.class);

        // Verify correctness: sum(1..10) = 55
        int n = 10;
        int expected = n * (n + 1) / 2; // 55
        int result = (int) countDown.invoke(null, n);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void rewrittenClass_pollsInsertedAtEntryAndBackedge() throws Exception {
        String internalName = "SyntheticWorkerForPollCount";
        byte[] original = buildSyntheticWorkerClass(internalName);

        PrcRewriter rewriter = new PrcRewriter();
        byte[] rewritten = rewriter.rewrite(original, null);

        // Count poll calls in the rewritten bytecode
        org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(rewritten);
        org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
        cr.accept(cn, 0);

        int polls = 0;
        for (org.objectweb.asm.tree.MethodNode mn : cn.methods) {
            if (mn.name.equals("countDown")) {
                for (org.objectweb.asm.tree.AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof org.objectweb.asm.tree.MethodInsnNode mi
                            && mi.owner.equals(PrcRewriter.CONTEXT_OWNER)
                            && mi.name.equals(PrcRewriter.CHECK_METHOD)) {
                        polls++;
                    }
                }
            }
        }
        // 1 entry poll + 1 backedge poll = 2
        assertThat(polls).as("countDown has 1 loop → 2 polls (entry + backedge)").isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // 4. Agent-instrumented code path: promotions fire under executor
    // -------------------------------------------------------------------------

    /**
     * Verifies the headline R1 claim: code running under VirtualThreadExecutor
     * with an aggressive heartbeat config actually triggers promotions.
     * fib(20) produces enough fork() calls that at least one frame is promoted
     * before the computation completes.
     */
    @Test
    void promotableTask_triggersPromotions_underExecutor() throws Exception {
        HeartbeatConfig config = TestConfig.aggressiveBuilder()
                .enableStatistics(true)
                .build();

        try (VirtualThreadExecutor executor = new VirtualThreadExecutor(config)) {
            long result = executor.submit(new AgentFibTask(20));

            assertThat(result)
                    .as("fib(20) should equal 6765")
                    .isEqualTo(6765L);
            assertThat(executor.getTotalPromotionsPerformed())
                    .as("At least one fork should be promoted with aggressive heartbeat config (period=%dns)",
                            config.getHeartbeatPeriodNanos())
                    .isGreaterThan(0);
        }
    }

    private static final class AgentFibTask extends HeartbeatTask<Long> {
        private final int n;

        AgentFibTask(int n) { this.n = n; }

        @Override
        protected Long compute() {
            if (n <= 1) return (long) n;
            AgentFibTask f1 = new AgentFibTask(n - 1);
            AgentFibTask f2 = new AgentFibTask(n - 2);
            fork(f1);
            fork(f2);
            return join(f1) + join(f2);
        }
    }

    // -------------------------------------------------------------------------
    // ClassLoader for a single dynamically-generated class
    // -------------------------------------------------------------------------

    private static class SingleClassLoader extends ClassLoader {
        private final String className;
        private final byte[] classBytes;

        SingleClassLoader(String className, byte[] classBytes) {
            // Use current thread's context classloader as parent so
            // the synthetic class can resolve HeartbeatContext etc.
            super(Thread.currentThread().getContextClassLoader());
            this.className = className;
            this.classBytes = classBytes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.equals(className)) {
                return defineClass(name, classBytes, 0, classBytes.length);
            }
            return super.findClass(name);
        }
    }
}
