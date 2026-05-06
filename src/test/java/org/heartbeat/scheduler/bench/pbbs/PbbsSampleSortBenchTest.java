package org.heartbeat.scheduler.bench.pbbs;

import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.testutil.TestConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class PbbsSampleSortBenchTest {

    @Test
    void comparisonSortMatchesJdkAcrossDistributions() {
        assertSequentialMatchesJdk("random");
        assertSequentialMatchesJdk("almost_sorted");
    }

    @Test
    void forkJoinAndHeartbeatMatchSequentialWhenSplitRecursively() throws Exception {
        double[] input = PbbsInputs.doubles(new PbbsDataset.Descriptor(
                "sample-test", 257, "almost_sorted", 123L));
        double[] expected = PbbsCopies.copy(input);
        PbbsSampleSortBench.comparisonSort(expected);

        double[] forkJoin = PbbsCopies.copy(input);
        PbbsSampleSortBench.forkJoinSampleSort(forkJoin, 17);

        double[] heartbeat = PbbsCopies.copy(input);
        try (VirtualThreadExecutor executor =
                     new VirtualThreadExecutor(TestConfig.instantFireBuilder().build())) {
            PbbsSampleSortBench.heartbeatSampleSort(executor, heartbeat, 17);
        }

        assertThat(forkJoin).containsExactly(expected);
        assertThat(heartbeat).containsExactly(expected);
        PbbsValidation.requireSorted(forkJoin);
        PbbsValidation.requireSorted(heartbeat);
        PbbsValidation.requireSameDoubleSequence(expected, heartbeat);
    }

    @Test
    void defensiveCopyKeepsGeneratedInputReusable() {
        double[] input = PbbsInputs.doubles(new PbbsDataset.Descriptor(
                "sample-test", 64, "random", 321L));
        double[] original = PbbsCopies.copy(input);
        double[] working = PbbsCopies.copy(input);

        PbbsSampleSortBench.comparisonSort(working);

        assertThat(input).containsExactly(original);
        assertThat(working).isSorted();
    }

    private static void assertSequentialMatchesJdk(String distribution) {
        double[] values = PbbsInputs.doubles(new PbbsDataset.Descriptor(
                "sample-test", 512, distribution, 99L));
        double[] expected = PbbsCopies.copy(values);
        Arrays.sort(expected);

        PbbsSampleSortBench.comparisonSort(values);

        assertThat(values).containsExactly(expected);
    }
}
