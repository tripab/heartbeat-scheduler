package org.heartbeat.scheduler.core;

/**
 * Thread-local context for each carrier thread.
 * Tracks timing and promotion state per carrier thread.
 * <p>
 * This is the per-worker state that tracks when promotions
 * should occur. Each carrier (platform) thread has its own
 * context with its own timer.
 */
public class HeartbeatContext {
    private static final ThreadLocal<HeartbeatContext> CONTEXT =
            new ThreadLocal<>();

    private final HeartbeatTimer timer;
    private final PollingStrategy pollingStrategy;
    private final HeartbeatConfig config;

    // Statistics (optional)
    private long totalPromotions;
    private long totalPolls;
    private long totalOperations;

    /**
     * Create a new heartbeat context.
     *
     * @param config          The configuration
     * @param pollingStrategy The polling strategy to use
     */
    public HeartbeatContext(
            HeartbeatConfig config,
            PollingStrategy pollingStrategy
    ) {
        if (config == null) {
            throw new IllegalArgumentException("Config cannot be null");
        }
        if (pollingStrategy == null) {
            throw new IllegalArgumentException("Polling strategy cannot be null");
        }

        this.config = config;
        this.timer = new HeartbeatTimer(config.getHeartbeatPeriodNanos());
        this.pollingStrategy = pollingStrategy;
        this.totalPromotions = 0;
        this.totalPolls = 0;
        this.totalOperations = 0;
    }

    /**
     * Get the current thread's context.
     *
     * @return The context, or null if not set
     */
    public static HeartbeatContext current() {
        return CONTEXT.get();
    }

    /**
     * Set the current thread's context.
     *
     * @param context The context to set
     */
    public static void setCurrent(HeartbeatContext context) {
        CONTEXT.set(context);
    }

    /**
     * Clear the current thread's context.
     */
    public static void clearCurrent() {
        CONTEXT.remove();
    }

    /**
     * Get the timer for this context.
     */
    public HeartbeatTimer getTimer() {
        return timer;
    }

    /**
     * Get the polling strategy for this context.
     */
    public PollingStrategy getPollingStrategy() {
        return pollingStrategy;
    }

    /**
     * Get the configuration for this context.
     */
    public HeartbeatConfig getConfig() {
        return config;
    }

    /**
     * Check if we should poll and promote.
     * Called at instrumentation points in the code.
     * <p>
     * This is the main entry point for the heartbeat mechanism:
     * 1. Check if it's time to poll (based on polling strategy)
     * 2. If yes, check if it's time to promote (based on timer)
     * 3. Return true if promotion should occur
     *
     * @return true if it's time to promote
     */
    public boolean checkHeartbeat() {
        totalOperations++;

        if (pollingStrategy.shouldPoll()) {
            totalPolls++;
            pollingStrategy.recordPoll();

            if (timer.shouldPromote()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Record that a promotion occurred.
     * Updates timer and statistics.
     */
    public void recordPromotion() {
        timer.recordPromotion();
        totalPromotions++;
    }

    /**
     * Add work credits to the timer.
     *
     * @param credits Number of work units performed
     */
    public void addCredits(long credits) {
        timer.addCredits(credits);
    }

    /**
     * Get total number of promotions performed by this context.
     */
    public long getTotalPromotions() {
        return totalPromotions;
    }

    /**
     * Get total number of polls performed by this context.
     */
    public long getTotalPolls() {
        return totalPolls;
    }

    /**
     * Get total number of operations performed by this context.
     */
    public long getTotalOperations() {
        return totalOperations;
    }

    /**
     * Get polling rate (polls / operations).
     */
    public double getPollingRate() {
        return totalOperations > 0
                ? (double) totalPolls / totalOperations
                : 0.0;
    }

    /**
     * Get promotion rate (promotions / polls).
     */
    public double getPromotionRate() {
        return totalPolls > 0
                ? (double) totalPromotions / totalPolls
                : 0.0;
    }

    /**
     * Reset statistics.
     */
    public void resetStatistics() {
        totalPromotions = 0;
        totalPolls = 0;
        totalOperations = 0;
    }

    /**
     * Reset timer and statistics.
     */
    public void reset() {
        timer.reset();
        pollingStrategy.reset();
        resetStatistics();
    }

    /**
     * Get a snapshot of statistics for this context.
     */
    public ContextStatistics getStatistics() {
        return new ContextStatistics(
                totalOperations,
                totalPolls,
                totalPromotions,
                timer.getTimeSincePromotion(),
                timer.getCreditsSincePromotion()
        );
    }

    @Override
    public String toString() {
        return String.format(
                "HeartbeatContext[ops=%d, polls=%d, promotions=%d, pollRate=%.4f, promoRate=%.4f]",
                totalOperations,
                totalPolls,
                totalPromotions,
                getPollingRate(),
                getPromotionRate()
        );
    }

    /**
     * Immutable snapshot of context statistics.
     */
    public static class ContextStatistics {
        public final long totalOperations;
        public final long totalPolls;
        public final long totalPromotions;
        public final long timeSincePromotionNanos;
        public final long creditsSincePromotion;

        private ContextStatistics(
                long totalOperations,
                long totalPolls,
                long totalPromotions,
                long timeSincePromotionNanos,
                long creditsSincePromotion
        ) {
            this.totalOperations = totalOperations;
            this.totalPolls = totalPolls;
            this.totalPromotions = totalPromotions;
            this.timeSincePromotionNanos = timeSincePromotionNanos;
            this.creditsSincePromotion = creditsSincePromotion;
        }

        public double getPollingRate() {
            return totalOperations > 0
                    ? (double) totalPolls / totalOperations
                    : 0.0;
        }

        public double getPromotionRate() {
            return totalPolls > 0
                    ? (double) totalPromotions / totalPolls
                    : 0.0;
        }

        @Override
        public String toString() {
            return String.format(
                    "ContextStatistics[ops=%d, polls=%d (%.2f%%), promotions=%d (%.2f%%), elapsed=%.2fμs, credits=%d]",
                    totalOperations,
                    totalPolls,
                    getPollingRate() * 100.0,
                    totalPromotions,
                    getPromotionRate() * 100.0,
                    timeSincePromotionNanos / 1000.0,
                    creditsSincePromotion
            );
        }
    }
}
