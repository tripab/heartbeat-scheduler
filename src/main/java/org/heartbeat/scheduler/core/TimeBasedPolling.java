package org.heartbeat.scheduler.core;

/**
 * Poll after approximately N nanoseconds.
 * Uses calibrated System.nanoTime() cost.
 * <p>
 * This strategy checks the actual wall-clock time and polls
 * when enough time has elapsed. More accurate than count-based
 * for workloads with variable operation costs.
 * <p>
 * Good for:
 * - Workloads with varying operation costs
 * - When precise timing is important
 * - When operations may block
 */
public class TimeBasedPolling implements PollingStrategy {
    private final long pollIntervalNanos;
    private long lastPollTime;

    /**
     * Create a time-based polling strategy.
     *
     * @param pollIntervalNanos Poll after this many nanoseconds
     */
    public TimeBasedPolling(long pollIntervalNanos) {
        if (pollIntervalNanos <= 0) {
            throw new IllegalArgumentException("Poll interval must be positive");
        }
        this.pollIntervalNanos = pollIntervalNanos;
        this.lastPollTime = System.nanoTime();
    }

    @Override
    public boolean shouldPoll() {
        long now = System.nanoTime();
        long elapsed = now - lastPollTime;
        return elapsed >= pollIntervalNanos;
    }

    @Override
    public void recordPoll() {
        lastPollTime = System.nanoTime();
    }

    @Override
    public String getName() {
        return String.format("TimeBased[%.2fμs]", pollIntervalNanos / 1000.0);
    }

    @Override
    public void reset() {
        lastPollTime = System.nanoTime();
    }

    /**
     * Get the configured poll interval in nanoseconds.
     */
    public long getPollIntervalNanos() {
        return pollIntervalNanos;
    }

    /**
     * Get the configured poll interval in microseconds.
     */
    public long getPollIntervalMicros() {
        return pollIntervalNanos / 1_000;
    }

    /**
     * Get time since last poll in nanoseconds.
     */
    public long getTimeSinceLastPollNanos() {
        return System.nanoTime() - lastPollTime;
    }

    /**
     * Get time since last poll in microseconds.
     */
    public long getTimeSinceLastPollMicros() {
        return getTimeSinceLastPollNanos() / 1_000;
    }

    @Override
    public String toString() {
        return String.format(
                "TimeBasedPolling[interval=%.2fμs, elapsed=%.2fμs]",
                pollIntervalNanos / 1000.0,
                getTimeSinceLastPollNanos() / 1000.0
        );
    }

    /**
     * Create a polling strategy that polls every N nanoseconds.
     */
    public static TimeBasedPolling everyNanos(long nanos) {
        return new TimeBasedPolling(nanos);
    }

    /**
     * Create a polling strategy that polls every N microseconds.
     */
    public static TimeBasedPolling everyMicros(long micros) {
        return new TimeBasedPolling(micros * 1_000);
    }

    /**
     * Create a default time-based polling strategy (every 10μs).
     */
    public static TimeBasedPolling createDefault() {
        return everyMicros(10);
    }

    /**
     * Create a polling strategy appropriate for the given heartbeat period.
     * Polls at 1/10th the heartbeat period to ensure promotions happen
     * close to the target time.
     *
     * @param heartbeatPeriodNanos The heartbeat period (N parameter)
     */
    public static TimeBasedPolling forHeartbeatPeriod(long heartbeatPeriodNanos) {
        // Poll at 1/10th the heartbeat period
        long pollInterval = heartbeatPeriodNanos / 10;
        // Minimum 1μs to avoid excessive polling
        pollInterval = Math.max(pollInterval, 1_000);
        return new TimeBasedPolling(pollInterval);
    }
}
