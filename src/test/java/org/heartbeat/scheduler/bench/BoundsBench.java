package org.heartbeat.scheduler.bench;

import org.heartbeat.scheduler.core.HeartbeatConfig;
import org.heartbeat.scheduler.core.TimeBasedPolling;
import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.task.HeartbeatTask;
import org.heartbeat.scheduler.utils.TimingCalibration;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark: empirical verification of the (1 + τ/N) work-overhead bound.
 *
 * <p>Sweeps the heartbeat period N over multiples of τ (the measured promotion cost):
 * <pre>
 *   N ∈ { 2τ, 5τ, 10τ, 20τ, 50τ, 100τ }
 * </pre>
 *
 * <p>The benchmark runs Fibonacci(30) under the heartbeat scheduler and measures
 * execution time as a proxy for total work W.  A sequential baseline provides w.
 * Theory predicts:
 * <pre>
 *   W / w  ≤  1 + τ/N  =  1 + 1/ratioNoverTau
 * </pre>
 *
 * <p>The {@code scripts/plot-results.py} script reads the JSON output of this
 * benchmark and generates the W/w vs (1 + τ/N) plot in {@code docs/results/}.
 *
 * <p>Run:
 * <pre>
 *   mvn test-compile exec:java -Pbenchmarks \
 *     -Dexec.args="BoundsBench -rf json -rff docs/results/bounds.json"
 * </pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(value = 3, jvmArgsPrepend = "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED")
public class BoundsBench {

    /**
     * N/τ ratio: heartbeat period expressed as a multiple of the promotion cost.
     * Small ratio → frequent promotions → high overhead.
     * Large ratio → infrequent promotions → low overhead.
     */
    @Param({"2", "5", "10", "20", "50", "100"})
    public int ratioNoverTau;

    private static final int FIB_N = 30;

    private VirtualThreadExecutor executor;
    private long measuredTauNanos;

    @Setup(Level.Trial)
    public void setup() {
        // Measure τ — virtual-thread creation + scheduling latency.
        // Guard against implausibly small values on misconfigured systems.
        measuredTauNanos = TimingCalibration.estimateVirtualThreadCost();
        if (measuredTauNanos < 500) measuredTauNanos = 2_000;

        long heartbeatPeriodNanos = measuredTauNanos * ratioNoverTau;

        // Time-based polling at N/10 granularity fires promotions close to the
        // target interval without paying for a nanoTime() call every fork.
        executor = new VirtualThreadExecutor(
                HeartbeatConfig.newBuilder()
                        .heartbeatPeriodNanos(heartbeatPeriodNanos)
                        .promotionCostNanos(measuredTauNanos)
                        .pollingStrategyFactory(
                                () -> TimeBasedPolling.forHeartbeatPeriod(heartbeatPeriodNanos))
                        .build());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        executor.close();
    }

    /** Heartbeat-scheduled recursive Fibonacci. */
    @Benchmark
    public void heartbeat(Blackhole bh) throws ExecutionException {
        bh.consume(executor.submit(new FibTask(FIB_N)));
    }

    /** Sequential baseline — same computation with zero scheduling overhead. */
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

    // ---- sequential reference ------------------------------------------

    static long seqFib(int n) {
        if (n <= 1) return n;
        return seqFib(n - 1) + seqFib(n - 2);
    }
}
