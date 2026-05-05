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
 * PBBS-style integer radix-sort benchmark.
 *
 * <p>All modes receive a fresh mutable copy of the same generated input. The
 * sequential mode is a signed-int LSD radix sort. The ForkJoinPool and
 * Heartbeat modes recursively split the input, radix-sort leaves, then merge
 * sorted halves; the threshold parameter makes granularity visible in results.
 */
public class PbbsRadixSortBench extends AbstractHeartbeatBench {

    @Param({"4096", "65536"})
    public int size;

    @Param({"random", "exponential_like", "bounded_random"})
    public String distribution;

    @Param({"1024", "8192"})
    public int threshold;

    private int[] sourceData;
    private ForkJoinPool forkJoinPool;

    @Setup(Level.Trial)
    public void setup() {
        PbbsDataset.Descriptor descriptor = new PbbsDataset.Descriptor(
                "radix-sort", size, distribution, 42L);
        sourceData = PbbsInputs.integers(descriptor);
        executor = new VirtualThreadExecutor(defaultConfig());
        forkJoinPool = ForkJoinPool.commonPool();
    }

    @Benchmark
    public void sequential(Blackhole bh) {
        int[] values = PbbsCopies.copy(sourceData);
        radixSort(values);
        consumeSorted(values, bh);
    }

    @Benchmark
    public void forkJoinPool(Blackhole bh) {
        int[] values = PbbsCopies.copy(sourceData);
        forkJoinPool.invoke(new FjRadixSort(values, new int[values.length], 0, values.length, threshold));
        consumeSorted(values, bh);
    }

    @Benchmark
    public void heartbeat(Blackhole bh) throws ExecutionException {
        int[] values = PbbsCopies.copy(sourceData);
        executor.submit(new HbRadixSort(values, new int[values.length], 0, values.length, threshold));
        consumeSorted(values, bh);
    }

    static void radixSort(int[] values) {
        if (values.length <= 1) {
            return;
        }
        int[] source = values;
        int[] dest = new int[values.length];
        int[] counts = new int[256];
        int[] positions = new int[256];

        for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
            Arrays.fill(counts, 0);
            for (int value : source) {
                counts[(sortKey(value) >>> shift) & 0xff]++;
            }

            int sum = 0;
            for (int i = 0; i < counts.length; i++) {
                positions[i] = sum;
                sum += counts[i];
            }

            for (int value : source) {
                int bucket = (sortKey(value) >>> shift) & 0xff;
                dest[positions[bucket]++] = value;
            }

            int[] tmp = source;
            source = dest;
            dest = tmp;
        }

        if (source != values) {
            System.arraycopy(source, 0, values, 0, values.length);
        }
    }

    static void parallelRadixSort(int[] values, int threshold) {
        ForkJoinPool.commonPool().invoke(
                new FjRadixSort(values, new int[values.length], 0, values.length, threshold));
    }

    static void heartbeatRadixSort(VirtualThreadExecutor executor, int[] values, int threshold)
            throws ExecutionException {
        executor.submit(new HbRadixSort(values, new int[values.length], 0, values.length, threshold));
    }

    private static void sortLeaf(int[] values, int lo, int hi) {
        int[] leaf = Arrays.copyOfRange(values, lo, hi);
        radixSort(leaf);
        System.arraycopy(leaf, 0, values, lo, leaf.length);
    }

    private static void merge(int[] values, int[] scratch, int lo, int mid, int hi) {
        int left = lo;
        int right = mid;
        int out = lo;
        while (left < mid && right < hi) {
            if (values[left] <= values[right]) {
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

    private static int sortKey(int value) {
        return value ^ Integer.MIN_VALUE;
    }

    private static void consumeSorted(int[] values, Blackhole bh) {
        bh.consume(values[values.length >>> 1]);
        bh.consume(values[values.length - 1]);
    }

    static final class FjRadixSort extends RecursiveAction {
        private final int[] values;
        private final int[] scratch;
        private final int lo;
        private final int hi;
        private final int threshold;

        FjRadixSort(int[] values, int[] scratch, int lo, int hi, int threshold) {
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
            FjRadixSort left = new FjRadixSort(values, scratch, lo, mid, threshold);
            FjRadixSort right = new FjRadixSort(values, scratch, mid, hi, threshold);
            left.fork();
            right.compute();
            left.join();
            merge(values, scratch, lo, mid, hi);
        }
    }

    static final class HbRadixSort extends HeartbeatTask<Void> {
        private final int[] values;
        private final int[] scratch;
        private final int lo;
        private final int hi;
        private final int threshold;

        HbRadixSort(int[] values, int[] scratch, int lo, int hi, int threshold) {
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
            HbRadixSort left = new HbRadixSort(values, scratch, lo, mid, threshold);
            HbRadixSort right = new HbRadixSort(values, scratch, mid, hi, threshold);
            fork(left);
            fork(right);
            join(left);
            join(right);
            merge(values, scratch, lo, mid, hi);
            return null;
        }
    }
}
