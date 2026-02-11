package org.heartbeat.scheduler.core;

/**
 * Configuration for Heartbeat Scheduler.
 * 
 * Key parameters:
 * - N (heartbeatPeriodNanos): Time between promotions
 * - τ (promotionCostNanos): Cost of creating/scheduling a thread
 * 
 * For bounded overhead of k%, set N = (100/k) * τ
 * Example: For 5% overhead, N = 20τ
 */
public class HeartbeatConfig {
    private final long heartbeatPeriodNanos;
    private final long promotionCostNanos;
    private final int numCarrierThreads;
    private final boolean enableStatistics;
    private final boolean enableDebugLogging;

    private HeartbeatConfig(Builder builder) {
        this.heartbeatPeriodNanos = builder.heartbeatPeriodNanos;
        this.promotionCostNanos = builder.promotionCostNanos;
        this.numCarrierThreads = builder.numCarrierThreads;
        this.enableStatistics = builder.enableStatistics;
        this.enableDebugLogging = builder.enableDebugLogging;
    }

    public long getHeartbeatPeriodNanos() {
        return heartbeatPeriodNanos;
    }

    public long getPromotionCostNanos() {
        return promotionCostNanos;
    }

    public int getNumCarrierThreads() {
        return numCarrierThreads;
    }

    public boolean isStatisticsEnabled() {
        return enableStatistics;
    }

    public boolean isDebugLoggingEnabled() {
        return enableDebugLogging;
    }

    /**
     * Calculate expected overhead fraction (τ/N).
     */
    public double getExpectedOverheadFraction() {
        return (double) promotionCostNanos / heartbeatPeriodNanos;
    }

    /**
     * Calculate expected overhead percentage.
     */
    public double getExpectedOverheadPercentage() {
        return getExpectedOverheadFraction() * 100.0;
    }

    /**
     * Calculate span increase factor (1 + N/τ).
     */
    public double getSpanIncreaseFactor() {
        return 1.0 + ((double) heartbeatPeriodNanos / promotionCostNanos);
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return String.format(
            "HeartbeatConfig[period=%.2fμs, cost=%.2fμs, carriers=%d, " +
            "overhead=%.2f%%, spanIncrease=%.2fx, stats=%s]",
            heartbeatPeriodNanos / 1000.0,
            promotionCostNanos / 1000.0,
            numCarrierThreads,
            getExpectedOverheadPercentage(),
            getSpanIncreaseFactor(),
            enableStatistics
        );
    }

    /**
     * Builder for HeartbeatConfig with sensible defaults.
     */
    public static class Builder {
        // Default: 30μs heartbeat period (typical value from paper)
        private long heartbeatPeriodNanos = 30_000;
        
        // Default: 1.5μs promotion cost (typical for virtual threads)
        private long promotionCostNanos = 1_500;
        
        // Default: one carrier thread per processor
        private int numCarrierThreads = Runtime.getRuntime().availableProcessors();
        
        private boolean enableStatistics = false;
        private boolean enableDebugLogging = false;

        /**
         * Set heartbeat period in nanoseconds.
         * This is the N parameter in the paper.
         */
        public Builder heartbeatPeriodNanos(long nanos) {
            if (nanos <= 0) {
                throw new IllegalArgumentException("Heartbeat period must be positive");
            }
            this.heartbeatPeriodNanos = nanos;
            return this;
        }

        /**
         * Set heartbeat period in microseconds (convenience method).
         */
        public Builder heartbeatPeriodMicros(long micros) {
            return heartbeatPeriodNanos(micros * 1_000);
        }

        /**
         * Set promotion cost in nanoseconds.
         * This is the τ parameter in the paper.
         */
        public Builder promotionCostNanos(long nanos) {
            if (nanos <= 0) {
                throw new IllegalArgumentException("Promotion cost must be positive");
            }
            this.promotionCostNanos = nanos;
            return this;
        }

        /**
         * Set promotion cost in microseconds (convenience method).
         */
        public Builder promotionCostMicros(long micros) {
            return promotionCostNanos(micros * 1_000);
        }

        /**
         * Set target overhead percentage and automatically calculate N.
         * For example, targetOverheadPercent=5.0 means 5% overhead.
         * Sets N = (100/k) * τ
         */
        public Builder targetOverheadPercent(double percent) {
            if (percent <= 0 || percent >= 100) {
                throw new IllegalArgumentException(
                    "Target overhead must be between 0 and 100 percent"
                );
            }
            this.heartbeatPeriodNanos = 
                (long) ((100.0 / percent) * promotionCostNanos);
            return this;
        }

        /**
         * Set number of carrier (platform) threads.
         */
        public Builder numCarrierThreads(int count) {
            if (count <= 0) {
                throw new IllegalArgumentException("Number of carriers must be positive");
            }
            this.numCarrierThreads = count;
            return this;
        }

        /**
         * Enable or disable statistics collection.
         */
        public Builder enableStatistics(boolean enable) {
            this.enableStatistics = enable;
            return this;
        }

        /**
         * Enable or disable debug logging.
         */
        public Builder enableDebugLogging(boolean enable) {
            this.enableDebugLogging = enable;
            return this;
        }

        /**
         * Build the configuration.
         */
        public HeartbeatConfig build() {
            // Validate that N > τ (otherwise overhead would be > 100%)
            if (heartbeatPeriodNanos <= promotionCostNanos) {
                throw new IllegalStateException(
                    String.format(
                        "Heartbeat period (%d ns) must be greater than " +
                        "promotion cost (%d ns) to have overhead < 100%%",
                        heartbeatPeriodNanos, promotionCostNanos
                    )
                );
            }
            
            return new HeartbeatConfig(this);
        }
    }
}
