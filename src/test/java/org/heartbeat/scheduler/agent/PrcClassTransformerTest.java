package org.heartbeat.scheduler.agent;

import org.heartbeat.scheduler.core.HeartbeatConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for PrcClassTransformer: skip rules, error path, and both
 * transformation outcomes (returns null vs. returns instrumented bytes).
 */
class PrcClassTransformerTest {

    private final PrcClassTransformer transformer =
            new PrcClassTransformer(false);

    // =========================================================================
    // Skip rules
    // =========================================================================

    @Test
    void nullClassNameReturnsNull() {
        assertThat(transformer.transform(null, null, null, null, new byte[]{0})).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "java/lang/Object",
            "javax/sql/DataSource",
            "jdk/internal/vm/Continuation",
            "sun/misc/Unsafe",
            "com/sun/proxy/$Proxy0",
            "org/objectweb/asm/ClassWriter",
            "org/heartbeat/scheduler/agent/PrcRewriter",
    })
    void skippedPrefixesReturnNull(String className) {
        byte[] result = transformer.transform(
                null, className, null, null, new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        assertThat(result)
                .as("transform should return null for skipped class '%s'", className)
                .isNull();
    }

    // =========================================================================
    // Error path
    // =========================================================================

    /**
     * Malformed bytes cause PrcRewriter to throw; PrcClassTransformer must catch
     * the exception and return null rather than letting it propagate to the JVM.
     */
    @Test
    void malformedBytesCaughtAndReturnsNull() {
        assertThatCode(() -> {
            byte[] result = transformer.transform(
                    null, "com/example/Foo", null, null, new byte[]{0, 1, 2, 3});
            assertThat(result).isNull();
        }).doesNotThrowAnyException();
    }

    // =========================================================================
    // Transformation outcomes
    // =========================================================================

    /**
     * A class without @Parallel is returned unchanged by PrcRewriter (same reference).
     * PrcClassTransformer must honour the ClassFileTransformer contract and return null
     * to signal "no transformation" rather than returning the original bytes.
     */
    @Test
    void nonAnnotatedClassReturnsNull() throws Exception {
        // HeartbeatConfig is outside the agent skip prefix and has no @Parallel annotation.
        byte[] bytes = bytesOf(HeartbeatConfig.class);
        byte[] result = transformer.transform(
                HeartbeatConfig.class.getClassLoader(),
                "org/heartbeat/scheduler/core/HeartbeatConfig",
                null, null, bytes);
        assertThat(result)
                .as("unannotated class must produce null (ClassFileTransformer contract)")
                .isNull();
    }

    /**
     * A class with @Parallel is instrumented; PrcClassTransformer returns the
     * rewritten bytes (non-null, different from the original buffer).
     */
    @Test
    void annotatedClassReturnsInstrumentedBytes() {
        byte[] original = buildAnnotatedFixture("com/example/Annotated");
        byte[] result = transformer.transform(
                null, "com/example/Annotated", null, null, original);
        assertThat(result)
                .as("class with @Parallel must be transformed and returned non-null")
                .isNotNull()
                .isNotSameAs(original);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static byte[] bytesOf(Class<?> cls) throws Exception {
        String path = cls.getName().replace('.', '/') + ".class";
        try (var is = cls.getClassLoader().getResourceAsStream(path)) {
            assertThat(is).as("class file resource for %s", cls).isNotNull();
            return is.readAllBytes();
        }
    }

    /** Builds a minimal class with one static method annotated @Parallel. */
    private static byte[] buildAnnotatedFixture(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitAnnotation("Lorg/heartbeat/scheduler/annotations/Parallel;", false).visitEnd();
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
