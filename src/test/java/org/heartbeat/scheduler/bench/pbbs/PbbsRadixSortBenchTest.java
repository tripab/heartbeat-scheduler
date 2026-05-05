package org.heartbeat.scheduler.bench.pbbs;

import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.testutil.TestConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class PbbsRadixSortBenchTest {

    @Test
    void radixSortMatchesJdkSortAcrossDistributions() {
        assertSequentialMatchesJdk("random");
        assertSequentialMatchesJdk("exponential_like");
        assertSequentialMatchesJdk("bounded_random");
    }

    @Test
    void forkJoinAndHeartbeatMatchSequentialWhenSplitRecursively() throws Exception {
        int[] input = PbbsInputs.integers(new PbbsDataset.Descriptor(
                "radix-test", 257, "random", 123L));
        int[] expected = PbbsCopies.copy(input);
        PbbsRadixSortBench.radixSort(expected);

        int[] forkJoin = PbbsCopies.copy(input);
        PbbsRadixSortBench.parallelRadixSort(forkJoin, 17);

        int[] heartbeat = PbbsCopies.copy(input);
        try (VirtualThreadExecutor executor =
                     new VirtualThreadExecutor(TestConfig.instantFireBuilder().build())) {
            PbbsRadixSortBench.heartbeatRadixSort(executor, heartbeat, 17);
        }

        assertThat(forkJoin).containsExactly(expected);
        assertThat(heartbeat).containsExactly(expected);
        PbbsValidation.requireSorted(forkJoin);
        PbbsValidation.requireSorted(heartbeat);
        PbbsValidation.requireSameIntMultiset(input, heartbeat);
    }

    @Test
    void signedIntegersSortInNaturalOrder() {
        int[] values = {
                Integer.MAX_VALUE, -1, 0, Integer.MIN_VALUE, 42, -42
        };

        PbbsRadixSortBench.radixSort(values);

        assertThat(values).containsExactly(
                Integer.MIN_VALUE, -42, -1, 0, 42, Integer.MAX_VALUE);
    }

    private static void assertSequentialMatchesJdk(String distribution) {
        int[] values = PbbsInputs.integers(new PbbsDataset.Descriptor(
                "radix-test", 512, distribution, 99L));
        int[] expected = PbbsCopies.copy(values);
        Arrays.sort(expected);

        PbbsRadixSortBench.radixSort(values);

        assertThat(values).containsExactly(expected);
    }
}
