package org.heartbeat.scheduler.task;

import org.heartbeat.scheduler.vthread.ContinuationScope;

import java.util.concurrent.Callable;

/**
 * Base class for computations that can be executed with
 * Heartbeat scheduling using virtual threads.
 * 
 * This corresponds to the parallel computations in the paper
 * that can fork and join with bounded overhead.
 * 
 * @param <T> The type of result produced by this task
 */
public abstract class HeartbeatTask<T> implements Callable<T> {
    private volatile T result;
    private volatile Throwable exception;
    private final ContinuationScope scope;
    private volatile boolean completed;
    private final long creationTime;

    /**
     * Create a new HeartbeatTask with a default scope.
     */
    protected HeartbeatTask() {
        this(ContinuationScope.createDefault());
    }

    /**
     * Create a new HeartbeatTask with a specific scope.
     */
    protected HeartbeatTask(ContinuationScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("Scope cannot be null");
        }
        this.scope = scope;
        this.completed = false;
        this.creationTime = System.nanoTime();
    }

    /**
     * The computation to perform. Override this method to implement
     * your parallel computation.
     * 
     * Within compute(), you can:
     * - Call fork() to create parallel subtasks
     * - Call join() to wait for subtask completion
     * - Use normal control flow
     * 
     * @return The result of the computation
     */
    protected abstract T compute();

    /**
     * Called by the framework - wraps compute() with
     * continuation support and exception handling.
     * 
     * DO NOT override this method - override compute() instead.
     */
    @Override
    public final T call() throws Exception {
        try {
            result = compute();
            completed = true;
            return result;
        } catch (Throwable t) {
            exception = t;
            completed = true;
            if (t instanceof Exception) {
                throw (Exception) t;
            } else if (t instanceof Error) {
                throw (Error) t;
            } else {
                throw new RuntimeException(t);
            }
        }
    }

    /**
     * Fork a subtask for parallel execution.
     * 
     * In the paper's terminology, this creates a parallel pair (e1 || e2)
     * where the forked task is e2. The task may be executed sequentially
     * or promoted to a parallel thread depending on the heartbeat.
     * 
     * @param task The task to fork
     * @return The same task (for convenience)
     */
    protected <U> HeartbeatTask<U> fork(HeartbeatTask<U> task) {
        // Will be implemented in Phase 4 when we have the executor
        throw new UnsupportedOperationException(
            "fork() not yet implemented - requires Phase 4 executor"
        );
    }

    /**
     * Join (wait for) a forked subtask and return its result.
     * 
     * @param task The task to join
     * @return The result of the task
     */
    protected <U> U join(HeartbeatTask<U> task) {
        // Will be implemented in Phase 4 when we have the executor
        throw new UnsupportedOperationException(
            "join() not yet implemented - requires Phase 4 executor"
        );
    }

    /**
     * Convenience method: fork a task and immediately join it.
     * Equivalent to: join(fork(task))
     */
    protected <U> U invoke(HeartbeatTask<U> task) {
        return join(fork(task));
    }

    /**
     * Get the result of this task.
     * 
     * @return The result, or null if not yet completed
     * @throws IllegalStateException if the task completed with an exception
     */
    public T getResult() {
        if (exception != null) {
            throw new IllegalStateException("Task completed with exception", exception);
        }
        return result;
    }

    /**
     * Get the exception if the task failed.
     */
    public Throwable getException() {
        return exception;
    }

    /**
     * Check if this task has completed (successfully or with exception).
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * Get the continuation scope for this task.
     */
    public ContinuationScope getScope() {
        return scope;
    }

    /**
     * Get the age of this task in nanoseconds.
     */
    public long getAgeNanos() {
        return System.nanoTime() - creationTime;
    }

    /**
     * Get the age of this task in microseconds.
     */
    public long getAgeMicros() {
        return getAgeNanos() / 1_000;
    }

    @Override
    public String toString() {
        return String.format(
            "%s[completed=%s, age=%.2fμs]",
            getClass().getSimpleName(),
            completed,
            getAgeMicros() / 1000.0
        );
    }
}
