package org.heartbeat.scheduler.integration;

import org.heartbeat.scheduler.core.HeartbeatConfig;
import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.task.HeartbeatTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for fork-join execution via VirtualThreadExecutor.
 */
class ForkJoinIntegrationTest {

    private VirtualThreadExecutor executor;

    @BeforeEach
    void setUp() {
        HeartbeatConfig config = HeartbeatConfig.newBuilder()
                .heartbeatPeriodNanos(5_000)   // 5μs - short period for testing
                .promotionCostNanos(500)
                .enableStatistics(true)
                .build();
        executor = new VirtualThreadExecutor(config);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    // --- Helper tasks ---

    static class AddTask extends HeartbeatTask<Integer> {
        private final int a, b;
        AddTask(int a, int b) { this.a = a; this.b = b; }
        @Override protected Integer compute() { return a + b; }
    }

    static class FibTask extends HeartbeatTask<Long> {
        private final int n;
        FibTask(int n) { this.n = n; }
        @Override
        protected Long compute() {
            if (n <= 1) return (long) n;
            FibTask f1 = new FibTask(n - 1);
            FibTask f2 = new FibTask(n - 2);
            fork(f1);
            fork(f2);
            return join(f1) + join(f2);
        }
    }

    static class SumTask extends HeartbeatTask<Long> {
        private final int[] array;
        private final int start, end, threshold;
        SumTask(int[] array, int start, int end, int threshold) {
            this.array = array; this.start = start; this.end = end; this.threshold = threshold;
        }
        @Override
        protected Long compute() {
            if (end - start <= threshold) {
                long sum = 0;
                for (int i = start; i < end; i++) sum += array[i];
                return sum;
            }
            int mid = (start + end) / 2;
            SumTask left = new SumTask(array, start, mid, threshold);
            SumTask right = new SumTask(array, mid, end, threshold);
            fork(left);
            fork(right);
            return join(left) + join(right);
        }
    }

    // --- Tests ---

    @Test
    void testStatisticsAfterForkJoin() throws ExecutionException {
        Long result = executor.submit(new FibTask(10));
        assertThat(result).isEqualTo(55L);

        VirtualThreadExecutor.ExecutorStatistics stats = executor.getStatistics();
        assertThat(stats.totalTasksExecuted).isGreaterThanOrEqualTo(1);
    }

    @Test
    void testSimpleTask() throws ExecutionException {
        Integer result = executor.submit(new AddTask(3, 4));
        assertThat(result).isEqualTo(7);
    }

    @Test
    void testSimpleForkJoin() throws ExecutionException {
        HeartbeatTask<Integer> task = new HeartbeatTask<>() {
            @Override
            protected Integer compute() {
                AddTask t1 = new AddTask(1, 2);
                AddTask t2 = new AddTask(3, 4);
                fork(t1);
                fork(t2);
                return join(t1) + join(t2);
            }
        };

        Integer result = executor.submit(task);
        assertThat(result).isEqualTo(10);
    }

    @Test
    void testFibonacciSmall() throws ExecutionException {
        assertThat(executor.submit(new FibTask(0))).isEqualTo(0L);
        assertThat(executor.submit(new FibTask(1))).isEqualTo(1L);
        assertThat(executor.submit(new FibTask(2))).isEqualTo(1L);
        assertThat(executor.submit(new FibTask(5))).isEqualTo(5L);
    }

    @Test
    void testFibonacciMedium() throws ExecutionException {
        assertThat(executor.submit(new FibTask(10))).isEqualTo(55L);
        assertThat(executor.submit(new FibTask(15))).isEqualTo(610L);
    }

    @Test
    void testFibonacciLarger() throws ExecutionException {
        assertThat(executor.submit(new FibTask(20))).isEqualTo(6765L);
    }

    @Test
    void testParallelSum() throws ExecutionException {
        int[] array = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        Long result = executor.submit(new SumTask(array, 0, 10, 2));
        assertThat(result).isEqualTo(55L);
    }

    @Test
    void testParallelSumLarger() throws ExecutionException {
        int size = 1000;
        int[] array = new int[size];
        long expected = 0;
        for (int i = 0; i < size; i++) {
            array[i] = i + 1;
            expected += (i + 1);
        }
        Long result = executor.submit(new SumTask(array, 0, size, 50));
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void testSequentialSubmissions() throws ExecutionException {
        for (int i = 0; i < 5; i++) {
            Long result = executor.submit(new FibTask(10));
            assertThat(result).isEqualTo(55L);
        }
    }

    @Test
    void testInvoke() throws ExecutionException {
        HeartbeatTask<Long> task = new HeartbeatTask<>() {
            @Override
            protected Long compute() {
                return invoke(new FibTask(10));
            }
        };
        assertThat(executor.submit(task)).isEqualTo(55L);
    }

    @Test
    void testExceptionPropagation() {
        HeartbeatTask<Integer> failTask = new HeartbeatTask<>() {
            @Override
            protected Integer compute() {
                throw new RuntimeException("Intentional failure");
            }
        };

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.submit(failTask))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .hasMessageContaining("Intentional failure");
    }

    @Test
    void testAsyncSubmission() throws Exception {
        var future = executor.submitAsync(new FibTask(10));
        assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(55L);
    }
}
