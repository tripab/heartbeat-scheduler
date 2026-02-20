package org.heartbeat.scheduler.executor;

import org.heartbeat.scheduler.core.CountBasedPolling;
import org.heartbeat.scheduler.core.HeartbeatConfig;
import org.heartbeat.scheduler.core.HeartbeatContext;
import org.heartbeat.scheduler.core.PollingStrategy;
import org.heartbeat.scheduler.task.HeartbeatTask;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Executor that runs HeartbeatTask instances using virtual threads.
 * <p>
 * This is the main entry point for executing parallel computations
 * with heartbeat scheduling. Tasks are submitted here and executed
 * with automatic promotion based on the heartbeat timer.
 * <p>
 * When a task is promoted, it is run on a new virtual thread. The
 * HeartbeatContext is propagated to promoted threads so that nested
 * fork/join operations continue to work correctly.
 */
public class VirtualThreadExecutor {
    private final HeartbeatConfig config;
    private final ExecutorService virtualThreadPool;
    private volatile boolean shutdown;

    // Statistics
    private final AtomicLong totalTasksExecuted = new AtomicLong();
    private final AtomicLong totalPromotions = new AtomicLong();
    private final AtomicInteger activeVirtualThreads;

    public VirtualThreadExecutor(HeartbeatConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Config cannot be null");
        }
        this.config = config;
        this.virtualThreadPool = Executors.newVirtualThreadPerTaskExecutor();
        this.shutdown = false;
        this.activeVirtualThreads = new AtomicInteger(0);
    }

    /**
     * Submit a task for execution and block until it completes.
     *
     * @param task The task to execute
     * @return The task's result
     * @throws ExecutionException if the task throws an exception
     */
    public <T> T submit(HeartbeatTask<T> task) throws ExecutionException {
        if (shutdown) {
            throw new IllegalStateException("Executor has been shut down");
        }

        // Set up heartbeat context for this thread
        HeartbeatContext context = createContext();
        HeartbeatContext.setCurrent(context);

        try {
            // Wire up the task to this executor
            task.setExecutor(this);

            T result = task.call();
            totalTasksExecuted.incrementAndGet();
            return result;
        } catch (Exception e) {
            throw new ExecutionException(e);
        } finally {
            HeartbeatContext.clearCurrent();
        }
    }

    /**
     * Submit a task for async execution. Returns a CompletableFuture.
     */
    public <T> CompletableFuture<T> submitAsync(HeartbeatTask<T> task) {
        if (shutdown) {
            throw new IllegalStateException("Executor has been shut down");
        }

        CompletableFuture<T> future = new CompletableFuture<>();

        virtualThreadPool.submit(() -> {
            HeartbeatContext context = createContext();
            HeartbeatContext.setCurrent(context);
            try {
                task.setExecutor(this);
                T result = task.call();
                totalTasksExecuted.incrementAndGet();
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            } finally {
                HeartbeatContext.clearCurrent();
            }
        });

        return future;
    }

    /**
     * Promote a task to run on a new virtual thread.
     * Called internally by HeartbeatTask.fork() when the heartbeat fires.
     * <p>
     * This propagates the HeartbeatConfig to the new thread so that
     * nested fork/join operations work correctly with their own context.
     */
    public <T> CompletableFuture<T> promoteTask(HeartbeatTask<T> task) {
        totalPromotions.incrementAndGet();

        CompletableFuture<T> future = new CompletableFuture<>();

        virtualThreadPool.submit(() -> {
            // Create a fresh context for this promoted thread.
            // This is the fix for the threadlocal propagation bug:
            // each promoted virtual thread gets its own HeartbeatContext
            // initialized from the shared config.
            HeartbeatContext promotedContext = createContext();
            HeartbeatContext.setCurrent(promotedContext);
            try {
                task.setExecutor(this);
                T result = task.call();
                totalTasksExecuted.incrementAndGet();
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            } finally {
                HeartbeatContext.clearCurrent();
            }
        });

        return future;
    }

    public void shutdown() {
        shutdown = true;
        virtualThreadPool.shutdown();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return virtualThreadPool.awaitTermination(timeout, unit);
    }

    public int getActiveVirtualThreads() {
        return activeVirtualThreads.get();
    }

    public long getTotalPromotionsPerformed() {
        return totalPromotions.get();
    }

    public long getTotalTasksExecuted() {
        return totalTasksExecuted.get();
    }

    public boolean isShutdown() {
        return shutdown;
    }

    public HeartbeatConfig getConfig() {
        return config;
    }

    /**
     * Get statistics for this executor.
     */
    public ExecutorStatistics getStatistics() {
        return new ExecutorStatistics(
                totalTasksExecuted.get(),
                totalPromotions.get(),
                activeVirtualThreads.get(),
                shutdown
        );
    }

    private HeartbeatContext createContext() {
        PollingStrategy polling = CountBasedPolling.every(1);
        return new HeartbeatContext(config, polling);
    }

    /**
     * Immutable snapshot of executor statistics.
     */
    public static class ExecutorStatistics {
        public final long totalTasksExecuted;
        public final long totalPromotionsPerformed;
        public final int activeVirtualThreads;
        public final boolean shutdown;

        private ExecutorStatistics(
                long totalTasksExecuted,
                long totalPromotionsPerformed,
                int activeVirtualThreads,
                boolean shutdown
        ) {
            this.totalTasksExecuted = totalTasksExecuted;
            this.totalPromotionsPerformed = totalPromotionsPerformed;
            this.activeVirtualThreads = activeVirtualThreads;
            this.shutdown = shutdown;
        }

        public double getPromotionRate() {
            return totalTasksExecuted > 0
                    ? (double) totalPromotionsPerformed / totalTasksExecuted
                    : 0.0;
        }

        @Override
        public String toString() {
            return String.format(
                    "ExecutorStatistics[tasks=%d, promotions=%d, active=%d, rate=%.2f%%]",
                    totalTasksExecuted,
                    totalPromotionsPerformed,
                    activeVirtualThreads,
                    getPromotionRate() * 100.0
            );
        }
    }
}
