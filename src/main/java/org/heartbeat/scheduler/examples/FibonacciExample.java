package org.heartbeat.scheduler.examples;

import org.heartbeat.scheduler.core.HeartbeatConfig;
import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.task.HeartbeatTask;

import java.util.concurrent.ExecutionException;

/**
 * Recursive Fibonacci computed with heartbeat-scheduled fork/join.
 * <p>
 * Each recursive call forks two subtasks. The heartbeat timer decides at
 * runtime which forks get promoted to virtual threads (parallel) and which
 * stay sequential — yielding the (1 + τ/N) work bound from Acar et al. 2018.
 * <p>
 * Usage: {@code java -cp target/classes org.heartbeat.scheduler.examples.FibonacciExample [n]}
 * where {@code n} defaults to 25. The {@code --add-exports java.base/jdk.internal.vm=ALL-UNNAMED}
 * JVM flag is required (see README).
 */
public final class FibonacciExample {

    private static final int DEFAULT_N = 20;
    private static final int MAX_N = 45;

    private FibonacciExample() {}

    public static void main(String[] args) throws ExecutionException {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_N;
        if (n < 0 || n > MAX_N) {
            System.err.printf("Pick n in [0, %d]; got %d%n", MAX_N, n);
            System.exit(1);
        }

        HeartbeatConfig config = HeartbeatConfig.newBuilder()
                .heartbeatPeriodMicros(30)
                .promotionCostMicros(2)
                .enableStatistics(true)
                .build();

        System.out.println(config);
        System.out.printf("Computing fib(%d) with heartbeat scheduling...%n", n);

        try (VirtualThreadExecutor executor = new VirtualThreadExecutor(config)) {
            long start = System.nanoTime();
            long result = executor.submit(new FibTask(n));
            long elapsedNanos = System.nanoTime() - start;

            System.out.printf("fib(%d) = %d%n", n, result);
            System.out.printf("Elapsed: %.2f ms%n", elapsedNanos / 1_000_000.0);
            System.out.println(executor.getStatistics());
        }
    }

    static final class FibTask extends HeartbeatTask<Long> {
        private final int n;

        FibTask(int n) {
            this.n = n;
        }

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
}
