package org.heartbeat.scheduler.bench.pbbs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PbbsInputGeneratorsTest {

    @Test
    void integerGeneratorsAreDeterministicAndRespectDistributionInvariants() {
        PbbsDataset.Descriptor random = descriptor("ints", "random", 64, 7);
        assertThat(PbbsInputs.integers(random))
                .containsExactly(PbbsInputs.integers(random));

        PbbsDataset.Descriptor bounded = descriptor("ints", "bounded random", 64, 7);
        int[] boundedValues = PbbsInputs.integers(bounded);
        for (int value : boundedValues) {
            assertThat(value).isBetween(0, PbbsInputs.boundedIntRange(bounded.size()) - 1);
        }

        PbbsDataset.Descriptor exponential = descriptor("ints", "exponential-like", 64, 7);
        for (int value : PbbsInputs.integers(exponential)) {
            assertThat(value).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void floatingGeneratorsAreDeterministicAndFinite() {
        PbbsDataset.Descriptor random = descriptor("doubles", "random", 64, 11);
        assertThat(PbbsInputs.doubles(random))
                .containsExactly(PbbsInputs.doubles(random));

        PbbsDataset.Descriptor almostSorted = descriptor("doubles", "almost-sorted", 128, 11);
        double[] values = PbbsInputs.doubles(almostSorted);

        assertThat(values).hasSize(128);
        for (double value : values) {
            assertThat(Double.isFinite(value)).isTrue();
        }
        assertThat(values).containsExactly(PbbsInputs.doubles(almostSorted));
    }

    @Test
    void pointGeneratorsAreDeterministicAndStayWithinExpectedGeometry() {
        PbbsDataset.Descriptor inCircle = descriptor("points", "in-circle", 64, 13);
        assertThat(PbbsInputs.points(inCircle))
                .containsExactly(PbbsInputs.points(inCircle));
        assertThat(PbbsInputs.points(inCircle))
                .allSatisfy(point -> assertThat(radius(point)).isLessThanOrEqualTo(1.0 + 1e-12));

        PbbsDataset.Descriptor onCircle = descriptor("points", "on-circle", 64, 13);
        assertThat(PbbsInputs.points(onCircle))
                .allSatisfy(point -> assertThat(radius(point)).isCloseTo(1.0, within(1e-12)));

        PbbsDataset.Descriptor kuzmin = descriptor("points", "kuzmin-like", 64, 13);
        assertThat(PbbsInputs.points(kuzmin))
                .allSatisfy(point -> assertThat(radius(point)).isLessThanOrEqualTo(1.0 + 1e-12));
    }

    @Test
    void graphGeneratorsAreDeterministicAndHaveValidEndpoints() {
        assertGraphDeterministicAndValid(descriptor("graph", "random-sparse", 32, 17));
        assertGraphDeterministicAndValid(descriptor("graph", "rMat-like", 32, 17));
        assertGraphDeterministicAndValid(descriptor("graph", "grid", 32, 17));
    }

    @Test
    void gridGraphUsesExpectedFourNeighborTopology() {
        PbbsGraph graph = PbbsInputs.graph(descriptor("graph", "grid", 4, 19));

        assertThat(graph.adjacency()[0]).containsExactlyInAnyOrder(2, 1);
        assertThat(graph.adjacency()[1]).containsExactlyInAnyOrder(3, 0);
        assertThat(graph.adjacency()[2]).containsExactlyInAnyOrder(0, 3);
        assertThat(graph.adjacency()[3]).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void unsupportedDistributionFailsFast() {
        assertThatThrownBy(() -> PbbsInputs.integers(descriptor("ints", "zipf", 16, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported integer distribution");
    }

    private static void assertGraphDeterministicAndValid(PbbsDataset.Descriptor descriptor) {
        PbbsGraph first = PbbsInputs.graph(descriptor);
        PbbsGraph second = PbbsInputs.graph(descriptor);

        assertThat(first.vertexCount()).isEqualTo(descriptor.size());
        assertThat(first.adjacency()).isDeepEqualTo(second.adjacency());
        PbbsValidation.requireValidGraphEndpoints(first);
    }

    private static PbbsDataset.Descriptor descriptor(String name, String distribution, int size, long seed) {
        return new PbbsDataset.Descriptor(name, size, distribution, seed);
    }

    private static double radius(PbbsPoint point) {
        return Math.hypot(point.x(), point.y());
    }
}
