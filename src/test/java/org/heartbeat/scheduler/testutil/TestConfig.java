package org.heartbeat.scheduler.testutil;

import org.heartbeat.scheduler.core.HeartbeatConfig;
import org.heartbeat.scheduler.utils.TimingCalibration;

/**
 * Shared test configuration utility that derives heartbeat periods
 * from machine-specific calibration rather than hardcoded values.
 * <p>
 * Calibration runs once per JVM and is cached.
 */
public final class TestConfig {

    private static final TimingCalibration.CalibrationResults CALIBRATION =
            TimingCalibration.calibrate();

    private TestConfig() {}

    /**
     * Calibrated promotion cost (τ) in nanoseconds.
     */
    public static long promotionCostNanos() {
        return CALIBRATION.promotionCost();
    }

    /**
     * Normal heartbeat period (N = 20τ) — appropriate for general task execution.
     */
    public static long normalPeriodNanos() {
        return CALIBRATION.recommendedHeartbeatPeriod();
    }

    /**
     * Aggressive heartbeat period (2τ) — forces frequent promotions.
     * Useful for tests that need to verify promotions actually happen.
     */
    public static long aggressivePeriodNanos() {
        return Math.max(CALIBRATION.promotionCost() * 2, 2);
    }

    /**
     * Long heartbeat period (1000 × N) — won't fire during normal test execution.
     * Useful for tests that need to verify the timer does NOT fire after a reset.
     */
    public static long longPeriodNanos() {
        return CALIBRATION.recommendedHeartbeatPeriod() * 1000;
    }

    /**
     * Sleep duration (ms) that reliably exceeds the given period.
     * Adds a generous margin to account for scheduling jitter.
     */
    public static long sleepToExceed(long periodNanos) {
        return Math.max(periodNanos / 1_000_000 + 5, 2);
    }

    /**
     * Config builder for normal execution tests.
     * Uses calibrated period and measured promotion cost.
     */
    public static HeartbeatConfig.Builder normalBuilder() {
        return HeartbeatConfig.newBuilder()
                .heartbeatPeriodNanos(normalPeriodNanos())
                .promotionCostNanos(promotionCostNanos());
    }

    /**
     * Config builder for tests that need aggressive promotion.
     * Very short period with minimal cost to force the timer to fire.
     */
    public static HeartbeatConfig.Builder aggressiveBuilder() {
        long aggressive = aggressivePeriodNanos();
        // Cost must be less than period for config validation to pass
        long cost = Math.max(aggressive / 2, 1);
        return HeartbeatConfig.newBuilder()
                .heartbeatPeriodNanos(aggressive)
                .promotionCostNanos(cost);
    }

    /**
     * Config builder for tests where the timer should NOT fire.
     * Uses a very long period so the heartbeat won't trigger during the test.
     */
    public static HeartbeatConfig.Builder longPeriodBuilder() {
        return HeartbeatConfig.newBuilder()
                .heartbeatPeriodNanos(longPeriodNanos())
                .promotionCostNanos(promotionCostNanos());
    }

    /**
     * Config builder where the heartbeat fires on the very first timer check.
     *
     * Uses the minimum valid (period=2ns, cost=1ns) pair — not calibrated because
     * this scenario is machine-independent: any JVM operation takes far longer than
     * 2 ns, so the timer is already elapsed before the first shouldPromote() call.
     *
     * Use this when a test needs timer.shouldPromote() to return true immediately
     * rather than "after some real-time elapses".
     */
    public static HeartbeatConfig.Builder instantFireBuilder() {
        return HeartbeatConfig.newBuilder()
                .heartbeatPeriodNanos(2)
                .promotionCostNanos(1);
    }

    /**
     * Get the raw calibration results for tests that need direct access.
     */
    public static TimingCalibration.CalibrationResults calibration() {
        return CALIBRATION;
    }
}
