package org.heartbeat.scheduler.task;

import org.heartbeat.scheduler.core.HeartbeatContext;
import org.heartbeat.scheduler.core.PromotionTracker;
import org.heartbeat.scheduler.executor.VirtualThreadExecutor;

import org.heartbeat.scheduler.sync.PromotionPoint;
import org.heartbeat.scheduler.vthread.ContinuationScope;
import org.heartbeat.scheduler.vthread.HeartbeatContinuation;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

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

    // Phase 4: executor and promotion state
    private volatile VirtualThreadExecutor executor;
    private final AtomicReference<CompletableFuture<T>> promotedFuture = new AtomicReference<>();

    protected HeartbeatTask() {
        this(ContinuationScope.createDefault());
    }

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
        } catch (Exception e) {
            exception = e;
            completed = true;
            throw e;
        } catch (Error e) {
            exception = e;
            completed = true;
            throw e;
        }
    }

    /**
     * Fork a subtask for parallel execution.
     * <p>
     * The forked task is registered as a promotable frame. If the heartbeat
     * fires, the oldest promotable frame will be promoted to a virtual thread.
     * Otherwise the task stays sequential and will be executed inline at join().
     *
     * @param task The task to fork
     * @return The same task (for chaining with join)
     */
    protected <U> HeartbeatTask<U> fork(HeartbeatTask<U> task) {
        HeartbeatContext context = HeartbeatContext.current();
        if (context == null) {
            throw new IllegalStateException(
                    "No HeartbeatContext set on current thread. " +
                    "Tasks must be submitted via VirtualThreadExecutor."
            );
        }

        // Wire executor to child task
        if (executor != null) {
            task.setExecutor(executor);
        }

        // Create a promotion point for this fork and push it to the tracker
        PromotionTracker tracker = context.getPromotionTracker();
        HeartbeatContinuation continuation = new HeartbeatContinuation(
                task.getScope(), () -> {
            try {
                task.call();
            } catch (Exception e) {
                // Exception stored in task
            }
        });
        PromotionPoint point = new PromotionPoint(continuation, task.getScope(), task);
        tracker.pushFrame(point);

        // Check heartbeat - promote oldest frame if timer has fired
        if (context.checkHeartbeat()) {
            PromotionPoint oldest = tracker.promoteOldest();
            if (oldest != null && executor != null) {
                @SuppressWarnings("unchecked")
                HeartbeatTask<Object> oldestTask = (HeartbeatTask<Object>) oldest.getTask();
                CompletableFuture<Object> future = executor.promoteTask(oldestTask);
                if (oldestTask.promotedFuture.compareAndSet(null, future)) {
                    context.recordPromotion();
                    context.getConfig().getObserver().onPromotion(
                            Thread.currentThread().getName(),
                            oldest.getAgeNanos(),
                            tracker.size());
                } else {
                    // Another thread already promoted this task; cancel our submission
                    future.cancel(false);
                }
            }
        }

        return task;
    }

    /**
     * Join (wait for) a forked subtask and return its result.
     * <p>
     * If the task was promoted to a virtual thread, this blocks until
     * the virtual thread completes. Otherwise, the task is executed
     * sequentially on the current thread.
     *
     * @param task The task to join
     * @return The result of the task
     */
    protected <U> U join(HeartbeatTask<U> task) {
        // Atomically read the promoted future to prevent race with fork()
        CompletableFuture<U> future = task.promotedFuture.get();
        if (future != null) {
            HeartbeatContext context = HeartbeatContext.current();
            Runnable endJoin = context != null
                    ? context.getConfig().getObserver().startJoinBlocked(
                            Thread.currentThread().getName(), task.getAgeNanos())
                    : () -> {};
            try {
                return future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Join interrupted", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error er) throw er;
                throw new RuntimeException(cause);
            } finally {
                endJoin.run();
            }
        }

        // Not promoted - pop from tracker and execute sequentially
        HeartbeatContext context = HeartbeatContext.current();
        if (context != null) {
            context.getPromotionTracker().popFrame();
        }

        // If already completed (e.g., by some other mechanism), return result
        if (task.isCompleted()) {
            return task.getResult();
        }

        // Execute sequentially on current thread
        try {
            return task.call();
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }

    /**
     * Convenience method: fork a task and immediately join it.
     * Equivalent to: join(fork(task))
     */
    protected <U> U invoke(HeartbeatTask<U> task) {
        return join(fork(task));
    }

    /**
     * Set the executor for this task. Called by VirtualThreadExecutor.
     */
    public void setExecutor(VirtualThreadExecutor executor) {
        this.executor = executor;
    }

    public VirtualThreadExecutor getExecutor() {
        return executor;
    }

    public boolean isPromoted() {
        return promotedFuture.get() != null;
    }

    public T getResult() {
        if (exception != null) {
            throw new IllegalStateException("Task completed with exception", exception);
        }
        return result;
    }

    public Throwable getException() {
        return exception;
    }

    public boolean isCompleted() {
        return completed;
    }

    public ContinuationScope getScope() {
        return scope;
    }

    public long getAgeNanos() {
        return System.nanoTime() - creationTime;
    }

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
