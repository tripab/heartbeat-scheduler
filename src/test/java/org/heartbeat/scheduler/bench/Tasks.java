package org.heartbeat.scheduler.bench;

import org.heartbeat.scheduler.task.HeartbeatTask;

import java.util.concurrent.RecursiveTask;

/**
 * Shared Fibonacci task definitions used by FibBench, BoundsBench, and ComparativeBench.
 */
final class Tasks {

    private Tasks() {}

    /** Heartbeat-scheduled recursive Fibonacci. */
    static final class FibTask extends HeartbeatTask<Long> {
        private final int n;

        FibTask(int n) { this.n = n; }

        @Override
        protected Long compute() {
            if (n <= 1) return (long) n;
            FibTask f1 = new FibTask(n - 1);
            FibTask f2 = new FibTask(n - 2);
            fork(f1);
            fork(f2);
            return join(f1) + join(f2);
        }
    }

    /** ForkJoinPool recursive Fibonacci. */
    static final class FjFib extends RecursiveTask<Long> {
        private final int n;

        FjFib(int n) { this.n = n; }

        @Override
        protected Long compute() {
            if (n <= 1) return (long) n;
            FjFib f1 = new FjFib(n - 1);
            f1.fork();
            FjFib f2 = new FjFib(n - 2);
            return f2.compute() + f1.join();
        }
    }

    /** Sequential Fibonacci reference. */
    static long seqFib(int n) {
        if (n <= 1) return n;
        return seqFib(n - 1) + seqFib(n - 2);
    }
}
