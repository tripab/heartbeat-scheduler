package org.heartbeat.scheduler.sync;

import org.heartbeat.scheduler.task.HeartbeatTask;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks how many parallel branches need to complete
 * before the join continuation can run.
 * 
 * In the paper's terminology, this corresponds to the join point
 * where multiple parallel branches (from a parallel pair or loop)
 * synchronize before continuing with the join continuation.
 */
public class JoinCounter {
    private final AtomicInteger count;
    private final HeartbeatTask<?> joinTask;
    private volatile boolean ready;
    private final Object lock = new Object();

    /**
     * Create a join counter for the given number of branches.
     * 
     * @param initialCount Number of branches that must complete
     * @param joinTask The task to execute when all branches complete
     */
    public JoinCounter(int initialCount, HeartbeatTask<?> joinTask) {
        if (initialCount <= 0) {
            throw new IllegalArgumentException("Initial count must be positive");
        }
        if (joinTask == null) {
            throw new IllegalArgumentException("Join task cannot be null");
        }
        
        this.count = new AtomicInteger(initialCount);
        this.joinTask = joinTask;
        this.ready = false;
    }

    /**
     * Decrement the counter. Returns true if counter reached zero.
     * 
     * When a parallel branch completes, it calls this method.
     * If this is the last branch, the counter reaches zero and
     * returns true, signaling that the join task can execute.
     * 
     * @return true if counter reached zero (join task is ready)
     * @throws IllegalStateException if decremented below zero
     */
    public boolean decrement() {
        int remaining = count.decrementAndGet();
        
        if (remaining == 0) {
            synchronized (lock) {
                ready = true;
                lock.notifyAll(); // Wake up any waiting threads
            }
            return true;
        }
        
        if (remaining < 0) {
            throw new IllegalStateException(
                "JoinCounter decremented below zero - concurrent modification?"
            );
        }
        
        return false;
    }

    /**
     * Check if the join task is ready to execute.
     * 
     * @return true if all branches have completed
     */
    public boolean isReady() {
        return ready;
    }

    /**
     * Get the join task to execute when ready.
     */
    public HeartbeatTask<?> getJoinTask() {
        return joinTask;
    }

    /**
     * Get the current count (number of branches still pending).
     */
    public int getCount() {
        return count.get();
    }

    /**
     * Wait for the join counter to become ready.
     * Blocks until all branches have completed.
     * 
     * @throws InterruptedException if interrupted while waiting
     */
    public void await() throws InterruptedException {
        synchronized (lock) {
            while (!ready) {
                lock.wait();
            }
        }
    }

    /**
     * Wait for the join counter to become ready with timeout.
     * 
     * @param timeoutMillis Maximum time to wait in milliseconds
     * @return true if ready, false if timed out
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean await(long timeoutMillis) throws InterruptedException {
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (!ready) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                lock.wait(remaining);
            }
            return true;
        }
    }

    @Override
    public String toString() {
        return String.format(
            "JoinCounter[count=%d, ready=%s, task=%s]",
            count.get(),
            ready,
            joinTask.getClass().getSimpleName()
        );
    }
}
