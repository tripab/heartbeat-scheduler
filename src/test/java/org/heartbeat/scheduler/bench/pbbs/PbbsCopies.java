package org.heartbeat.scheduler.bench.pbbs;

/**
 * Defensive-copy helpers for mutable PBBS benchmark inputs.
 */
final class PbbsCopies {

    private PbbsCopies() {}

    static int[] copy(int[] values) {
        return values.clone();
    }

    static double[] copy(double[] values) {
        return values.clone();
    }

    static PbbsPoint[] copy(PbbsPoint[] points) {
        return points.clone();
    }

    static int[][] copy(int[][] adjacency) {
        int[][] result = new int[adjacency.length][];
        for (int i = 0; i < adjacency.length; i++) {
            result[i] = adjacency[i].clone();
        }
        return result;
    }

    static PbbsGraph copy(PbbsGraph graph) {
        return new PbbsGraph(graph.vertexCount(), graph.adjacency());
    }
}
