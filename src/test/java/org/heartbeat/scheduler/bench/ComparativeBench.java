package org.heartbeat.scheduler.bench;

import org.heartbeat.scheduler.core.HeartbeatConfig;
import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
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
 *       {@code Runtime.availableProcessors()}). The heartbeat executor uses that
 *       JVM-global setting; the {@code numCarriers} parameter controls only the
 *       direct ForkJoinPool baseline in this in-process benchmark.</li>
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
public class ComparativeBench extends AbstractHeartbeatBench {

    /** Parallelism level — varies the ForkJoinPool size (see class javadoc for heartbeat caveat). */
    @Param({"1", "2", "4", "8"})
    public int numCarriers;

    private static final int FIB_N = 35;

    private ForkJoinPool fjPool;

    @Setup(Level.Trial)
    public void setup() {
        executor = new VirtualThreadExecutor(
                HeartbeatConfig.newBuilder()
                        .heartbeatPeriodNanos(CALIBRATION.recommendedHeartbeatPeriod())
                        .promotionCostNanos(CALIBRATION.promotionCost())
                        .build());
        fjPool = new ForkJoinPool(numCarriers);
    }

    @TearDown(Level.Trial)
    public void closefjPool() throws InterruptedException {
        fjPool.shutdown();
        fjPool.awaitTermination(10, TimeUnit.SECONDS);
    }

    @Benchmark
    public void heartbeat(Blackhole bh) throws ExecutionException {
        bh.consume(executor.submit(new Tasks.FibTask(FIB_N)));
    }

    @Benchmark
    public void forkJoinPool(Blackhole bh) throws Exception {
        bh.consume(fjPool.submit(new Tasks.FjFib(FIB_N)).get());
    }

    @Benchmark
    public void sequential(Blackhole bh) {
        bh.consume(Tasks.seqFib(FIB_N));
    }
}
