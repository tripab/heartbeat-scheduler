package org.heartbeat.scheduler.bench.pbbs;

import org.heartbeat.scheduler.bench.AbstractHeartbeatBench;
import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.task.HeartbeatTask;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

/**
 * PBBS-style convex-hull benchmark over generated 2D point sets.
 *
 * <p>The hull algorithm is Andrew's monotonic chain. Parallel modes compute
 * hulls for recursive subsets, concatenate boundary candidates, then hull the
 * reduced point set. The result is a canonical counter-clockwise hull with no
 * repeated closing vertex.
 */
public class PbbsConvexHullBench extends AbstractHeartbeatBench {

    @Param({"4096", "65536"})
    public int size;

    @Param({"in_circle", "on_circle", "kuzmin_like"})
    public String distribution;

    @Param({"1024", "8192"})
    public int threshold;

    private PbbsPoint[] sourceData;
    private ForkJoinPool forkJoinPool;

    @Setup(Level.Trial)
    public void setup() {
        PbbsDataset.Descriptor descriptor = new PbbsDataset.Descriptor(
                "convex-hull", size, distribution, 45L);
        sourceData = PbbsInputs.points(descriptor);
        executor = new VirtualThreadExecutor(defaultConfig());
        forkJoinPool = ForkJoinPool.commonPool();
    }

    @Benchmark
    public void sequential(Blackhole bh) {
        PbbsPoint[] hull = convexHull(sourceData);
        consumeHull(hull, bh);
    }

    @Benchmark
    public void forkJoinPool(Blackhole bh) {
        PbbsPoint[] hull = forkJoinPool.invoke(new FjConvexHull(
                PbbsCopies.copy(sourceData), 0, sourceData.length, threshold));
        consumeHull(hull, bh);
    }

    @Benchmark
    public void heartbeat(Blackhole bh) throws ExecutionException {
        PbbsPoint[] hull = executor.submit(new HbConvexHull(
                PbbsCopies.copy(sourceData), 0, sourceData.length, threshold));
        consumeHull(hull, bh);
    }

    static PbbsPoint[] convexHull(PbbsPoint[] points) {
        if (points.length <= 1) {
            return PbbsCopies.copy(points);
        }

        PbbsPoint[] sorted = PbbsCopies.copy(points);
        Arrays.sort(sorted, POINT_ORDER);

        PbbsPoint[] stack = new PbbsPoint[sorted.length * 2];
        int size = 0;

        for (PbbsPoint point : sorted) {
            while (size >= 2 && cross(stack[size - 2], stack[size - 1], point) <= 0.0) {
                size--;
            }
            stack[size++] = point;
        }

        int lowerSize = size;
        for (int i = sorted.length - 2; i >= 0; i--) {
            PbbsPoint point = sorted[i];
            while (size > lowerSize && cross(stack[size - 2], stack[size - 1], point) <= 0.0) {
                size--;
            }
            stack[size++] = point;
        }

        if (size > 1) {
            size--;
        }
        return Arrays.copyOf(stack, size);
    }

    static PbbsPoint[] forkJoinHull(PbbsPoint[] points, int threshold) {
        return ForkJoinPool.commonPool().invoke(
                new FjConvexHull(points, 0, points.length, threshold));
    }

    static PbbsPoint[] heartbeatHull(VirtualThreadExecutor executor, PbbsPoint[] points, int threshold)
            throws ExecutionException {
        return executor.submit(new HbConvexHull(points, 0, points.length, threshold));
    }

    private static PbbsPoint[] mergeHulls(PbbsPoint[] left, PbbsPoint[] right) {
        PbbsPoint[] candidates = new PbbsPoint[left.length + right.length];
        System.arraycopy(left, 0, candidates, 0, left.length);
        System.arraycopy(right, 0, candidates, left.length, right.length);
        return convexHull(candidates);
    }

    private static void consumeHull(PbbsPoint[] hull, Blackhole bh) {
        bh.consume(hull.length);
        if (hull.length > 0) {
            PbbsPoint first = hull[0];
            PbbsPoint last = hull[hull.length - 1];
            bh.consume(first.x() + first.y());
            bh.consume(last.x() + last.y());
        }
    }

    private static double cross(PbbsPoint origin, PbbsPoint a, PbbsPoint b) {
        return (a.x() - origin.x()) * (b.y() - origin.y())
                - (a.y() - origin.y()) * (b.x() - origin.x());
    }

    private static final Comparator<PbbsPoint> POINT_ORDER =
            Comparator.comparingDouble(PbbsPoint::x)
                    .thenComparingDouble(PbbsPoint::y);

    static final class FjConvexHull extends RecursiveTask<PbbsPoint[]> {
        private final PbbsPoint[] points;
        private final int lo;
        private final int hi;
        private final int threshold;

        FjConvexHull(PbbsPoint[] points, int lo, int hi, int threshold) {
            this.points = points;
            this.lo = lo;
            this.hi = hi;
            this.threshold = threshold;
        }

        @Override
        protected PbbsPoint[] compute() {
            if (hi - lo <= threshold) {
                return convexHull(Arrays.copyOfRange(points, lo, hi));
            }
            int mid = lo + ((hi - lo) >>> 1);
            FjConvexHull left = new FjConvexHull(points, lo, mid, threshold);
            FjConvexHull right = new FjConvexHull(points, mid, hi, threshold);
            left.fork();
            PbbsPoint[] rightHull = right.compute();
            PbbsPoint[] leftHull = left.join();
            return mergeHulls(leftHull, rightHull);
        }
    }

    static final class HbConvexHull extends HeartbeatTask<PbbsPoint[]> {
        private final PbbsPoint[] points;
        private final int lo;
        private final int hi;
        private final int threshold;

        HbConvexHull(PbbsPoint[] points, int lo, int hi, int threshold) {
            this.points = points;
            this.lo = lo;
            this.hi = hi;
            this.threshold = threshold;
        }

        @Override
        protected PbbsPoint[] compute() {
            if (hi - lo <= threshold) {
                return convexHull(Arrays.copyOfRange(points, lo, hi));
            }
            int mid = lo + ((hi - lo) >>> 1);
            HbConvexHull left = new HbConvexHull(points, lo, mid, threshold);
            HbConvexHull right = new HbConvexHull(points, mid, hi, threshold);
            fork(left);
            fork(right);
            PbbsPoint[] leftHull = join(left);
            PbbsPoint[] rightHull = join(right);
            return mergeHulls(leftHull, rightHull);
        }
    }
}
