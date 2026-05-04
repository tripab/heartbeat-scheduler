package org.heartbeat.scheduler.examples;

import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

class FibonacciExampleTest {

    @Test
    void readmeInputCompletesWithExpectedResult() throws ExecutionException {
        try (VirtualThreadExecutor executor =
                     new VirtualThreadExecutor(ExamplesSupport.defaultHeartbeatConfig())) {
            assertThat(executor.submit(new FibonacciExample.FibTask(35)))
                    .isEqualTo(9_227_465L);
        }
    }
}
