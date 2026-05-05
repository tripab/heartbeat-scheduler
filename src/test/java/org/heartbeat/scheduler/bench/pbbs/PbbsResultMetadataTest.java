package org.heartbeat.scheduler.bench.pbbs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PbbsResultMetadataTest {

    @Test
    void metadataCapturesBenchmarkParametersAndMode() {
        PbbsDataset.Descriptor descriptor =
                new PbbsDataset.Descriptor("radix-small", 1_024, "bounded random", 42);

        PbbsResultMetadata metadata = PbbsResultMetadata.of(
                PbbsResultMetadata.Family.RADIX_SORT,
                descriptor,
                128,
                PbbsResultMetadata.Mode.HEARTBEAT);

        assertThat(metadata.benchmarkClassName()).isEqualTo("PbbsRadixSortBench");
        assertThat(metadata.distribution()).isEqualTo("bounded_random");
        assertThat(metadata.size()).isEqualTo(1_024);
        assertThat(metadata.threshold()).isEqualTo(128);
        assertThat(metadata.jmhMethodName()).isEqualTo("heartbeat");
        assertThat(metadata.resultKey())
                .isEqualTo("radix-sort/bounded_random/n=1024/t=128/heartbeat");
    }

    @Test
    void tagsIncludePaperContextAsQualitativeOnly() {
        PbbsResultMetadata metadata = new PbbsResultMetadata(
                PbbsResultMetadata.Family.CONVEX_HULL,
                "on-circle",
                256,
                32,
                PbbsResultMetadata.Mode.FORK_JOIN_POOL);

        assertThat(metadata.tags())
                .containsEntry("family", "convex-hull")
                .containsEntry("distribution", "on_circle")
                .containsEntry("size", "256")
                .containsEntry("threshold", "32")
                .containsEntry("mode", "fork_join_pool")
                .containsEntry("paperContext", "convexhull")
                .containsEntry("comparisonScope", "paper-context-only");
        assertThat(metadata.paperContext().comparisonScope()).isEqualTo("paper-context-only");
    }

    @Test
    void modesExposeExpectedJmhMethodNames() {
        assertThat(PbbsResultMetadata.Mode.SEQUENTIAL.jmhMethodName()).isEqualTo("sequential");
        assertThat(PbbsResultMetadata.Mode.FORK_JOIN_POOL.jmhMethodName()).isEqualTo("forkJoinPool");
        assertThat(PbbsResultMetadata.Mode.HEARTBEAT.jmhMethodName()).isEqualTo("heartbeat");
    }

    @Test
    void validationRejectsIncompleteMetadata() {
        assertThatThrownBy(() -> new PbbsResultMetadata(null, "random", 1, 1,
                PbbsResultMetadata.Mode.SEQUENTIAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("family");
        assertThatThrownBy(() -> new PbbsResultMetadata(PbbsResultMetadata.Family.SAMPLE_SORT,
                " ", 1, 1, PbbsResultMetadata.Mode.SEQUENTIAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distribution");
        assertThatThrownBy(() -> new PbbsResultMetadata(PbbsResultMetadata.Family.SAMPLE_SORT,
                "random", 0, 1, PbbsResultMetadata.Mode.SEQUENTIAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size");
        assertThatThrownBy(() -> new PbbsResultMetadata(PbbsResultMetadata.Family.SAMPLE_SORT,
                "random", 1, 0, PbbsResultMetadata.Mode.SEQUENTIAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold");
        assertThatThrownBy(() -> new PbbsResultMetadata(PbbsResultMetadata.Family.SAMPLE_SORT,
                "random", 1, 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode");
    }
}
