package org.heartbeat.scheduler.bench;

import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.task.HeartbeatTask;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.*;

/**
 * JMH benchmark: parallel quicksort.
 *
 * <p>Divide-and-conquer with potentially skewed splits (random pivot) — tests
 * promotion fairness when work is unevenly distributed between the two halves.
 *
 * <p>Each benchmark method receives a fresh copy of the input so array state
 * does not carry over between invocations.
 */
public class QuicksortBench extends AbstractHeartbeatBench {

    @Param({"100000", "500000"})
    public int size;

    /** Granularity threshold: sub-arrays ≤ this size are sorted sequentially. */
    private static final int THRESHOLD = 1000;

    private int[] sourceData;

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(42);
        sourceData = new int[size];
        for (int i = 0; i < size; i++) sourceData[i] = rng.nextInt();
        executor = new VirtualThreadExecutor(defaultConfig());
    }

    @Benchmark
    public void heartbeat(Blackhole bh) throws ExecutionException {
        int[] arr = sourceData.clone();
        executor.submit(new QsTask(arr, 0, arr.length));
        bh.consume(arr[arr.length / 2]);
    }

    @Benchmark
    public void sequential(Blackhole bh) {
        int[] arr = sourceData.clone();
        seqQuicksort(arr, 0, arr.length);
        bh.consume(arr[arr.length / 2]);
    }

    @Benchmark
    public void jdkArraysSort(Blackhole bh) {
        int[] arr = sourceData.clone();
        Arrays.sort(arr);
        bh.consume(arr[arr.length / 2]);
    }

    @Benchmark
    public void forkJoinPool(Blackhole bh) throws Exception {
        int[] arr = sourceData.clone();
        ForkJoinPool.commonPool().submit(new FjQs(arr, 0, arr.length)).get();
        bh.consume(arr[arr.length / 2]);
    }

    // ---- heartbeat task ------------------------------------------------

    static final class QsTask extends HeartbeatTask<Void> {
        private final int[] arr;
        private final int lo, hi;

        QsTask(int[] arr, int lo, int hi) {
            this.arr = arr;
            this.lo = lo;
            this.hi = hi;
        }

        @Override
        protected Void compute() {
            if (hi - lo <= THRESHOLD) {
                seqQuicksort(arr, lo, hi);
                return null;
            }
            int p = partition(arr, lo, hi);
            QsTask left = new QsTask(arr, lo, p);
            QsTask right = new QsTask(arr, p + 1, hi);
            fork(left);
            fork(right);
            join(left);
            join(right);
            return null;
        }
    }

    // ---- ForkJoinPool task ---------------------------------------------

    static final class FjQs extends RecursiveAction {
        private final int[] arr;
        private final int lo, hi;

        FjQs(int[] arr, int lo, int hi) {
            this.arr = arr;
            this.lo = lo;
            this.hi = hi;
        }

        @Override
        protected void compute() {
            if (hi - lo <= THRESHOLD) {
                seqQuicksort(arr, lo, hi);
                return;
            }
            int p = partition(arr, lo, hi);
            FjQs left = new FjQs(arr, lo, p);
            FjQs right = new FjQs(arr, p + 1, hi);
            left.fork();
            right.compute();
            left.join();
        }
    }

    // ---- shared helpers ------------------------------------------------

    static int partition(int[] arr, int lo, int hi) {
        int pivot = arr[lo + (hi - lo) / 2];
        int i = lo - 1, j = hi;
        while (true) {
            do { i++; } while (arr[i] < pivot);
            do { j--; } while (arr[j] > pivot);
            if (i >= j) return j;
            int tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
        }
    }

    static void seqQuicksort(int[] arr, int lo, int hi) {
        if (hi - lo <= 1) return;
        int p = partition(arr, lo, hi);
        seqQuicksort(arr, lo, p);
        seqQuicksort(arr, p + 1, hi);
    }
}
