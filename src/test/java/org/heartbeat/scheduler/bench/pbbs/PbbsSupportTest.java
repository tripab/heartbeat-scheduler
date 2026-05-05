package org.heartbeat.scheduler.bench.pbbs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PbbsSupportTest {

    @Test
    void descriptorDerivesNamedScaledDataset() {
        PbbsDataset.Descriptor descriptor = PbbsDataset.Descriptor.of(
                "radix", PbbsDataset.Scale.SMALL, "random", 42L);

        assertThat(descriptor.name()).isEqualTo("radix-small");
        assertThat(descriptor.size()).isEqualTo(1_024);
        assertThat(descriptor.distribution()).isEqualTo("random");
        assertThat(descriptor.seed()).isEqualTo(42L);
        assertThat(descriptor.withSize(16).size()).isEqualTo(16);
    }

    @Test
    void descriptorRejectsInvalidMetadata() {
        assertThatThrownBy(() -> new PbbsDataset.Descriptor("", 10, "random", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> new PbbsDataset.Descriptor("radix", 0, "random", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size");
        assertThatThrownBy(() -> new PbbsDataset.Descriptor("radix", 10, " ", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distribution");
    }

    @Test
    void copyHelpersDefensivelyCopyMutableInputs() {
        int[] ints = {3, 1, 2};
        double[] doubles = {3.0, 1.0};
        PbbsPoint[] points = {new PbbsPoint(1.0, 2.0)};
        int[][] adjacency = {{1, 2}, {0}, {}};

        int[] intsCopy = PbbsCopies.copy(ints);
        double[] doublesCopy = PbbsCopies.copy(doubles);
        PbbsPoint[] pointsCopy = PbbsCopies.copy(points);
        int[][] adjacencyCopy = PbbsCopies.copy(adjacency);

        intsCopy[0] = 99;
        doublesCopy[0] = 99.0;
        pointsCopy[0] = new PbbsPoint(9.0, 9.0);
        adjacencyCopy[0][0] = 99;

        assertThat(ints).containsExactly(3, 1, 2);
        assertThat(doubles).containsExactly(3.0, 1.0);
        assertThat(points).containsExactly(new PbbsPoint(1.0, 2.0));
        assertThat(adjacency[0]).containsExactly(1, 2);
    }

    @Test
    void graphCopiesAdjacencyAndValidatesEndpoints() {
        int[][] adjacency = {{1}, {0, 2}, {}};
        PbbsGraph graph = new PbbsGraph(3, adjacency);

        adjacency[0][0] = 2;
        int[][] graphAdjacency = graph.adjacency();
        graphAdjacency[0][0] = 2;

        assertThat(graph.adjacency()[0]).containsExactly(1);
        assertThatThrownBy(() -> new PbbsGraph(2, new int[][]{{1}, {2}}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("endpoint");
    }

    @Test
    void sortAndMultisetValidationAcceptsEquivalentSortedOutput() {
        int[] input = {5, 1, 5, 2};
        int[] sorted = {1, 2, 5, 5};

        PbbsValidation.requireSorted(sorted);
        PbbsValidation.requireSameIntMultiset(input, sorted);

        assertThatThrownBy(() -> PbbsValidation.requireSorted(new int[]{1, 3, 2}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sorted");
        assertThatThrownBy(() -> PbbsValidation.requireSameIntMultiset(input, new int[]{1, 2, 5}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multiset");
    }

    @Test
    void uniqueAndPointSetValidationUseCanonicalSets() {
        PbbsValidation.requireStrictlyUniqueSorted(new int[]{1, 3, 8});
        PbbsValidation.requireSameUniqueSet(new int[]{3, 1, 3, 2}, new int[]{1, 2, 3});
        PbbsValidation.requireSamePointSet(
                new PbbsPoint[]{new PbbsPoint(1, 1), new PbbsPoint(2, 2)},
                new PbbsPoint[]{new PbbsPoint(2, 2), new PbbsPoint(1, 1)});

        assertThat(PbbsValidation.sortedUnique(new int[]{4, 2, 4, 1}))
                .containsExactly(1, 2, 4);
        assertThatThrownBy(() -> PbbsValidation.requireStrictlyUniqueSorted(new int[]{1, 1, 2}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unique");
        assertThatThrownBy(() -> PbbsValidation.requireSamePointSet(
                new PbbsPoint[]{new PbbsPoint(1, 1)},
                new PbbsPoint[]{new PbbsPoint(2, 2)}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("point");
    }
}
