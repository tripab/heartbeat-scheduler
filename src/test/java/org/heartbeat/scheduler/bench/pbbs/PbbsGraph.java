package org.heartbeat.scheduler.bench.pbbs;

/**
 * Immutable adjacency-list graph input for PBBS-style graph benchmarks.
 */
record PbbsGraph(int vertexCount, int[][] adjacency) {
    PbbsGraph {
        if (vertexCount < 0) {
            throw new IllegalArgumentException("vertex count must be non-negative");
        }
        if (adjacency.length != vertexCount) {
            throw new IllegalArgumentException("adjacency length must match vertex count");
        }
        adjacency = PbbsCopies.copy(adjacency);
        PbbsValidation.requireValidGraphEndpoints(vertexCount, adjacency);
    }

    @Override
    public int[][] adjacency() {
        return PbbsCopies.copy(adjacency);
    }
}
