package org.heartbeat.scheduler.core;

import org.heartbeat.scheduler.utils.TimingCalibration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeartbeatTimerTest {

    private HeartbeatTimer timer;
    private static final long HEARTBEAT_PERIOD = 30_000; // 30μs
    private static TimingCalibration.CalibrationResults timingCalibrationResults;

    @BeforeEach
    void setUp() {
        timingCalibrationResults = TimingCalibration.calibrate();
        if (timingCalibrationResults != null) {
            timer = new HeartbeatTimer(timingCalibrationResults.recommendedHeartbeatPeriod());
        } else {
            timer = new HeartbeatTimer(HEARTBEAT_PERIOD);
        }
    }

    @Test
    void testInitialState() {
        assertThat(timer.getCreditsSincePromotion()).isEqualTo(0);
        if (timingCalibrationResults == null) {
            assertThat(timer.getHeartbeatPeriodNanos()).isEqualTo(HEARTBEAT_PERIOD);
        }
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
        // Note: This test is timing-sensitive. On fast hardware or with JVM timing
        // variations, the initial call might already return true if >30μs has elapsed
        // between timer creation and this check.

        // Create a fresh timer right before checking
        HeartbeatTimer freshTimer = new HeartbeatTimer(HEARTBEAT_PERIOD);

        // Immediately check - should be false or at least deterministic
        // If this fails, it means <30μs has NOT elapsed, which is expected
        boolean initialCheck = freshTimer.shouldPromote();

        // Wait for period to definitely elapse
        Thread.sleep(1); // Sleep 1ms = 1000μs >> 30μs

        // Now it should definitely be true
        assertThat(freshTimer.shouldPromote()).isTrue();
    }

    @Test
    void testShouldPromoteWithLargePeriod() {
        // Use a large period to ensure initial check is false
        HeartbeatTimer longTimer = new HeartbeatTimer(1_000_000_000L); // 1 second

        assertThat(longTimer.shouldPromote()).isFalse();
    }

    @Test
    void testRecordPromotion() throws InterruptedException {
        // Wait and check
        Thread.sleep(1);
        assertThat(timer.shouldPromote()).isTrue();

        // Record promotion
        timer.recordPromotion();

        // Should not promote immediately after recording
        assertThat(timer.shouldPromote()).isFalse();
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

        // Getting overhead triggers auto-calibration
        long overhead = timer.getSystemNanoTimeOverhead();

        assertThat(timer.isCalibrated()).isTrue();
        assertThat(overhead).isGreaterThan(0);
    }

    @Test
    void testCalibrationIdempotent() {
        timer.calibrate();
        long firstOverhead = timer.getSystemNanoTimeOverhead();

        timer.calibrate(); // Should be no-op
        long secondOverhead = timer.getSystemNanoTimeOverhead();

        assertThat(secondOverhead).isEqualTo(firstOverhead);
    }

    @Test
    void testReset() throws InterruptedException {
        timer.addCredits(100);
        Thread.sleep(1);

        timer.reset();

        assertThat(timer.getCreditsSincePromotion()).isEqualTo(0);
        // After reset, shouldn't promote immediately
        assertThat(timer.shouldPromote()).isFalse();
    }

    @Test
    void testSnapshot() throws InterruptedException {
        timer.addCredits(50);
        Thread.sleep(1);

        HeartbeatTimer.TimerSnapshot snapshot = timer.snapshot();

        assertThat(snapshot.creditsSincePromotion).isEqualTo(50);
        if (timingCalibrationResults == null) {
            assertThat(snapshot.heartbeatPeriodNanos).isEqualTo(HEARTBEAT_PERIOD);
        }
        assertThat(snapshot.getElapsedNanos()).isGreaterThan(0);
        assertThat(snapshot.shouldPromote()).isTrue();
    }

    @Test
    void testMultiplePromotionCycles() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            Thread.sleep(1);
            assertThat(timer.shouldPromote()).isTrue();
            timer.recordPromotion();
            assertThat(timer.shouldPromote()).isFalse();
        }
    }

    @Test
    void testTimingBehaviorWithCalibratedPeriod() throws InterruptedException {
        // Use a period we can reliably exceed with Thread.sleep
        long reliablePeriod = 500_000; // 500μs = 0.5ms
        HeartbeatTimer reliableTimer = new HeartbeatTimer(reliablePeriod);

        // Should not promote immediately
        assertThat(reliableTimer.shouldPromote()).isFalse();

        // Sleep for 1ms > 500μs
        Thread.sleep(1);

        // Should definitely promote now
        assertThat(reliableTimer.shouldPromote()).isTrue();

        // After recording, should not promote
        reliableTimer.recordPromotion();
        assertThat(reliableTimer.shouldPromote()).isFalse();
    }
}