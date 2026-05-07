package org.heartbeat.scheduler.bench.pbbs;

import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.testutil.TestConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ForkJoinPool;

import static org.assertj.core.api.Assertions.assertThat;

class PbbsBenchmarkCorrectnessTest {

    private static final int SMALL_SIZE = 129;
    private static final int SPLITTING_THRESHOLD = 11;

    @Test
    void radixSortModesMatchSequentialAcrossDistributions() throws Exception {
        for (String distribution : new String[]{"random", "exponential_like", "bounded_random"}) {
            int[] input = PbbsInputs.integers(descriptor("radix-correctness", distribution));
            int[] expected = PbbsCopies.copy(input);
            PbbsRadixSortBench.radixSort(expected);

            int[] forkJoin = PbbsCopies.copy(input);
            PbbsRadixSortBench.parallelRadixSort(forkJoin, SPLITTING_THRESHOLD);

            int[] heartbeat = PbbsCopies.copy(input);
            try (VirtualThreadExecutor executor = instantExecutor()) {
                PbbsRadixSortBench.heartbeatRadixSort(executor, heartbeat, SPLITTING_THRESHOLD);
            }

            assertThat(forkJoin).containsExactly(expected);
            assertThat(heartbeat).containsExactly(expected);
            PbbsValidation.requireSameIntMultiset(input, heartbeat);
        }
    }

    @Test
    void sampleSortModesMatchSequentialAcrossDistributions() throws Exception {
        for (String distribution : new String[]{"random", "almost_sorted"}) {
            double[] input = PbbsInputs.doubles(descriptor("sample-correctness", distribution));
            double[] expected = PbbsCopies.copy(input);
            PbbsSampleSortBench.comparisonSort(expected);

            double[] forkJoin = PbbsCopies.copy(input);
            PbbsSampleSortBench.forkJoinSampleSort(forkJoin, SPLITTING_THRESHOLD);

            double[] heartbeat = PbbsCopies.copy(input);
            try (VirtualThreadExecutor executor = instantExecutor()) {
                PbbsSampleSortBench.heartbeatSampleSort(executor, heartbeat, SPLITTING_THRESHOLD);
            }

            assertThat(forkJoin).containsExactly(expected);
            assertThat(heartbeat).containsExactly(expected);
            PbbsValidation.requireSameDoubleSequence(expected, heartbeat);
        }
    }

    @Test
    void removeDuplicatesModesMatchSequentialAcrossDistributions() throws Exception {
        for (String distribution : new String[]{"random", "bounded_random"}) {
            int[] input = PbbsInputs.integers(descriptor("dedup-correctness", distribution));
            int[] expected = PbbsRemoveDuplicatesBench.uniqueSorted(input);

            int[] forkJoin = PbbsRemoveDuplicatesBench.forkJoinUniqueSorted(
                    PbbsCopies.copy(input), SPLITTING_THRESHOLD);

            int[] heartbeat;
            try (VirtualThreadExecutor executor = instantExecutor()) {
                heartbeat = PbbsRemoveDuplicatesBench.heartbeatUniqueSorted(
                        executor, PbbsCopies.copy(input), SPLITTING_THRESHOLD);
            }

            assertThat(forkJoin).containsExactly(expected);
            assertThat(heartbeat).containsExactly(expected);
            PbbsValidation.requireSameUniqueSet(input, heartbeat);
        }
    }

    @Test
    void convexHullModesMatchSequentialAcrossDistributions() throws Exception {
        for (String distribution : new String[]{"in_circle", "on_circle", "kuzmin_like"}) {
            PbbsPoint[] input = PbbsInputs.points(descriptor("hull-correctness", distribution));
            PbbsPoint[] expected = PbbsConvexHullBench.convexHull(input);

            PbbsPoint[] forkJoin = PbbsConvexHullBench.forkJoinHull(
                    PbbsCopies.copy(input), SPLITTING_THRESHOLD);

            PbbsPoint[] heartbeat;
            try (VirtualThreadExecutor executor = instantExecutor()) {
                heartbeat = PbbsConvexHullBench.heartbeatHull(
                        executor, PbbsCopies.copy(input), SPLITTING_THRESHOLD);
            }

            assertThat(forkJoin).containsExactly(expected);
            assertThat(heartbeat).containsExactly(expected);
            PbbsValidation.requireSamePointSet(expected, heartbeat);
        }
    }

    @Test
    void spanningModesProduceValidForestsAcrossDistributions() throws Exception {
        for (String distribution : new String[]{"rmat_like", "grid", "random_sparse"}) {
            PbbsGraph graph = PbbsInputs.graph(descriptor("spanning-correctness", distribution));
            int[] sequential = PbbsSpanningBench.sequentialSpanningForest(graph);

            int[] forkJoin = PbbsSpanningBench.forkJoinSpanningForest(
                    graph, SPLITTING_THRESHOLD, ForkJoinPool.commonPool());

            int[] heartbeat;
            try (VirtualThreadExecutor executor = instantExecutor()) {
                heartbeat = PbbsSpanningBench.heartbeatSpanningForest(
                        graph, SPLITTING_THRESHOLD, executor);
            }

            PbbsValidation.requireValidSpanningForest(graph, sequential);
            PbbsValidation.requireValidSpanningForest(graph, forkJoin);
            PbbsValidation.requireValidSpanningForest(graph, heartbeat);
            assertThat(PbbsSpanningBench.rootCount(forkJoin))
                    .isEqualTo(PbbsSpanningBench.rootCount(sequential));
            assertThat(PbbsSpanningBench.rootCount(heartbeat))
                    .isEqualTo(PbbsSpanningBench.rootCount(sequential));
        }
    }

    private static PbbsDataset.Descriptor descriptor(String name, String distribution) {
        return new PbbsDataset.Descriptor(name, SMALL_SIZE, distribution, 777L);
    }

    private static VirtualThreadExecutor instantExecutor() {
        return new VirtualThreadExecutor(TestConfig.instantFireBuilder().build());
    }
}
