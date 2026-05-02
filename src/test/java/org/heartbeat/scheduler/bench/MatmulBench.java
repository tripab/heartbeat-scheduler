package org.heartbeat.scheduler.bench;

import org.heartbeat.scheduler.core.HeartbeatConfig;
import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.task.HeartbeatTask;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.*;

/**
 * JMH benchmark: dense matrix multiplication C = A × B.
 *
 * <p>Regular parfor-style parallelism: each chunk of output rows is independent,
 * so this stress-tests bulk parallelism and cache behaviour rather than
 * irregular or recursive patterns.
 *
 * <p>Parallelism structure: rows of C are split recursively until
 * {@code THRESHOLD} rows remain, at which point the sequential dot-product
 * loop runs. Both halves are forked, giving a balanced binary tree.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(value = 3, jvmArgsPrepend = "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED")
public class MatmulBench {

    @Param({"64", "128", "256"})
    public int n;

    private static final int THRESHOLD = 16;

    private double[][] a;
    private double[][] b;
    private VirtualThreadExecutor executor;

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(42);
        a = randomMatrix(n, rng);
        b = randomMatrix(n, rng);
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
        double[][] c = new double[n][n];
        executor.submit(new MmTask(a, b, c, 0, n));
        bh.consume(c[n / 2][n / 2]);
    }

    @Benchmark
    public void sequential(Blackhole bh) {
        double[][] c = new double[n][n];
        seqMul(a, b, c, 0, n);
        bh.consume(c[n / 2][n / 2]);
    }

    @Benchmark
    public void forkJoinPool(Blackhole bh) throws Exception {
        double[][] c = new double[n][n];
        ForkJoinPool.commonPool().submit(new FjMm(a, b, c, 0, n)).get();
        bh.consume(c[n / 2][n / 2]);
    }

    // ---- heartbeat task ------------------------------------------------

    final class MmTask extends HeartbeatTask<Void> {
        private final double[][] a, b, c;
        private final int lo, hi;

        MmTask(double[][] a, double[][] b, double[][] c, int lo, int hi) {
            this.a = a; this.b = b; this.c = c;
            this.lo = lo; this.hi = hi;
        }

        @Override
        protected Void compute() {
            if (hi - lo <= THRESHOLD) {
                seqMul(a, b, c, lo, hi);
                return null;
            }
            int mid = (lo + hi) / 2;
            MmTask left = new MmTask(a, b, c, lo, mid);
            MmTask right = new MmTask(a, b, c, mid, hi);
            fork(left);
            fork(right);
            join(left);
            join(right);
            return null;
        }
    }

    // ---- ForkJoinPool task ---------------------------------------------

    final class FjMm extends RecursiveAction {
        private final double[][] a, b, c;
        private final int lo, hi;

        FjMm(double[][] a, double[][] b, double[][] c, int lo, int hi) {
            this.a = a; this.b = b; this.c = c;
            this.lo = lo; this.hi = hi;
        }

        @Override
        protected void compute() {
            if (hi - lo <= THRESHOLD) {
                seqMul(a, b, c, lo, hi);
                return;
            }
            int mid = (lo + hi) / 2;
            FjMm left = new FjMm(a, b, c, lo, mid);
            FjMm right = new FjMm(a, b, c, mid, hi);
            left.fork();
            right.compute();
            left.join();
        }
    }

    // ---- shared helpers ------------------------------------------------

    static void seqMul(double[][] a, double[][] b, double[][] c, int lo, int hi) {
        int n = a.length;
        for (int i = lo; i < hi; i++) {
            for (int j = 0; j < n; j++) {
                double sum = 0.0;
                for (int k = 0; k < n; k++) sum += a[i][k] * b[k][j];
                c[i][j] = sum;
            }
        }
    }

    private static double[][] randomMatrix(int n, Random rng) {
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                m[i][j] = rng.nextDouble();
        return m;
    }
}
