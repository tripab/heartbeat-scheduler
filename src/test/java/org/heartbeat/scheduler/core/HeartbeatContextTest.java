package org.heartbeat.scheduler.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class HeartbeatContextTest {

    private HeartbeatConfig config;
    private PollingStrategy pollingStrategy;

    @BeforeEach
    void setUp() {
        config = HeartbeatConfig.newBuilder()
                .heartbeatPeriodMicros(30)
                .promotionCostNanos(1500)
                .build();
        pollingStrategy = CountBasedPolling.every(100);
    }

    @AfterEach
    void tearDown() {
        HeartbeatContext.clearCurrent();
    }

    @Test
    void testCreation() {
        HeartbeatContext context = new HeartbeatContext(config, pollingStrategy);

        assertThat(context.getConfig()).isSameAs(config);
        assertThat(context.getTimer()).isNotNull();
        assertThat(context.getPollingStrategy()).isSameAs(pollingStrategy);
        assertThat(context.getTotalOperations()).isEqualTo(0);
        assertThat(context.getTotalPolls()).isEqualTo(0);
        assertThat(context.getTotalPromotions()).isEqualTo(0);
    }

    @Test
    void testNullConfig() {
        assertThatThrownBy(() -> new HeartbeatContext(null, pollingStrategy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Config cannot be null");
    }

    @Test
    void testNullPollingStrategy() {
        assertThatThrownBy(() -> new HeartbeatContext(config, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Polling strategy cannot be null");
    }

    @Test
    void testThreadLocalIsolation() {
        HeartbeatContext context1 = new HeartbeatContext(config, pollingStrategy);
        HeartbeatContext.setCurrent(context1);

        assertThat(HeartbeatContext.current()).isSameAs(context1);

        // Different thread should have null context
        Thread thread = new Thread(() -> {
            assertThat(HeartbeatContext.current()).isNull();
        });
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Original thread still has context
        assertThat(HeartbeatContext.current()).isSameAs(context1);
    }

    @Test
    void testClearCurrent() {
        HeartbeatContext context = new HeartbeatContext(config, pollingStrategy);
        HeartbeatContext.setCurrent(context);

        assertThat(HeartbeatContext.current()).isSameAs(context);

        HeartbeatContext.clearCurrent();

        assertThat(HeartbeatContext.current()).isNull();
    }

    @Test
    void testCheckHeartbeat() {
        // Use count-based polling that polls every operation
        PollingStrategy alwaysPoll = CountBasedPolling.every(1);
        HeartbeatContext context = new HeartbeatContext(config, alwaysPoll);

        // First check - shouldn't promote (not enough time passed)
        boolean shouldPromote1 = context.checkHeartbeat();
        assertThat(shouldPromote1).isFalse();
        assertThat(context.getTotalOperations()).isEqualTo(1);
        assertThat(context.getTotalPolls()).isEqualTo(1);
        assertThat(context.getTotalPromotions()).isEqualTo(0);
    }

    @Test
    void testCheckHeartbeatWithTimeDelay() throws InterruptedException {
        // Use count-based polling that polls every operation
        PollingStrategy alwaysPoll = CountBasedPolling.every(1);
        HeartbeatContext context = new HeartbeatContext(config, alwaysPoll);

        // Wait for heartbeat period to pass
        Thread.sleep(1); // 1ms >> 30μs

        boolean shouldPromote = context.checkHeartbeat();
        assertThat(shouldPromote).isTrue();
        assertThat(context.getTotalOperations()).isEqualTo(1);
        assertThat(context.getTotalPolls()).isEqualTo(1);
    }

    @Test
    void testRecordPromotion() throws InterruptedException {
        PollingStrategy alwaysPoll = CountBasedPolling.every(1);
        HeartbeatContext context = new HeartbeatContext(config, alwaysPoll);

        Thread.sleep(1); // Wait for heartbeat

        boolean shouldPromote = context.checkHeartbeat();
        assertThat(shouldPromote).isTrue();

        context.recordPromotion();

        assertThat(context.getTotalPromotions()).isEqualTo(1);

        // After recording, timer should reset
        shouldPromote = context.checkHeartbeat();
        assertThat(shouldPromote).isFalse();
    }

    @Test
    void testAddCredits() {
        HeartbeatContext context = new HeartbeatContext(config, pollingStrategy);

        context.addCredits(100);
        context.addCredits(50);

        assertThat(context.getTimer().getCreditsSincePromotion()).isEqualTo(150);
    }

    @Test
    void testStatistics() {
        PollingStrategy alwaysPoll = CountBasedPolling.every(1);
        HeartbeatContext context = new HeartbeatContext(config, alwaysPoll);

        // Perform some operations
        for (int i = 0; i < 10; i++) {
            context.checkHeartbeat();
        }

        assertThat(context.getTotalOperations()).isEqualTo(10);
        assertThat(context.getTotalPolls()).isEqualTo(10);

        HeartbeatContext.ContextStatistics stats = context.getStatistics();

        assertThat(stats.totalOperations).isEqualTo(10);
        assertThat(stats.totalPolls).isEqualTo(10);
        assertThat(stats.totalPromotions).isEqualTo(0);
    }

    @Test
    void testPollingRate() {
        // Poll every 5 operations
        PollingStrategy sparsePoll = CountBasedPolling.every(5);
        HeartbeatContext context = new HeartbeatContext(config, sparsePoll);

        for (int i = 0; i < 50; i++) {
            context.checkHeartbeat();
        }

        assertThat(context.getTotalOperations()).isEqualTo(50);
        assertThat(context.getTotalPolls()).isEqualTo(10); // 50/5 = 10

        double pollingRate = context.getPollingRate();
        assertThat(pollingRate).isCloseTo(0.2, within(0.01)); // 10/50 = 0.2
    }

    @Test
    void testPromotionRate() throws InterruptedException {
        PollingStrategy alwaysPoll = CountBasedPolling.every(1);
        HeartbeatContext context = new HeartbeatContext(config, alwaysPoll);

        // Do some operations without promotion
        for (int i = 0; i < 5; i++) {
            context.checkHeartbeat();
        }

        assertThat(context.getPromotionRate()).isCloseTo(0.0, within(0.01));

        // Wait and cause a promotion
        Thread.sleep(1);
        context.checkHeartbeat();
        context.recordPromotion();

        // 1 promotion out of 6 polls
        assertThat(context.getPromotionRate()).isCloseTo(1.0 / 6.0, within(0.01));
    }

    @Test
    void testResetStatistics() {
        PollingStrategy alwaysPoll = CountBasedPolling.every(1);
        HeartbeatContext context = new HeartbeatContext(config, alwaysPoll);

        for (int i = 0; i < 10; i++) {
            context.checkHeartbeat();
        }

        assertThat(context.getTotalOperations()).isEqualTo(10);

        context.resetStatistics();

        assertThat(context.getTotalOperations()).isEqualTo(0);
        assertThat(context.getTotalPolls()).isEqualTo(0);
        assertThat(context.getTotalPromotions()).isEqualTo(0);
    }

    @Test
    void testReset() {
        PollingStrategy alwaysPoll = CountBasedPolling.every(1);
        HeartbeatContext context = new HeartbeatContext(config, alwaysPoll);

        for (int i = 0; i < 10; i++) {
            context.checkHeartbeat();
        }
        context.addCredits(100);

        context.reset();

        assertThat(context.getTotalOperations()).isEqualTo(0);
        assertThat(context.getTotalPolls()).isEqualTo(0);
        assertThat(context.getTotalPromotions()).isEqualTo(0);
        assertThat(context.getTimer().getCreditsSincePromotion()).isEqualTo(0);
    }

    @Test
    void testGetSnapshot() {
        PollingStrategy alwaysPoll = CountBasedPolling.every(1);
        HeartbeatContext context = new HeartbeatContext(config, alwaysPoll);

        for (int i = 0; i < 10; i++) {
            context.checkHeartbeat();
        }

        HeartbeatContext.ContextStatistics snapshot = context.getStatistics();

        assertThat(snapshot.totalOperations).isEqualTo(10);
        assertThat(snapshot.totalPolls).isEqualTo(10);
        assertThat(snapshot.totalPromotions).isEqualTo(0);
        assertThat(snapshot.creditsSincePromotion).isEqualTo(0);
    }

    @Test
    void testPollingIntegration() {
        // Test count-based polling
        PollingStrategy countPoll = CountBasedPolling.every(10);
        HeartbeatContext context = new HeartbeatContext(config, countPoll);

        // Should poll on 10th, 20th, 30th operation
        for (int i = 0; i < 30; i++) {
            context.checkHeartbeat();
        }

        assertThat(context.getTotalPolls()).isEqualTo(3);
    }

    @Test
    void testTimerIntegration() throws InterruptedException {
        PollingStrategy alwaysPoll = CountBasedPolling.every(1);
        HeartbeatContext context = new HeartbeatContext(config, alwaysPoll);

        HeartbeatTimer timer = context.getTimer();
        assertThat(timer).isNotNull();
        assertThat(timer.getHeartbeatPeriodNanos()).isEqualTo(config.getHeartbeatPeriodNanos());

        // Timer should not be ready initially
        assertThat(timer.shouldPromote()).isFalse();

        // After delay, should be ready
        Thread.sleep(1);
        assertThat(timer.shouldPromote()).isTrue();
    }

    @Test
    void testToString() {
        PollingStrategy alwaysPoll = CountBasedPolling.every(1);
        HeartbeatContext context = new HeartbeatContext(config, alwaysPoll);

        for (int i = 0; i < 10; i++) {
            context.checkHeartbeat();
        }

        String str = context.toString();

        assertThat(str).contains("ops=10");
        assertThat(str).contains("polls=10");
        assertThat(str).contains("promotions=0");
    }
}
