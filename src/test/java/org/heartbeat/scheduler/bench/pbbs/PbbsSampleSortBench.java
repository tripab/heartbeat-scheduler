package org.heartbeat.scheduler.bench.pbbs;

import org.heartbeat.scheduler.bench.AbstractHeartbeatBench;
import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.task.HeartbeatTask;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;

/**
 * PBBS-style sample-sort benchmark over generated double keys.
 *
 * <p>The milestone implementation uses deterministic divide-and-conquer
 * comparison sorting rather than a fully sampled splitter tree. This keeps the
 * Java-vs-Java comparison fair while exposing the same PBBS benchmark knobs:
 * distribution, size, and task granularity threshold.
 */
public class PbbsSampleSortBench extends AbstractHeartbeatBench {

    @Param({"4096", "65536"})
    public int size;

    @Param({"random", "almost_sorted"})
    public String distribution;

    @Param({"1024", "8192"})
    public int threshold;

    private double[] sourceData;
    private ForkJoinPool forkJoinPool;

    @Setup(Level.Trial)
    public void setup() {
        PbbsDataset.Descriptor descriptor = new PbbsDataset.Descriptor(
                "sample-sort", size, distribution, 43L);
        sourceData = PbbsInputs.doubles(descriptor);
        executor = new VirtualThreadExecutor(defaultConfig());
        forkJoinPool = ForkJoinPool.commonPool();
    }

    @Benchmark
    public void sequential(Blackhole bh) {
        double[] values = PbbsCopies.copy(sourceData);
        comparisonSort(values);
        consumeSorted(values, bh);
    }

    @Benchmark
    public void forkJoinPool(Blackhole bh) {
        double[] values = PbbsCopies.copy(sourceData);
        forkJoinPool.invoke(new FjSampleSort(values, new double[values.length],
                0, values.length, threshold));
        consumeSorted(values, bh);
    }

    @Benchmark
    public void heartbeat(Blackhole bh) throws ExecutionException {
        double[] values = PbbsCopies.copy(sourceData);
        executor.submit(new HbSampleSort(values, new double[values.length],
                0, values.length, threshold));
        consumeSorted(values, bh);
    }

    static void comparisonSort(double[] values) {
        Arrays.sort(values);
    }

    static void forkJoinSampleSort(double[] values, int threshold) {
        ForkJoinPool.commonPool().invoke(new FjSampleSort(values, new double[values.length],
                0, values.length, threshold));
    }

    static void heartbeatSampleSort(VirtualThreadExecutor executor, double[] values, int threshold)
            throws ExecutionException {
        executor.submit(new HbSampleSort(values, new double[values.length],
                0, values.length, threshold));
    }

    private static void sortLeaf(double[] values, int lo, int hi) {
        Arrays.sort(values, lo, hi);
    }

    private static void merge(double[] values, double[] scratch, int lo, int mid, int hi) {
        int left = lo;
        int right = mid;
        int out = lo;
        while (left < mid && right < hi) {
            if (Double.compare(values[left], values[right]) <= 0) {
                scratch[out++] = values[left++];
            } else {
                scratch[out++] = values[right++];
            }
        }
        while (left < mid) {
            scratch[out++] = values[left++];
        }
        while (right < hi) {
            scratch[out++] = values[right++];
        }
        System.arraycopy(scratch, lo, values, lo, hi - lo);
    }

    private static void consumeSorted(double[] values, Blackhole bh) {
        bh.consume(values[values.length >>> 1]);
        bh.consume(values[values.length - 1]);
    }

    static final class FjSampleSort extends RecursiveAction {
        private final double[] values;
        private final double[] scratch;
        private final int lo;
        private final int hi;
        private final int threshold;

        FjSampleSort(double[] values, double[] scratch, int lo, int hi, int threshold) {
            this.values = values;
            this.scratch = scratch;
            this.lo = lo;
            this.hi = hi;
            this.threshold = threshold;
        }

        @Override
        protected void compute() {
            if (hi - lo <= threshold) {
                sortLeaf(values, lo, hi);
                return;
            }
            int mid = lo + ((hi - lo) >>> 1);
            FjSampleSort left = new FjSampleSort(values, scratch, lo, mid, threshold);
            FjSampleSort right = new FjSampleSort(values, scratch, mid, hi, threshold);
            left.fork();
            right.compute();
            left.join();
            merge(values, scratch, lo, mid, hi);
        }
    }

    static final class HbSampleSort extends HeartbeatTask<Void> {
        private final double[] values;
        private final double[] scratch;
        private final int lo;
        private final int hi;
        private final int threshold;

        HbSampleSort(double[] values, double[] scratch, int lo, int hi, int threshold) {
            this.values = values;
            this.scratch = scratch;
            this.lo = lo;
            this.hi = hi;
            this.threshold = threshold;
        }

        @Override
        protected Void compute() {
            if (hi - lo <= threshold) {
                sortLeaf(values, lo, hi);
                return null;
            }
            int mid = lo + ((hi - lo) >>> 1);
            HbSampleSort left = new HbSampleSort(values, scratch, lo, mid, threshold);
            HbSampleSort right = new HbSampleSort(values, scratch, mid, hi, threshold);
            fork(left);
            fork(right);
            join(left);
            join(right);
            merge(values, scratch, lo, mid, hi);
            return null;
        }
    }
}
