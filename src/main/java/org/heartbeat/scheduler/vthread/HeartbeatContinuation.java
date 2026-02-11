package org.heartbeat.scheduler.vthread;

import jdk.internal.vm.Continuation;

/**
 * Manages a continuation that can be yielded and resumed.
 * This is where promotion happens - when we yield, we're
 * deferring work that can be picked up later.
 * 
 * Corresponds to the promotion mechanism in the paper where
 * PAIRL frames are converted into independent machines.
 */
public class HeartbeatContinuation {
    private final Continuation continuation;
    private final ContinuationScope scope;
    private volatile boolean isDone;
    private volatile boolean hasYielded;
    private final long creationTime;

    /**
     * Create a new continuation with the given scope and target.
     * 
     * @param scope The continuation scope
     * @param target The runnable to execute in this continuation
     */
    public HeartbeatContinuation(ContinuationScope scope, Runnable target) {
        if (scope == null) {
            throw new IllegalArgumentException("Scope cannot be null");
        }
        if (target == null) {
            throw new IllegalArgumentException("Target cannot be null");
        }
        
        this.scope = scope;
        this.continuation = new Continuation(scope.toJdkScope(), target);
        this.isDone = false;
        this.hasYielded = false;
        this.creationTime = System.nanoTime();
    }

    /**
     * Yield this continuation (promotion point).
     * This corresponds to promoting a PAIRL frame in the paper.
     * 
     * @throws IllegalStateException if continuation is already done
     */
    public void yield() {
        if (isDone) {
            throw new IllegalStateException("Cannot yield a completed continuation");
        }
        
        Continuation.yield(scope.toJdkScope());
        hasYielded = true;
    }

    /**
     * Resume this continuation.
     * Executes the continuation until it yields or completes.
     */
    public void resume() {
        if (isDone) {
            return; // Already completed, nothing to do
        }
        
        if (!continuation.isDone()) {
            continuation.run();
            isDone = continuation.isDone();
        } else {
            isDone = true;
        }
    }

    /**
     * Check if this continuation has completed.
     */
    public boolean isDone() {
        return isDone || continuation.isDone();
    }

    /**
     * Check if this continuation has yielded at least once.
     */
    public boolean hasYielded() {
        return hasYielded;
    }

    /**
     * Get the continuation scope.
     */
    public ContinuationScope getScope() {
        return scope;
    }

    /**
     * Get the age of this continuation in nanoseconds.
     */
    public long getAgeNanos() {
        return System.nanoTime() - creationTime;
    }

    /**
     * Get the age of this continuation in microseconds.
     */
    public long getAgeMicros() {
        return getAgeNanos() / 1_000;
    }

    @Override
    public String toString() {
        return String.format(
            "HeartbeatContinuation[scope=%s, done=%s, yielded=%s, age=%.2fμs]",
            scope.name(),
            isDone,
            hasYielded,
            getAgeMicros() / 1000.0
        );
    }

    /**
     * Static method to yield from the current continuation.
     * Convenience method for use within task code.
     */
    public static void yieldCurrent(ContinuationScope scope) {
        Continuation.yield(scope.toJdkScope());
    }

    /**
     * Check if we're currently running in a continuation.
     */
    public static boolean isInContinuation() {
        // This is a simplified check - in practice we'd need to track this
        // via thread-local state
        return Thread.currentThread().isVirtual();
    }
}
