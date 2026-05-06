package org.heartbeat.scheduler.bench.pbbs;

import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.testutil.TestConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PbbsRemoveDuplicatesBenchTest {

    @Test
    void uniqueSortedCompactsDuplicatesDeterministically() {
        int[] values = {4, 1, 4, -2, 1, 7, -2};

        int[] unique = PbbsRemoveDuplicatesBench.uniqueSorted(values);

        assertThat(unique).containsExactly(-2, 1, 4, 7);
        assertThat(values).containsExactly(4, 1, 4, -2, 1, 7, -2);
    }

    @Test
    void forkJoinAndHeartbeatMatchSequentialWhenSplitRecursively() throws Exception {
        int[] input = PbbsInputs.integers(new PbbsDataset.Descriptor(
                "dedup-test", 257, "bounded_random", 123L));
        int[] expected = PbbsRemoveDuplicatesBench.uniqueSorted(input);

        int[] forkJoin = PbbsRemoveDuplicatesBench.forkJoinUniqueSorted(PbbsCopies.copy(input), 17);

        int[] heartbeat;
        try (VirtualThreadExecutor executor =
                     new VirtualThreadExecutor(TestConfig.instantFireBuilder().build())) {
            heartbeat = PbbsRemoveDuplicatesBench.heartbeatUniqueSorted(
                    executor, PbbsCopies.copy(input), 17);
        }

        assertThat(forkJoin).containsExactly(expected);
        assertThat(heartbeat).containsExactly(expected);
        PbbsValidation.requireStrictlyUniqueSorted(forkJoin);
        PbbsValidation.requireStrictlyUniqueSorted(heartbeat);
        PbbsValidation.requireSameUniqueSet(input, heartbeat);
    }

    @Test
    void supportsRandomDistributionWithMostlyUniqueKeys() throws Exception {
        int[] input = PbbsInputs.integers(new PbbsDataset.Descriptor(
                "dedup-test", 512, "random", 321L));
        int[] expected = PbbsRemoveDuplicatesBench.uniqueSorted(input);

        int[] forkJoin = PbbsRemoveDuplicatesBench.forkJoinUniqueSorted(PbbsCopies.copy(input), 64);

        assertThat(forkJoin).containsExactly(expected);
        PbbsValidation.requireSameUniqueSet(input, forkJoin);
    }
}
