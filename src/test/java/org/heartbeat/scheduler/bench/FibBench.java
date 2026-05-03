package org.heartbeat.scheduler.bench;

import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

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
public class FibBench extends AbstractHeartbeatBench {

    @Param({"30", "35"})
    public int n;

    @Setup(Level.Trial)
    public void setup() {
        executor = new VirtualThreadExecutor(defaultConfig());
    }

    @Benchmark
    public void heartbeat(Blackhole bh) throws ExecutionException {
        bh.consume(executor.submit(new Tasks.FibTask(n)));
    }

    @Benchmark
    public void sequential(Blackhole bh) {
        bh.consume(Tasks.seqFib(n));
    }

    @Benchmark
    public void forkJoinPool(Blackhole bh) throws Exception {
        bh.consume(ForkJoinPool.commonPool().submit(new Tasks.FjFib(n)).get());
    }
}
