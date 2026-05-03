package org.heartbeat.scheduler.core;

import org.heartbeat.scheduler.testutil.TestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeartbeatTimerTest {

    private HeartbeatTimer timer;

    @BeforeEach
    void setUp() {
        timer = new HeartbeatTimer(TestConfig.normalPeriodNanos());
    }

    @Test
    void testInitialState() {
        assertThat(timer.getCreditsSincePromotion()).isEqualTo(0);
        assertThat(timer.getHeartbeatPeriodNanos()).isEqualTo(TestConfig.normalPeriodNanos());
        assertThat(timer.isCalibrated()).isFalse();
    }

    @Test
    void testInvalidPeriod() {
        assertThatThrownBy(() -> new HeartbeatTimer(0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new HeartbeatTimer(-1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testShouldPromoteAfterPeriod() throws InterruptedException {
        HeartbeatTimer freshTimer = new HeartbeatTimer(TestConfig.normalPeriodNanos());

        // Wait for period to definitely elapse
        Thread.sleep(TestConfig.sleepToExceed(TestConfig.normalPeriodNanos()));

        assertThat(freshTimer.shouldPromote()).isTrue();
    }

    @Test
    void testShouldPromoteWithLargePeriod() {
        HeartbeatTimer longTimer = new HeartbeatTimer(TestConfig.longPeriodNanos());

        assertThat(longTimer.shouldPromote()).isFalse();
    }

    @Test
    void testRecordPromotion() throws InterruptedException {
        Thread.sleep(TestConfig.sleepToExceed(TestConfig.normalPeriodNanos()));
        assertThat(timer.shouldPromote()).isTrue();

        timer.recordPromotion();

        // After recording, elapsed time is less than period
        assertThat(timer.getTimeSincePromotion()).isLessThan(TestConfig.normalPeriodNanos());
        assertThat(timer.getCreditsSincePromotion()).isEqualTo(0);
    }

    @Test
    void testAddCredits() {
        timer.addCredits(100);
        assertThat(timer.getCreditsSincePromotion()).isEqualTo(100);

        timer.addCredits(50);
        assertThat(timer.getCreditsSincePromotion()).isEqualTo(150);

        timer.recordPromotion();
        assertThat(timer.getCreditsSincePromotion()).isEqualTo(0);
    }

    @Test
    void testTimeSincePromotion() throws InterruptedException {
        long initialTime = timer.getTimeSincePromotion();
        assertThat(initialTime).isGreaterThanOrEqualTo(0);

        Thread.sleep(1);

        long laterTime = timer.getTimeSincePromotion();
        assertThat(laterTime).isGreaterThan(initialTime);
    }

    @Test
    void testCalibration() {
        assertThat(timer.isCalibrated()).isFalse();

        timer.calibrate();

        assertThat(timer.isCalibrated()).isTrue();
        assertThat(timer.getSystemNanoTimeOverhead()).isGreaterThan(0);
    }

    @Test
    void testAutoCalibration() {
        assertThat(timer.isCalibrated()).isFalse();

        long overhead = timer.getSystemNanoTimeOverhead();

        assertThat(timer.isCalibrated()).isTrue();
        assertThat(overhead).isGreaterThan(0);
    }

    @Test
    void testCalibrationIdempotent() {
        timer.calibrate();
        long firstOverhead = timer.getSystemNanoTimeOverhead();

        timer.calibrate();
        long secondOverhead = timer.getSystemNanoTimeOverhead();

        assertThat(secondOverhead).isEqualTo(firstOverhead);
    }

    @Test
    void testReset() throws InterruptedException {
        timer.addCredits(100);
        Thread.sleep(1);

        timer.reset();

        assertThat(timer.getCreditsSincePromotion()).isEqualTo(0);
        assertThat(timer.shouldPromote()).isFalse();
    }

    @Test
    void testSnapshot() throws InterruptedException {
        timer.addCredits(50);
        Thread.sleep(TestConfig.sleepToExceed(TestConfig.normalPeriodNanos()));

        HeartbeatTimer.TimerSnapshot snapshot = timer.snapshot();

        assertThat(snapshot.creditsSincePromotion).isEqualTo(50);
        assertThat(snapshot.heartbeatPeriodNanos).isEqualTo(TestConfig.normalPeriodNanos());
        assertThat(snapshot.getElapsedNanos()).isGreaterThan(0);
        assertThat(snapshot.shouldPromote()).isTrue();
    }

    @Test
    void testMultiplePromotionCycles() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            Thread.sleep(TestConfig.sleepToExceed(TestConfig.normalPeriodNanos()));
            assertThat(timer.shouldPromote()).isTrue();
            timer.recordPromotion();
            assertThat(timer.getTimeSincePromotion()).isLessThan(TestConfig.normalPeriodNanos());
        }
    }

}
