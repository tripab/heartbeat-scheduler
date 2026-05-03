package org.heartbeat.scheduler.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PollingStrategyTest {

    @Nested
    class CountBasedPollingTest {

        @Test
        void testPollsAfterExactInterval() {
            CountBasedPolling polling = CountBasedPolling.every(3);
            assertThat(polling.shouldPoll()).isFalse(); // 1
            assertThat(polling.shouldPoll()).isFalse(); // 2
            assertThat(polling.shouldPoll()).isTrue();  // 3
        }

        @Test
        void testRecordPollResetsCounter() {
            CountBasedPolling polling = CountBasedPolling.every(2);
            polling.shouldPoll(); // 1
            polling.shouldPoll(); // 2 — triggers
            polling.recordPoll();

            assertThat(polling.getOperationsSincePoll()).isEqualTo(0);
            assertThat(polling.shouldPoll()).isFalse(); // 1 again
            assertThat(polling.shouldPoll()).isTrue();  // 2 again
        }

        @Test
        void testReset() {
            CountBasedPolling polling = CountBasedPolling.every(3);
            polling.shouldPoll();
            polling.shouldPoll();
            polling.reset();

            assertThat(polling.getOperationsSincePoll()).isEqualTo(0);
            assertThat(polling.shouldPoll()).isFalse();
        }

        @Test
        void testEveryOne() {
            CountBasedPolling polling = CountBasedPolling.every(1);
            assertThat(polling.shouldPoll()).isTrue();
            polling.recordPoll();
            assertThat(polling.shouldPoll()).isTrue();
        }

        @Test
        void testGetPollInterval() {
            assertThat(CountBasedPolling.every(42).getPollInterval()).isEqualTo(42);
        }

        @Test
        void testZeroIntervalThrows() {
            assertThatThrownBy(() -> CountBasedPolling.every(0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void testNegativeIntervalThrows() {
            assertThatThrownBy(() -> CountBasedPolling.every(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void testContinuesPastInterval() {
            // shouldPoll stays true once count >= interval until recordPoll
            CountBasedPolling polling = CountBasedPolling.every(2);
            polling.shouldPoll(); // 1
            polling.shouldPoll(); // 2
            assertThat(polling.shouldPoll()).isTrue(); // 3 — still >= 2
        }
    }

    @Nested
    class TimeBasedPollingTest {

        @Test
        void testDoesNotPollImmediately() {
            TimeBasedPolling polling = TimeBasedPolling.everyMicros(1_000); // 1ms
            assertThat(polling.shouldPoll()).isFalse();
        }

        @Test
        void testPollsAfterInterval() throws InterruptedException {
            TimeBasedPolling polling = TimeBasedPolling.everyNanos(1_000_000); // 1ms
            Thread.sleep(5);
            assertThat(polling.shouldPoll()).isTrue();
        }

        @Test
        void testRecordPollResetsTimer() throws InterruptedException {
            TimeBasedPolling polling = TimeBasedPolling.everyNanos(1_000_000); // 1ms
            Thread.sleep(5);
            assertThat(polling.shouldPoll()).isTrue();

            polling.recordPoll();
            assertThat(polling.shouldPoll()).isFalse();
        }

        @Test
        void testReset() throws InterruptedException {
            TimeBasedPolling polling = TimeBasedPolling.everyNanos(1_000_000); // 1ms
            Thread.sleep(5);
            polling.reset();
            assertThat(polling.shouldPoll()).isFalse();
        }

        @Test
        void testGetPollIntervalNanos() {
            TimeBasedPolling polling = TimeBasedPolling.everyNanos(5_000);
            assertThat(polling.getPollIntervalNanos()).isEqualTo(5_000);
        }

        @Test
        void testGetPollIntervalMicros() {
            TimeBasedPolling polling = TimeBasedPolling.everyMicros(10);
            assertThat(polling.getPollIntervalMicros()).isEqualTo(10);
        }

        @Test
        void testForHeartbeatPeriod() {
            // 100μs heartbeat → poll at 10μs
            TimeBasedPolling polling = TimeBasedPolling.forHeartbeatPeriod(100_000);
            assertThat(polling.getPollIntervalNanos()).isEqualTo(10_000);
        }

        @Test
        void testForHeartbeatPeriodMinimum() {
            // Very small period → clamped to 1μs minimum
            TimeBasedPolling polling = TimeBasedPolling.forHeartbeatPeriod(100);
            assertThat(polling.getPollIntervalNanos()).isEqualTo(1_000);
        }

        @Test
        void testZeroIntervalThrows() {
            assertThatThrownBy(() -> TimeBasedPolling.everyNanos(0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void testNegativeIntervalThrows() {
            assertThatThrownBy(() -> TimeBasedPolling.everyNanos(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

    }
}
