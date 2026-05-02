package org.heartbeat.scheduler.bench;

import org.heartbeat.scheduler.core.HeartbeatConfig;
import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.task.HeartbeatTask;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * JMH benchmark: BFS on a synthetic random graph.
 *
 * <p>Each BFS level's frontier is expanded in parallel using divide-and-conquer:
 * the frontier array is split in half, each half forks, and the two halves
 * merge their successor sets. This tests load balancing under irregular,
 * data-dependent parallelism — very different from the balanced recursive
 * patterns in FibBench and MatmulBench.
 *
 * <p>Graph: {@code numNodes} nodes, each with exactly {@code AVG_DEGREE} randomly
 * chosen out-edges (a Erdős–Rényi-style construction with fixed seed).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(value = 3, jvmArgsPrepend = "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED")
public class BfsBench {

    @Param({"10000", "50000"})
    public int numNodes;

    private static final int AVG_DEGREE = 10;
    private static final int THRESHOLD = 256;

    /** Adjacency list representation. */
    private int[][] adj;
    private VirtualThreadExecutor executor;

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(42);
        adj = new int[numNodes][];
        for (int i = 0; i < numNodes; i++) {
            adj[i] = new int[AVG_DEGREE];
            for (int d = 0; d < AVG_DEGREE; d++) {
                adj[i][d] = rng.nextInt(numNodes);
            }
        }
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
        bh.consume(heartbeatBfs(0));
    }

    @Benchmark
    public void sequential(Blackhole bh) {
        bh.consume(seqBfs(0));
    }

    @Benchmark
    public void forkJoinPool(Blackhole bh) throws Exception {
        bh.consume(fjBfs(0));
    }

    // ---- heartbeat BFS -------------------------------------------------

    private int heartbeatBfs(int source) throws ExecutionException {
        AtomicIntegerArray visited = new AtomicIntegerArray(numNodes);
        visited.set(source, 1);

        int[] frontier = {source};
        int totalVisited = 1;

        while (frontier.length > 0) {
            ConcurrentLinkedQueue<Integer> nextQueue = new ConcurrentLinkedQueue<>();
            executor.submit(new FrontierTask(frontier, 0, frontier.length,
                    adj, visited, nextQueue));

            // Drain next frontier
            int[] next = new int[nextQueue.size()];
            int idx = 0;
            for (int node : nextQueue) next[idx++] = node;
            totalVisited += next.length;
            frontier = next;
        }
        return totalVisited;
    }

    static final class FrontierTask extends HeartbeatTask<Void> {
        private final int[] frontier;
        private final int lo, hi;
        private final int[][] adj;
        private final AtomicIntegerArray visited;
        private final ConcurrentLinkedQueue<Integer> nextFrontier;

        FrontierTask(int[] frontier, int lo, int hi,
                     int[][] adj, AtomicIntegerArray visited,
                     ConcurrentLinkedQueue<Integer> nextFrontier) {
            this.frontier = frontier;
            this.lo = lo;
            this.hi = hi;
            this.adj = adj;
            this.visited = visited;
            this.nextFrontier = nextFrontier;
        }

        @Override
        protected Void compute() {
            if (hi - lo <= THRESHOLD) {
                for (int i = lo; i < hi; i++) {
                    for (int neighbor : adj[frontier[i]]) {
                        if (visited.compareAndSet(neighbor, 0, 1)) {
                            nextFrontier.add(neighbor);
                        }
                    }
                }
                return null;
            }
            int mid = (lo + hi) / 2;
            FrontierTask left = new FrontierTask(
                    frontier, lo, mid, adj, visited, nextFrontier);
            FrontierTask right = new FrontierTask(
                    frontier, mid, hi, adj, visited, nextFrontier);
            fork(left);
            fork(right);
            join(left);
            join(right);
            return null;
        }
    }

    // ---- ForkJoinPool BFS ----------------------------------------------

    private int fjBfs(int source) throws Exception {
        AtomicIntegerArray visited = new AtomicIntegerArray(numNodes);
        visited.set(source, 1);

        int[] frontier = {source};
        int totalVisited = 1;

        while (frontier.length > 0) {
            ConcurrentLinkedQueue<Integer> nextQueue = new ConcurrentLinkedQueue<>();
            ForkJoinPool.commonPool().submit(
                    new FjFrontier(frontier, 0, frontier.length, adj, visited, nextQueue)).get();

            int[] next = new int[nextQueue.size()];
            int idx = 0;
            for (int node : nextQueue) next[idx++] = node;
            totalVisited += next.length;
            frontier = next;
        }
        return totalVisited;
    }

    static final class FjFrontier extends RecursiveAction {
        private final int[] frontier;
        private final int lo, hi;
        private final int[][] adj;
        private final AtomicIntegerArray visited;
        private final ConcurrentLinkedQueue<Integer> nextFrontier;

        FjFrontier(int[] frontier, int lo, int hi,
                   int[][] adj, AtomicIntegerArray visited,
                   ConcurrentLinkedQueue<Integer> nextFrontier) {
            this.frontier = frontier;
            this.lo = lo;
            this.hi = hi;
            this.adj = adj;
            this.visited = visited;
            this.nextFrontier = nextFrontier;
        }

        @Override
        protected void compute() {
            if (hi - lo <= THRESHOLD) {
                for (int i = lo; i < hi; i++) {
                    for (int neighbor : adj[frontier[i]]) {
                        if (visited.compareAndSet(neighbor, 0, 1)) {
                            nextFrontier.add(neighbor);
                        }
                    }
                }
                return;
            }
            int mid = (lo + hi) / 2;
            FjFrontier left = new FjFrontier(
                    frontier, lo, mid, adj, visited, nextFrontier);
            FjFrontier right = new FjFrontier(
                    frontier, mid, hi, adj, visited, nextFrontier);
            left.fork();
            right.compute();
            left.join();
        }
    }

    // ---- sequential BFS ------------------------------------------------

    private int seqBfs(int source) {
        boolean[] visited = new boolean[numNodes];
        visited[source] = true;
        Queue<Integer> queue = new ArrayDeque<>();
        queue.add(source);
        int count = 1;
        while (!queue.isEmpty()) {
            int node = queue.poll();
            for (int neighbor : adj[node]) {
                if (!visited[neighbor]) {
                    visited[neighbor] = true;
                    queue.add(neighbor);
                    count++;
                }
            }
        }
        return count;
    }
}
