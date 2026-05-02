package org.heartbeat.scheduler.bench;

import org.heartbeat.scheduler.core.HeartbeatConfig;
import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.task.HeartbeatTask;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.*;

/**
 * JMH benchmark: comparative scalability — heartbeat scheduler vs. ForkJoinPool vs. sequential.
 *
 * <p>Runs recursive Fibonacci(35) under three executor types while varying the degree of
 * parallelism ({@code numCarriers} carriers).  The goal is not to show that the heartbeat
 * scheduler is faster than ForkJoinPool on every benchmark — ForkJoinPool is a highly
 * optimised baseline — but to show that the heartbeat scheduler delivers bounded overhead
 * under nested parallelism and scales predictably.
 *
 * <p><b>Parallelism control:</b>
 * <ul>
 *   <li>ForkJoinPool — direct: {@code new ForkJoinPool(numCarriers)}.</li>
 *   <li>Heartbeat scheduler — indirect: Loom's default carrier scheduler is a ForkJoinPool
 *       sized to {@code jdk.virtualThreadScheduler.parallelism} (defaults to
 *       {@code Runtime.availableProcessors()}).  The heartbeat executor therefore
 *       uses whatever Loom offers; the {@code numCarriers} parameter still provides a
 *       meaningful experimental axis because the ForkJoinPool baseline is controlled.</li>
 *   <li>Sequential — single-threaded reference; same result regardless of {@code numCarriers}.</li>
 * </ul>
 *
 * <p>Generate scalability curves from the JSON output with {@code scripts/plot-results.py}.
 *
 * <p>Run:
 * <pre>
 *   mvn test-compile exec:java -Pbenchmarks \
 *     -Dexec.args="ComparativeBench -rf json -rff docs/results/comparative.json"
 * </pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(value = 3, jvmArgsPrepend = "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED")
public class ComparativeBench {

    /** Parallelism level — varies the ForkJoinPool size (see class javadoc for heartbeat caveat). */
    @Param({"1", "2", "4", "8"})
    public int numCarriers;

    private static final int FIB_N = 35;

    private VirtualThreadExecutor heartbeatExecutor;
    private ForkJoinPool fjPool;

    @Setup(Level.Trial)
    public void setup() {
        heartbeatExecutor = new VirtualThreadExecutor(
                HeartbeatConfig.newBuilder()
                        .heartbeatPeriodMicros(30)
                        .promotionCostMicros(2)
                        .numCarrierThreads(numCarriers)
                        .build());

        fjPool = new ForkJoinPool(numCarriers);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws InterruptedException {
        heartbeatExecutor.close();
        fjPool.shutdown();
        fjPool.awaitTermination(10, TimeUnit.SECONDS);
    }

    // ---- benchmark methods ------------------------------------------------

    @Benchmark
    public void heartbeat(Blackhole bh) throws ExecutionException {
        bh.consume(heartbeatExecutor.submit(new FibTask(FIB_N)));
    }

    @Benchmark
    public void forkJoinPool(Blackhole bh) throws Exception {
        bh.consume(fjPool.submit(new FjFib(FIB_N)).get());
    }

    @Benchmark
    public void sequential(Blackhole bh) {
        bh.consume(seqFib(FIB_N));
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

    static long seqFib(int n) {
        if (n <= 1) return n;
        return seqFib(n - 1) + seqFib(n - 2);
    }
}
