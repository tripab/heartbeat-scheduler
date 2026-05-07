package org.heartbeat.scheduler.bench.pbbs;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Correctness checks shared by PBBS-style benchmarks and their unit tests.
 */
final class PbbsValidation {

    private PbbsValidation() {}

    static boolean isSorted(int[] values) {
        for (int i = 1; i < values.length; i++) {
            if (values[i - 1] > values[i]) {
                return false;
            }
        }
        return true;
    }

    static boolean isSorted(double[] values) {
        for (int i = 1; i < values.length; i++) {
            if (Double.compare(values[i - 1], values[i]) > 0) {
                return false;
            }
        }
        return true;
    }

    static void requireSorted(int[] values) {
        if (!isSorted(values)) {
            throw new IllegalStateException("expected sorted int output");
        }
    }

    static void requireSorted(double[] values) {
        if (!isSorted(values)) {
            throw new IllegalStateException("expected sorted double output");
        }
    }

    static void requireSameIntMultiset(int[] expected, int[] actual) {
        int[] sortedExpected = expected.clone();
        int[] sortedActual = actual.clone();
        Arrays.sort(sortedExpected);
        Arrays.sort(sortedActual);
        if (!Arrays.equals(sortedExpected, sortedActual)) {
            throw new IllegalStateException("expected matching int multiset");
        }
    }

    static void requireSameDoubleSequence(double[] expected, double[] actual) {
        if (expected.length != actual.length) {
            throw new IllegalStateException("expected matching double output length");
        }
        for (int i = 0; i < expected.length; i++) {
            if (Double.compare(expected[i], actual[i]) != 0) {
                throw new IllegalStateException("expected matching double output");
            }
        }
    }

    static void requireStrictlyUniqueSorted(int[] values) {
        requireSorted(values);
        for (int i = 1; i < values.length; i++) {
            if (values[i - 1] == values[i]) {
                throw new IllegalStateException("expected unique sorted int output");
            }
        }
    }

    static void requireSameUniqueSet(int[] expected, int[] actual) {
        int[] expectedUnique = sortedUnique(expected);
        int[] actualUnique = sortedUnique(actual);
        if (!Arrays.equals(expectedUnique, actualUnique)) {
            throw new IllegalStateException("expected matching unique int set");
        }
    }

    static void requireSamePointSet(PbbsPoint[] expected, PbbsPoint[] actual) {
        Set<PbbsPoint> expectedSet = new HashSet<>(Arrays.asList(expected));
        Set<PbbsPoint> actualSet = new HashSet<>(Arrays.asList(actual));
        if (!expectedSet.equals(actualSet)) {
            throw new IllegalStateException("expected matching point set");
        }
    }

    static void requireValidGraphEndpoints(PbbsGraph graph) {
        requireValidGraphEndpoints(graph.vertexCount(), graph.adjacency());
    }

    static void requireValidGraphEndpoints(int vertexCount, int[][] adjacency) {
        for (int from = 0; from < adjacency.length; from++) {
            for (int to : adjacency[from]) {
                if (to < 0 || to >= vertexCount) {
                    throw new IllegalStateException("graph endpoint out of range");
                }
            }
        }
    }

    static void requireValidSpanningForest(PbbsGraph graph, int[] parent) {
        int vertexCount = graph.vertexCount();
        int[][] adjacency = graph.adjacency();
        if (parent.length != vertexCount) {
            throw new IllegalStateException("spanning forest parent length mismatch");
        }

        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int predecessor = parent[vertex];
            if (predecessor < 0 || predecessor >= vertexCount) {
                throw new IllegalStateException("spanning forest parent out of range");
            }
            if (predecessor != vertex && !hasEdge(adjacency[predecessor], vertex)) {
                throw new IllegalStateException("spanning forest parent edge is not in graph");
            }
            requireRootedParentChain(parent, vertex);
        }
    }

    static int[] sortedUnique(int[] values) {
        if (values.length == 0) {
            return new int[0];
        }
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        int uniqueCount = 1;
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i] != sorted[uniqueCount - 1]) {
                sorted[uniqueCount++] = sorted[i];
            }
        }
        return Arrays.copyOf(sorted, uniqueCount);
    }

    private static boolean hasEdge(int[] neighbors, int target) {
        for (int neighbor : neighbors) {
            if (neighbor == target) {
                return true;
            }
        }
        return false;
    }

    private static void requireRootedParentChain(int[] parent, int vertex) {
        int cursor = vertex;
        for (int depth = 0; depth <= parent.length; depth++) {
            int predecessor = parent[cursor];
            if (predecessor == cursor) {
                return;
            }
            cursor = predecessor;
        }
        throw new IllegalStateException("spanning forest parent chain contains a cycle");
    }
}
