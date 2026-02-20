package org.heartbeat.scheduler.executor;

import org.heartbeat.scheduler.core.HeartbeatConfig;
import org.heartbeat.scheduler.task.HeartbeatTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VirtualThreadExecutorTest {

    private VirtualThreadExecutor executor;

    @BeforeEach
    void setUp() {
        HeartbeatConfig config = HeartbeatConfig.newBuilder()
                .heartbeatPeriodNanos(30_000)
                .promotionCostNanos(1_500)
                .enableStatistics(true)
                .build();
        executor = new VirtualThreadExecutor(config);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void testSimpleSubmit() throws ExecutionException {
        HeartbeatTask<Integer> task = new HeartbeatTask<>() {
            @Override protected Integer compute() { return 42; }
        };
        assertThat(executor.submit(task)).isEqualTo(42);
    }

    @Test
    void testMultipleSubmissions() throws ExecutionException {
        for (int i = 0; i < 10; i++) {
            final int val = i;
            HeartbeatTask<Integer> task = new HeartbeatTask<>() {
                @Override protected Integer compute() { return val * 2; }
            };
            assertThat(executor.submit(task)).isEqualTo(i * 2);
        }
    }

    @Test
    void testExceptionHandling() {
        HeartbeatTask<Integer> task = new HeartbeatTask<>() {
            @Override protected Integer compute() {
                throw new RuntimeException("test error");
            }
        };
        assertThatThrownBy(() -> executor.submit(task))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void testAsyncSubmit() throws Exception {
        HeartbeatTask<String> task = new HeartbeatTask<>() {
            @Override protected String compute() { return "hello"; }
        };
        var future = executor.submitAsync(task);
        assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("hello");
    }

    @Test
    void testStatistics() throws ExecutionException {
        executor.submit(new HeartbeatTask<>() {
            @Override protected Integer compute() { return 1; }
        });
        executor.submit(new HeartbeatTask<>() {
            @Override protected Integer compute() { return 2; }
        });

        VirtualThreadExecutor.ExecutorStatistics stats = executor.getStatistics();
        assertThat(stats.totalTasksExecuted).isEqualTo(2);
    }

    @Test
    void testShutdownRejectsNewTasks() {
        executor.shutdown();
        assertThat(executor.isShutdown()).isTrue();
        assertThatThrownBy(() -> executor.submit(new HeartbeatTask<>() {
            @Override protected Integer compute() { return 1; }
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testConfig() {
        assertThat(executor.getConfig()).isNotNull();
        assertThat(executor.getConfig().getHeartbeatPeriodNanos()).isEqualTo(30_000);
    }

    @Test
    void testNullConfigThrows() {
        assertThatThrownBy(() -> new VirtualThreadExecutor(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
