package org.heartbeat.scheduler.bench.pbbs;

import org.heartbeat.scheduler.bench.AbstractHeartbeatBench;
import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.task.HeartbeatTask;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * First PBBS-style graph benchmark: directed spanning forest.
 *
 * <p>Each mode visits every vertex by starting a new BFS tree from each still
 * unvisited vertex. Parallel modes expand each frontier with recursive
 * divide-and-conquer tasks over the frontier array.
 */
public class PbbsSpanningBench extends AbstractHeartbeatBench {

    @Param({"4096", "32768"})
    public int size;

    @Param({"rmat_like", "grid", "random_sparse"})
    public String distribution;

    @Param({"256", "1024"})
    public int threshold;

    private PbbsGraph graph;
    private ForkJoinPool forkJoinPool;

    @Setup(Level.Trial)
    public void setup() {
        PbbsDataset.Descriptor descriptor = new PbbsDataset.Descriptor(
                "spanning", size, distribution, 46L);
        graph = PbbsInputs.graph(descriptor);
        executor = new VirtualThreadExecutor(defaultConfig());
        forkJoinPool = ForkJoinPool.commonPool();
    }

    @Benchmark
    public void sequential(Blackhole bh) {
        int[] parent = sequentialSpanningForest(graph);
        consumeForest(parent, bh);
    }

    @Benchmark
    public void forkJoinPool(Blackhole bh) {
        int[] parent = forkJoinSpanningForest(graph, threshold, forkJoinPool);
        consumeForest(parent, bh);
    }

    @Benchmark
    public void heartbeat(Blackhole bh) throws ExecutionException {
        int[] parent = heartbeatSpanningForest(graph, threshold, executor);
        consumeForest(parent, bh);
    }

    static int[] sequentialSpanningForest(PbbsGraph graph) {
        int[][] adjacency = graph.adjacency();
        int[] parent = new int[graph.vertexCount()];
        Arrays.fill(parent, -1);
        int[] queue = new int[graph.vertexCount()];

        for (int root = 0; root < parent.length; root++) {
            if (parent[root] != -1) {
                continue;
            }
            parent[root] = root;
            int head = 0;
            int tail = 0;
            queue[tail++] = root;
            while (head < tail) {
                int vertex = queue[head++];
                for (int neighbor : adjacency[vertex]) {
                    if (parent[neighbor] == -1) {
                        parent[neighbor] = vertex;
                        queue[tail++] = neighbor;
                    }
                }
            }
        }
        return parent;
    }

    static int[] forkJoinSpanningForest(PbbsGraph graph, int threshold, ForkJoinPool pool) {
        int[][] adjacency = graph.adjacency();
        AtomicIntegerArray parent = emptyParents(graph.vertexCount());

        for (int root = 0; root < graph.vertexCount(); root++) {
            if (!parent.compareAndSet(root, -1, root)) {
                continue;
            }
            int[] frontier = {root};
            while (frontier.length > 0) {
                ConcurrentLinkedQueue<Integer> next = new ConcurrentLinkedQueue<>();
                pool.invoke(new FjFrontier(adjacency, parent, frontier, 0, frontier.length,
                        threshold, next));
                frontier = toArray(next);
            }
        }
        return toArray(parent);
    }

    static int[] heartbeatSpanningForest(PbbsGraph graph, int threshold, VirtualThreadExecutor executor)
            throws ExecutionException {
        int[][] adjacency = graph.adjacency();
        AtomicIntegerArray parent = emptyParents(graph.vertexCount());

        for (int root = 0; root < graph.vertexCount(); root++) {
            if (!parent.compareAndSet(root, -1, root)) {
                continue;
            }
            int[] frontier = {root};
            while (frontier.length > 0) {
                ConcurrentLinkedQueue<Integer> next = new ConcurrentLinkedQueue<>();
                executor.submit(new HbFrontier(adjacency, parent, frontier, 0, frontier.length,
                        threshold, next));
                frontier = toArray(next);
            }
        }
        return toArray(parent);
    }

