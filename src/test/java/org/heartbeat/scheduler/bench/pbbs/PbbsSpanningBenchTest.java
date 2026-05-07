package org.heartbeat.scheduler.bench.pbbs;

import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.testutil.TestConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ForkJoinPool;

import static org.assertj.core.api.Assertions.*;

class PbbsSpanningBenchTest {

    @Test
    void sequentialSpanningForestCoversEveryGeneratedGraphDistribution() {
        assertValidSequentialForest("grid");
        assertValidSequentialForest("random_sparse");
        assertValidSequentialForest("rmat_like");
    }

    @Test
    void forkJoinAndHeartbeatProduceValidForestsWhenSplitRecursively() throws Exception {
        PbbsGraph graph = PbbsInputs.graph(new PbbsDataset.Descriptor(
                "spanning-test", 257, "random_sparse", 123L));

        int[] forkJoin = PbbsSpanningBench.forkJoinSpanningForest(
                graph, 7, ForkJoinPool.commonPool());

        int[] heartbeat;
        try (VirtualThreadExecutor executor =
                     new VirtualThreadExecutor(TestConfig.instantFireBuilder().build())) {
            heartbeat = PbbsSpanningBench.heartbeatSpanningForest(graph, 7, executor);
        }

        PbbsValidation.requireValidSpanningForest(graph, forkJoin);
        PbbsValidation.requireValidSpanningForest(graph, heartbeat);
        assertThat(forkJoin).hasSize(graph.vertexCount());
        assertThat(heartbeat).hasSize(graph.vertexCount());
    }

    @Test
    void gridGraphFromCornerFormsSingleSequentialTree() {
        PbbsGraph graph = PbbsInputs.graph(new PbbsDataset.Descriptor(
                "spanning-test", 16, "grid", 321L));

        int[] parent = PbbsSpanningBench.sequentialSpanningForest(graph);

        PbbsValidation.requireValidSpanningForest(graph, parent);
        assertThat(PbbsSpanningBench.rootCount(parent)).isEqualTo(1);
    }

    @Test
    void validationRejectsInvalidParentEdgesAndCycles() {
        PbbsGraph graph = new PbbsGraph(3, new int[][]{{1}, {2}, {}});

        assertThatThrownBy(() -> PbbsValidation.requireValidSpanningForest(
                graph, new int[]{0, 2, 2}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("parent edge");

        PbbsGraph cyclicGraph = new PbbsGraph(3, new int[][]{{1}, {0}, {}});
        assertThatThrownBy(() -> PbbsValidation.requireValidSpanningForest(
                cyclicGraph, new int[]{1, 0, 2}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    private static void assertValidSequentialForest(String distribution) {
        PbbsGraph graph = PbbsInputs.graph(new PbbsDataset.Descriptor(
                "spanning-test", 128, distribution, 99L));
        int[] parent = PbbsSpanningBench.sequentialSpanningForest(graph);

        PbbsValidation.requireValidSpanningForest(graph, parent);
        assertThat(parent).hasSize(graph.vertexCount());
    }
}
