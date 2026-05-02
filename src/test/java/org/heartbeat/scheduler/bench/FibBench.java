package org.heartbeat.scheduler.bench;

import org.heartbeat.scheduler.core.HeartbeatConfig;
import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.task.HeartbeatTask;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.*;

/**
 * JMH benchmark: recursive Fibonacci.
 *
 * <p>Regular binary-recursive split — the canonical heartbeat sanity baseline.
 * Compares heartbeat scheduler vs. ForkJoinPool vs. sequential.
 *
 * <p>Run (after {@code mvn package -DskipTests}):
 * <pre>
 *   java --add-exports java.base/jdk.internal.vm=ALL-UNNAMED \
 *        -jar target/benchmarks.jar FibBench
 * </pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(value = 3, jvmArgsPrepend = "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED")
public class FibBench {

    @Param({"30", "35"})
    public int n;

    private VirtualThreadExecutor executor;

    @Setup(Level.Trial)
    public void setup() {
        executor = new VirtualThreadExecutor(
                HeartbeatConfig.newBuilder()
                        .heartbeatPeriodMicros(30)
                        .promotionCostMicros(2)
                        .build());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        executor.close();
    }

    @Benchmark
    public void heartbeat(Blackhole bh) throws ExecutionException {
        bh.consume(executor.submit(new FibTask(n)));
    }

    @Benchmark
    public void sequential(Blackhole bh) {
        bh.consume(seqFib(n));
    }

    @Benchmark
    public void forkJoinPool(Blackhole bh) throws Exception {
        bh.consume(ForkJoinPool.commonPool().submit(new FjFib(n)).get());
    }

    // ---- heartbeat task ------------------------------------------------

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

    // ---- ForkJoinPool task ---------------------------------------------

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

    // ---- sequential reference ------------------------------------------

    private static long seqFib(int n) {
        if (n <= 1) return n;
        return seqFib(n - 1) + seqFib(n - 2);
    }
}