    private static AtomicIntegerArray emptyParents(int vertexCount) {
        AtomicIntegerArray parent = new AtomicIntegerArray(vertexCount);
        for (int i = 0; i < vertexCount; i++) {
            parent.set(i, -1);
        }
        return parent;
    }

    private static int[] toArray(AtomicIntegerArray values) {
        int[] result = new int[values.length()];
        for (int i = 0; i < values.length(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private static int[] toArray(Queue<Integer> values) {
        int[] result = new int[values.size()];
        int index = 0;
        for (int value : values) {
            result[index++] = value;
        }
        return result;
    }

    private static void consumeForest(int[] parent, Blackhole bh) {
        bh.consume(parent.length);
        bh.consume(rootCount(parent));
        if (parent.length > 0) {
            bh.consume(parent[parent.length >>> 1]);
            bh.consume(parent[parent.length - 1]);
        }
    }

    static int rootCount(int[] parent) {
        int roots = 0;
        for (int vertex = 0; vertex < parent.length; vertex++) {
            if (parent[vertex] == vertex) {
                roots++;
            }
        }
        return roots;
    }

    static final class FjFrontier extends RecursiveAction {
        private final int[][] adjacency;
        private final AtomicIntegerArray parent;
        private final int[] frontier;
        private final int lo;
        private final int hi;
        private final int threshold;
        private final ConcurrentLinkedQueue<Integer> next;

        FjFrontier(int[][] adjacency, AtomicIntegerArray parent, int[] frontier,
                   int lo, int hi, int threshold, ConcurrentLinkedQueue<Integer> next) {
            this.adjacency = adjacency;
            this.parent = parent;
            this.frontier = frontier;
            this.lo = lo;
            this.hi = hi;
            this.threshold = threshold;
            this.next = next;
        }

        @Override
        protected void compute() {
            if (hi - lo <= threshold) {
                expandFrontier(adjacency, parent, frontier, lo, hi, next);
                return;
            }
            int mid = lo + ((hi - lo) >>> 1);
            FjFrontier left = new FjFrontier(adjacency, parent, frontier, lo, mid,
                    threshold, next);
            FjFrontier right = new FjFrontier(adjacency, parent, frontier, mid, hi,
                    threshold, next);
            left.fork();
            right.compute();
            left.join();
        }
    }

    static final class HbFrontier extends HeartbeatTask<Void> {
        private final int[][] adjacency;
        private final AtomicIntegerArray parent;
        private final int[] frontier;
        private final int lo;
        private final int hi;
        private final int threshold;
        private final ConcurrentLinkedQueue<Integer> next;

        HbFrontier(int[][] adjacency, AtomicIntegerArray parent, int[] frontier,
                   int lo, int hi, int threshold, ConcurrentLinkedQueue<Integer> next) {
            this.adjacency = adjacency;
            this.parent = parent;
            this.frontier = frontier;
            this.lo = lo;
            this.hi = hi;
            this.threshold = threshold;
            this.next = next;
        }

        @Override
        protected Void compute() {
            if (hi - lo <= threshold) {
                expandFrontier(adjacency, parent, frontier, lo, hi, next);
                return null;
            }
            int mid = lo + ((hi - lo) >>> 1);
            HbFrontier left = new HbFrontier(adjacency, parent, frontier, lo, mid,
                    threshold, next);
            HbFrontier right = new HbFrontier(adjacency, parent, frontier, mid, hi,
                    threshold, next);
            fork(left);
            fork(right);
            join(left);
            join(right);
            return null;
        }
    }

    private static void expandFrontier(int[][] adjacency, AtomicIntegerArray parent,
                                       int[] frontier, int lo, int hi,
                                       ConcurrentLinkedQueue<Integer> next) {
        for (int i = lo; i < hi; i++) {
            int vertex = frontier[i];
            for (int neighbor : adjacency[vertex]) {
                if (parent.compareAndSet(neighbor, -1, vertex)) {
                    next.add(neighbor);
                }
            }
        }
    }
}
