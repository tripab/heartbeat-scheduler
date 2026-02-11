package org.heartbeat.scheduler.core;

/**
 * Manages timing for heartbeat promotions.
 * Thread-local per carrier thread.
 * <p>
 * Implements the core timing logic: promote every N nanoseconds
 * of sequential work performed.
 */
public class HeartbeatTimer {
    private long lastPromotionTime;
    private long creditsSincePromotion;
    private final long heartbeatPeriodNanos;

    // Calibration state
    private long systemNanoTimeOverhead;
    private boolean calibrated;

    /**
     * Create a new timer with the given heartbeat period.
     *
     * @param heartbeatPeriodNanos The N parameter from the paper
     */
    public HeartbeatTimer(long heartbeatPeriodNanos) {
        if (heartbeatPeriodNanos <= 0) {
            throw new IllegalArgumentException("Heartbeat period must be positive");
        }

        this.heartbeatPeriodNanos = heartbeatPeriodNanos;
        this.lastPromotionTime = System.nanoTime();
        this.creditsSincePromotion = 0;
        this.calibrated = false;
    }

    /**
     * Check if it's time to promote based on elapsed time.
     * This is the core heartbeat logic: promote every N nanoseconds.
     *
     * @return true if elapsed time >= heartbeat period
     */
    public boolean shouldPromote() {
        long now = System.nanoTime();
        long elapsed = now - lastPromotionTime;
        return elapsed >= heartbeatPeriodNanos;
    }

    /**
     * Record that a promotion occurred.
     * Resets the timer and credit counter.
     */
    public void recordPromotion() {
        lastPromotionTime = System.nanoTime();
        creditsSincePromotion = 0;
    }

    /**
     * Add credits for work performed.
     * Credits track sequential steps since last promotion.
     * <p>
     * In an interpreter-based implementation (like the C++ prototype),
     * we'd increment credits for each basic block executed.
     * For a JIT-compiled implementation, we rely more on timing.
     *
     * @param credits Number of work units performed
     */
    public void addCredits(long credits) {
        creditsSincePromotion += credits;
    }

    /**
     * Get the number of credits accumulated since last promotion.
     */
    public long getCreditsSincePromotion() {
        return creditsSincePromotion;
    }

    /**
     * Get the time elapsed since last promotion.
     *
     * @return Nanoseconds since last promotion
     */
    public long getTimeSincePromotion() {
        return System.nanoTime() - lastPromotionTime;
    }

    /**
     * Get the time elapsed since last promotion in microseconds.
     */
    public long getTimeSincePromotionMicros() {
        return getTimeSincePromotion() / 1_000;
    }

    /**
     * Get the configured heartbeat period.
     */
    public long getHeartbeatPeriodNanos() {
        return heartbeatPeriodNanos;
    }

    /**
     * Get the configured heartbeat period in microseconds.
     */
    public long getHeartbeatPeriodMicros() {
        return heartbeatPeriodNanos / 1_000;
    }

    /**
     * Calibrate timing overhead.
     * Measures System.nanoTime() call cost through repeated sampling.
     */
    public void calibrate() {
        if (calibrated) {
            return;
        }

        final int iterations = 10_000;
        long sum = 0;

        // Warmup phase - let JIT optimize the code
        for (int i = 0; i < 1_000; i++) {
            long start = System.nanoTime();
            long end = System.nanoTime();
            sum += end - start;
        }
        systemNanoTimeOverhead = sum / iterations;
        sum = 0;

        // Measurement phase
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            long end = System.nanoTime();
            sum += (end - start);
        }

        systemNanoTimeOverhead = sum / iterations;
        calibrated = true;
    }

    /**
     * Get the measured System.nanoTime() overhead.
     * Automatically calibrates if not already done.
     *
     * @return Overhead in nanoseconds per call
     */
    public long getSystemNanoTimeOverhead() {
        if (!calibrated) {
            calibrate();
        }
        return systemNanoTimeOverhead;
    }

    /**
     * Check if timing has been calibrated.
     */
    public boolean isCalibrated() {
        return calibrated;
    }

    /**
     * Reset the timer to current time.
     * Useful when starting a new computation.
     */
    public void reset() {
        lastPromotionTime = System.nanoTime();
        creditsSincePromotion = 0;
    }

    /**
     * Get a snapshot of the timer state for statistics.
     */
    public TimerSnapshot snapshot() {
        return new TimerSnapshot(
                System.nanoTime(),
                lastPromotionTime,
                creditsSincePromotion,
                heartbeatPeriodNanos,
                systemNanoTimeOverhead
        );
    }

    @Override
    public String toString() {
        return String.format(
                "HeartbeatTimer[period=%.2fμs, elapsed=%.2fμs, credits=%d, calibrated=%s]",
                heartbeatPeriodNanos / 1000.0,
                getTimeSincePromotion() / 1000.0,
                creditsSincePromotion,
                calibrated
        );
    }

    /**
     * Immutable snapshot of timer state at a point in time.
     */
    public static class TimerSnapshot {
        public final long currentTime;
        public final long lastPromotionTime;
        public final long creditsSincePromotion;
        public final long heartbeatPeriodNanos;
        public final long systemNanoTimeOverhead;

        private TimerSnapshot(
                long currentTime,
                long lastPromotionTime,
                long creditsSincePromotion,
                long heartbeatPeriodNanos,
                long systemNanoTimeOverhead
        ) {
            this.currentTime = currentTime;
            this.lastPromotionTime = lastPromotionTime;
            this.creditsSincePromotion = creditsSincePromotion;
            this.heartbeatPeriodNanos = heartbeatPeriodNanos;
            this.systemNanoTimeOverhead = systemNanoTimeOverhead;
        }

        public long getElapsedNanos() {
            return currentTime - lastPromotionTime;
        }

        public long getElapsedMicros() {
            return getElapsedNanos() / 1_000;
        }

        public boolean shouldPromote() {
            return getElapsedNanos() >= heartbeatPeriodNanos;
        }

        @Override
        public String toString() {
            return String.format(
                    "TimerSnapshot[elapsed=%.2fμs, period=%.2fμs, credits=%d]",
                    getElapsedMicros() / 1000.0,
                    heartbeatPeriodNanos / 1000.0,
                    creditsSincePromotion
            );
        }
    }
}
