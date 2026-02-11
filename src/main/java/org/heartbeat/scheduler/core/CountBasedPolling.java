package org.heartbeat.scheduler.core;

/**
 * Poll after every N operations.
 * <p>
 * This is the simplest polling strategy - just count operations
 * and poll when the count reaches the threshold.
 * <p>
 * Good for:
 * - Uniform workloads
 * - When operation cost is relatively consistent
 * - Minimizing timing overhead
 */
public class CountBasedPolling implements PollingStrategy {
    private final int pollInterval;
    private int operationsSincePoll;

    /**
     * Create a count-based polling strategy.
     *
     * @param pollInterval Poll after this many operations
     */
    public CountBasedPolling(int pollInterval) {
        if (pollInterval <= 0) {
            throw new IllegalArgumentException("Poll interval must be positive");
        }
        this.pollInterval = pollInterval;
        this.operationsSincePoll = 0;
    }

    @Override
    public boolean shouldPoll() {
        operationsSincePoll++;
        return operationsSincePoll >= pollInterval;
    }

    @Override
    public void recordPoll() {
        operationsSincePoll = 0;
    }

    @Override
    public String getName() {
        return "CountBased[" + pollInterval + "]";
    }

    @Override
    public void reset() {
        operationsSincePoll = 0;
    }

    /**
     * Get the configured poll interval.
     */
    public int getPollInterval() {
        return pollInterval;
    }

    /**
     * Get the current operation count.
     */
    public int getOperationsSincePoll() {
        return operationsSincePoll;
    }

    @Override
    public String toString() {
        return String.format(
                "CountBasedPolling[interval=%d, current=%d]",
                pollInterval,
                operationsSincePoll
        );
    }

    /**
     * Create a polling strategy that polls every N operations.
     * <p>
     * Common values:
     * - 100: Poll frequently (low latency, higher overhead)
     * - 1000: Poll moderately (balanced)
     * - 10000: Poll infrequently (low overhead, higher latency)
     */
    public static CountBasedPolling every(int operations) {
        return new CountBasedPolling(operations);
    }

    /**
     * Create a default count-based polling strategy (every 1000 operations).
     */
    public static CountBasedPolling createDefault() {
        return new CountBasedPolling(1000);
    }
}
