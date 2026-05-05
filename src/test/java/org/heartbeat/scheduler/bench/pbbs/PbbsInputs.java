package org.heartbeat.scheduler.bench.pbbs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Deterministic, in-memory PBBS-style input generators.
 */
final class PbbsInputs {

    private static final int RANDOM_GRAPH_AVG_DEGREE = 4;
    private static final double TWO_PI = 2.0 * Math.PI;

    private PbbsInputs() {}

    static int[] integers(PbbsDataset.Descriptor descriptor) {
        Random rng = rng(descriptor, "ints");
        int[] values = new int[descriptor.size()];
        switch (distribution(descriptor)) {
            case "random" -> {
                for (int i = 0; i < values.length; i++) {
                    values[i] = rng.nextInt();
                }
            }
            case "exponential", "exponential_like" -> {
                double scale = Math.max(1.0, descriptor.size() / 8.0);
                for (int i = 0; i < values.length; i++) {
                    double sample = -Math.log1p(-rng.nextDouble()) * scale;
                    values[i] = (int) Math.min(Integer.MAX_VALUE, Math.round(sample));
                }
            }
            case "bounded", "bounded_random" -> {
                int bound = boundedIntRange(descriptor.size());
                for (int i = 0; i < values.length; i++) {
                    values[i] = rng.nextInt(bound);
                }
            }
            default -> throw unsupported("integer", descriptor.distribution());
        }
        return values;
    }

    static double[] doubles(PbbsDataset.Descriptor descriptor) {
        Random rng = rng(descriptor, "doubles");
        double[] values = new double[descriptor.size()];
        switch (distribution(descriptor)) {
            case "random" -> {
                for (int i = 0; i < values.length; i++) {
                    values[i] = rng.nextDouble();
                }
            }
            case "almost_sorted" -> {
                for (int i = 0; i < values.length; i++) {
                    values[i] = i + rng.nextDouble() * 0.001;
                }
                int swaps = Math.max(1, values.length / 100);
                for (int i = 0; i < swaps && values.length > 1; i++) {
                    int a = rng.nextInt(values.length);
                    int b = rng.nextInt(values.length);
                    double tmp = values[a];
                    values[a] = values[b];
                    values[b] = tmp;
                }
            }
            default -> throw unsupported("floating", descriptor.distribution());
        }
        return values;
    }

    static PbbsPoint[] points(PbbsDataset.Descriptor descriptor) {
        Random rng = rng(descriptor, "points");
        PbbsPoint[] points = new PbbsPoint[descriptor.size()];
        switch (distribution(descriptor)) {
            case "in_circle" -> {
                for (int i = 0; i < points.length; i++) {
                    points[i] = polar(Math.sqrt(rng.nextDouble()), rng.nextDouble() * TWO_PI);
                }
            }
            case "on_circle" -> {
                double phase = rng.nextDouble() * TWO_PI;
                for (int i = 0; i < points.length; i++) {
                    points[i] = polar(1.0, phase + TWO_PI * i / points.length);
                }
            }
            case "kuzmin", "kuzmin_like" -> {
                for (int i = 0; i < points.length; i++) {
                    double radius = Math.pow(rng.nextDouble(), 2.0);
                    points[i] = polar(radius, rng.nextDouble() * TWO_PI);
                }
            }
            default -> throw unsupported("point", descriptor.distribution());
        }
        return points;
    }

    static PbbsGraph graph(PbbsDataset.Descriptor descriptor) {
        return switch (distribution(descriptor)) {
            case "rmat", "rmat_like" -> rmatGraph(descriptor);
            case "grid" -> gridGraph(descriptor.size());
            case "random_sparse" -> randomSparseGraph(descriptor);
            default -> throw unsupported("graph", descriptor.distribution());
        };
    }

    static int boundedIntRange(int size) {
        return Math.max(1, (int) Math.ceil(Math.sqrt(size)));
    }

