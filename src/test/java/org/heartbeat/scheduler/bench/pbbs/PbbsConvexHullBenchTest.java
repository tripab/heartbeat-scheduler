package org.heartbeat.scheduler.bench.pbbs;

import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.testutil.TestConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PbbsConvexHullBenchTest {

    @Test
    void convexHullDropsInteriorAndCollinearInteriorPoints() {
        PbbsPoint[] points = {
                new PbbsPoint(0, 0),
                new PbbsPoint(1, 0),
                new PbbsPoint(1, 1),
                new PbbsPoint(0, 1),
                new PbbsPoint(0.5, 0.5),
                new PbbsPoint(0.5, 0)
        };

        PbbsPoint[] hull = PbbsConvexHullBench.convexHull(points);

        assertThat(hull).containsExactly(
                new PbbsPoint(0, 0),
                new PbbsPoint(1, 0),
                new PbbsPoint(1, 1),
                new PbbsPoint(0, 1));
    }

    @Test
    void forkJoinAndHeartbeatMatchSequentialWhenSplitRecursively() throws Exception {
        PbbsPoint[] input = PbbsInputs.points(new PbbsDataset.Descriptor(
                "hull-test", 257, "in_circle", 123L));
        PbbsPoint[] expected = PbbsConvexHullBench.convexHull(input);

        PbbsPoint[] forkJoin = PbbsConvexHullBench.forkJoinHull(PbbsCopies.copy(input), 17);

        PbbsPoint[] heartbeat;
        try (VirtualThreadExecutor executor =
                     new VirtualThreadExecutor(TestConfig.instantFireBuilder().build())) {
            heartbeat = PbbsConvexHullBench.heartbeatHull(executor, PbbsCopies.copy(input), 17);
        }

        PbbsValidation.requireSamePointSet(expected, forkJoin);
        PbbsValidation.requireSamePointSet(expected, heartbeat);
        assertThat(forkJoin).containsExactly(expected);
        assertThat(heartbeat).containsExactly(expected);
    }

    @Test
    void allOnCirclePointsAreHullVertices() {
        PbbsPoint[] points = PbbsInputs.points(new PbbsDataset.Descriptor(
                "hull-test", 64, "on_circle", 321L));

        PbbsPoint[] hull = PbbsConvexHullBench.convexHull(points);

        assertThat(hull).hasSize(points.length);
        PbbsValidation.requireSamePointSet(points, hull);
    }

    @Test
    void generatedDistributionsProduceDeterministicHulls() {
        assertDeterministicHull("in_circle");
        assertDeterministicHull("on_circle");
        assertDeterministicHull("kuzmin_like");
    }

    private static void assertDeterministicHull(String distribution) {
        PbbsDataset.Descriptor descriptor = new PbbsDataset.Descriptor(
                "hull-test", 128, distribution, 99L);

        assertThat(PbbsConvexHullBench.convexHull(PbbsInputs.points(descriptor)))
                .containsExactly(PbbsConvexHullBench.convexHull(PbbsInputs.points(descriptor)));
    }
}
