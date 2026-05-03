package org.heartbeat.scheduler.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * ClassFileTransformer that applies the PRC rewriter to every loaded class.
 *
 * Fast-path: classes with no @Parallel annotations are returned unchanged by
 * PrcRewriter without allocating a ClassWriter, so the overhead for
 * non-annotated code is a single ClassReader scan.
 */
public class PrcClassTransformer implements ClassFileTransformer {

    private final boolean verbose;
    private final PrcRewriter rewriter = new PrcRewriter();

    public PrcClassTransformer(boolean verbose) {
        this.verbose = verbose;
    }

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {

        if (shouldSkip(loader, className)) return null;

        try {
            byte[] result = rewriter.rewrite(classfileBuffer, loader);
            if (result != classfileBuffer && verbose) {
                System.err.println("[PrcAgent] Instrumented: " + className.replace('/', '.'));
            }
            // Return null when unmodified — ClassFileTransformer contract: null = no change.
            return result == classfileBuffer ? null : result;
        } catch (Throwable e) {
            System.err.println("[PrcAgent] Failed to instrument " + className + ": " + e);
            return null;
        }
    }

    private static boolean shouldSkip(ClassLoader loader, String className) {
        // Skip bootstrap/platform classes and internal dependencies before parsing bytes.
        if (loader == null || loader == ClassLoader.getPlatformClassLoader()) return true;
        return className == null
                || className.startsWith("java/")
                || className.startsWith("javax/")
                || className.startsWith("jdk/")
                || className.startsWith("sun/")
                || className.startsWith("com/sun/")
                || className.startsWith("org/objectweb/asm/")
                || className.startsWith("org/heartbeat/scheduler/agent/");
    }
}
