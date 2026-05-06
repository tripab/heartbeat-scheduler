package org.heartbeat.scheduler.bench.pbbs;

import org.heartbeat.scheduler.bench.AbstractHeartbeatBench;
import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.task.HeartbeatTask;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

/**
 * PBBS-style remove-duplicates benchmark over generated integer keys.
 *
 * <p>The deterministic representation is sorted unique output. Parallel modes
 * recursively split, sort-compact leaves, then merge unique sorted halves.
 */
public class PbbsRemoveDuplicatesBench extends AbstractHeartbeatBench {

    @Param({"4096", "65536"})
    public int size;

    @Param({"random", "bounded_random"})
    public String distribution;

    @Param({"1024", "8192"})
    public int threshold;

    private int[] sourceData;
    private ForkJoinPool forkJoinPool;

    @Setup(Level.Trial)
    public void setup() {
        PbbsDataset.Descriptor descriptor = new PbbsDataset.Descriptor(
                "remove-duplicates", size, distribution, 44L);
        sourceData = PbbsInputs.integers(descriptor);
        executor = new VirtualThreadExecutor(defaultConfig());
        forkJoinPool = ForkJoinPool.commonPool();
    }

    @Benchmark
    public void sequential(Blackhole bh) {
        int[] unique = uniqueSorted(sourceData);
        consumeUnique(unique, bh);
    }

    @Benchmark
    public void forkJoinPool(Blackhole bh) {
        int[] unique = forkJoinPool.invoke(new FjRemoveDuplicates(
                PbbsCopies.copy(sourceData), 0, sourceData.length, threshold));
        consumeUnique(unique, bh);
    }

    @Benchmark
    public void heartbeat(Blackhole bh) throws ExecutionException {
        int[] unique = executor.submit(new HbRemoveDuplicates(
                PbbsCopies.copy(sourceData), 0, sourceData.length, threshold));
        consumeUnique(unique, bh);
    }

    static int[] uniqueSorted(int[] values) {
        return PbbsValidation.sortedUnique(values);
    }

    static int[] forkJoinUniqueSorted(int[] values, int threshold) {
        return ForkJoinPool.commonPool().invoke(
                new FjRemoveDuplicates(values, 0, values.length, threshold));
    }

    static int[] heartbeatUniqueSorted(VirtualThreadExecutor executor, int[] values, int threshold)
            throws ExecutionException {
        return executor.submit(new HbRemoveDuplicates(values, 0, values.length, threshold));
    }

    private static int[] mergeUnique(int[] left, int[] right) {
        int[] merged = new int[left.length + right.length];
        int l = 0;
        int r = 0;
        int out = 0;
        boolean hasLast = false;
        int last = 0;

        while (l < left.length || r < right.length) {
            int value;
            if (r >= right.length || (l < left.length && left[l] <= right[r])) {
                value = left[l++];
            } else {
                value = right[r++];
            }

            if (!hasLast || value != last) {
                merged[out++] = value;
                last = value;
                hasLast = true;
            }
        }
        return Arrays.copyOf(merged, out);
    }

    private static void consumeUnique(int[] unique, Blackhole bh) {
        bh.consume(unique.length);
        if (unique.length > 0) {
            bh.consume(unique[unique.length >>> 1]);
            bh.consume(unique[unique.length - 1]);
        }
    }

    static final class FjRemoveDuplicates extends RecursiveTask<int[]> {
        private final int[] values;
        private final int lo;
        private final int hi;
        private final int threshold;

        FjRemoveDuplicates(int[] values, int lo, int hi, int threshold) {
            this.values = values;
            this.lo = lo;
            this.hi = hi;
            this.threshold = threshold;
        }

        @Override
        protected int[] compute() {
            if (hi - lo <= threshold) {
                return uniqueSorted(Arrays.copyOfRange(values, lo, hi));
            }
            int mid = lo + ((hi - lo) >>> 1);
            FjRemoveDuplicates left = new FjRemoveDuplicates(values, lo, mid, threshold);
            FjRemoveDuplicates right = new FjRemoveDuplicates(values, mid, hi, threshold);
            left.fork();
            int[] rightUnique = right.compute();
            int[] leftUnique = left.join();
            return mergeUnique(leftUnique, rightUnique);
        }
    }

    static final class HbRemoveDuplicates extends HeartbeatTask<int[]> {
        private final int[] values;
        private final int lo;
        private final int hi;
        private final int threshold;

        HbRemoveDuplicates(int[] values, int lo, int hi, int threshold) {
            this.values = values;
            this.lo = lo;
            this.hi = hi;
            this.threshold = threshold;
        }

        @Override
        protected int[] compute() {
            if (hi - lo <= threshold) {
                return uniqueSorted(Arrays.copyOfRange(values, lo, hi));
            }
            int mid = lo + ((hi - lo) >>> 1);
            HbRemoveDuplicates left = new HbRemoveDuplicates(values, lo, mid, threshold);
            HbRemoveDuplicates right = new HbRemoveDuplicates(values, mid, hi, threshold);
            fork(left);
            fork(right);
            int[] leftUnique = join(left);
            int[] rightUnique = join(right);
            return mergeUnique(leftUnique, rightUnique);
        }
    }
}