    private static PbbsGraph randomSparseGraph(PbbsDataset.Descriptor descriptor) {
        Random rng = rng(descriptor, "graph-random-sparse");
        int vertexCount = descriptor.size();
        int[][] adjacency = new int[vertexCount][RANDOM_GRAPH_AVG_DEGREE];
        for (int from = 0; from < vertexCount; from++) {
            for (int edge = 0; edge < RANDOM_GRAPH_AVG_DEGREE; edge++) {
                adjacency[from][edge] = rng.nextInt(vertexCount);
            }
        }
        return new PbbsGraph(vertexCount, adjacency);
    }

    private static PbbsGraph gridGraph(int vertexCount) {
        int width = (int) Math.ceil(Math.sqrt(vertexCount));
        int[][] adjacency = new int[vertexCount][];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int row = vertex / width;
            int col = vertex % width;
            List<Integer> neighbors = new ArrayList<>(4);
            addGridNeighbor(neighbors, vertexCount, width, row - 1, col);
            addGridNeighbor(neighbors, vertexCount, width, row + 1, col);
            addGridNeighbor(neighbors, vertexCount, width, row, col - 1);
            addGridNeighbor(neighbors, vertexCount, width, row, col + 1);
            adjacency[vertex] = neighbors.stream().mapToInt(Integer::intValue).toArray();
        }
        return new PbbsGraph(vertexCount, adjacency);
    }

    private static PbbsGraph rmatGraph(PbbsDataset.Descriptor descriptor) {
        Random rng = rng(descriptor, "graph-rmat");
        int vertexCount = descriptor.size();
        int scale = ceilLog2(vertexCount);
        @SuppressWarnings("unchecked")
        List<Integer>[] adjacency = new List[vertexCount];
        for (int i = 0; i < adjacency.length; i++) {
            adjacency[i] = new ArrayList<>(RANDOM_GRAPH_AVG_DEGREE);
        }

        int edges = Math.max(1, vertexCount * RANDOM_GRAPH_AVG_DEGREE);
        int accepted = 0;
        while (accepted < edges) {
            int from = 0;
            int to = 0;
            for (int bit = 0; bit < scale; bit++) {
                int mask = 1 << bit;
                double quadrant = rng.nextDouble();
                if (quadrant >= 0.57 && quadrant < 0.76) {
                    to |= mask;
                } else if (quadrant >= 0.76 && quadrant < 0.95) {
                    from |= mask;
                } else if (quadrant >= 0.95) {
                    from |= mask;
                    to |= mask;
                }
            }
            if (from < vertexCount && to < vertexCount) {
                adjacency[from].add(to);
                accepted++;
            }
        }

        int[][] arrays = new int[vertexCount][];
        for (int i = 0; i < vertexCount; i++) {
            arrays[i] = adjacency[i].stream().mapToInt(Integer::intValue).toArray();
        }
        return new PbbsGraph(vertexCount, arrays);
    }

    private static void addGridNeighbor(List<Integer> neighbors, int vertexCount,
                                        int width, int row, int col) {
        if (row < 0 || col < 0 || col >= width) {
            return;
        }
        int neighbor = row * width + col;
        if (neighbor >= 0 && neighbor < vertexCount) {
            neighbors.add(neighbor);
        }
    }

    private static PbbsPoint polar(double radius, double angle) {
        return new PbbsPoint(radius * Math.cos(angle), radius * Math.sin(angle));
    }

    private static Random rng(PbbsDataset.Descriptor descriptor, String family) {
        long seed = descriptor.seed()
                ^ ((long) family.hashCode() << 32)
                ^ descriptor.distribution().hashCode()
                ^ descriptor.size();
        return new Random(seed);
    }

    private static String distribution(PbbsDataset.Descriptor descriptor) {
        return descriptor.distribution()
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
    }

    private static int ceilLog2(int value) {
        int log = 0;
        int power = 1;
        while (power < value) {
            power <<= 1;
            log++;
        }
        return log;
    }

    private static IllegalArgumentException unsupported(String family, String distribution) {
        return new IllegalArgumentException("unsupported " + family + " distribution: " + distribution);
    }
}
