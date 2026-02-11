package org.heartbeat.scheduler.utils;

import org.heartbeat.scheduler.core.HeartbeatConfig;

/**
 * Simplified timing calibration that only measures virtual thread costs.
 * <p>
 * This version doesn't use internal JDK APIs and is safe to run on any
 * Java 21+ environment.
 */
public class SimpleCalibration {

    /**
     * Calibration result.
     */
    public static class Result {
        public final long promotionCostNanos;
        public final long recommendedHeartbeatNanos;
        public final double expectedOverheadPercent;

        Result(long promotionCostNanos, long recommendedHeartbeatNanos, double expectedOverheadPercent) {
            this.promotionCostNanos = promotionCostNanos;
            this.recommendedHeartbeatNanos = recommendedHeartbeatNanos;
            this.expectedOverheadPercent = expectedOverheadPercent;
        }

        @Override
        public String toString() {
            return String.format(
                    "τ = %,d ns (%.2f μs), N = %,d ns (%.2f μs), overhead = %.2f%%",
                    promotionCostNanos, promotionCostNanos / 1000.0,
                    recommendedHeartbeatNanos, recommendedHeartbeatNanos / 1000.0,
                    expectedOverheadPercent
            );
        }
    }

    /**
     * Measure virtual thread creation/start cost.
     */
    public static long measureVirtualThreadCost(int iterations) {
        // Warmup
        for (int i = 0; i < 100; i++) {
            try {
                Thread.ofVirtual().start(() -> {
                }).join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Measure
        long totalTime = 0;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            try {
                Thread.ofVirtual().start(() -> {
                }).join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long end = System.nanoTime();
            totalTime += (end - start);
        }

        return totalTime / iterations;
    }

    /**
     * Quick calibration (fewer iterations).
     */
    public static Result quickCalibrate() {
        return calibrate(1000);
    }

    /**
     * Standard calibration.
     */
    public static Result calibrate() {
        return calibrate(5000);
    }

    /**
     * Calibrate with custom iteration count.
     */
    public static Result calibrate(int iterations) {
        System.out.println("Measuring virtual thread cost (" + iterations + " iterations)...");

        long tau = measureVirtualThreadCost(iterations);
        long N = 20 * tau;  // For 5% overhead
        double overhead = (tau * 100.0) / N;

        System.out.println("Results:");
        System.out.println("  τ (promotion cost) = " + String.format("%,d ns (%.2f μs)", tau, tau / 1000.0));
        System.out.println("  N (heartbeat period) = " + String.format("%,d ns (%.2f μs)", N, N / 1000.0));
        System.out.println("  Expected overhead = " + String.format("%.2f%%", overhead));

        return new Result(tau, N, overhead);
    }

    /**
     * Create config from calibration result.
     */
    public static HeartbeatConfig createConfig(Result result) {
        return HeartbeatConfig.newBuilder()
                .promotionCostNanos(result.promotionCostNanos)
                .heartbeatPeriodNanos(result.recommendedHeartbeatNanos)
                .build();
    }

    /**
     * Main method for standalone calibration.
     */
    static void main() {
        System.out.println("=".repeat(70));
        System.out.println("Simple Heartbeat Calibration");
        System.out.println("=".repeat(70));
        System.out.println();

        Result result = calibrate();

        System.out.println();
        System.out.println("Use in code:");
        System.out.println();
        System.out.println("HeartbeatConfig config = HeartbeatConfig.newBuilder()");
        System.out.println("    .promotionCostNanos(" + result.promotionCostNanos + "L)");
        System.out.println("    .heartbeatPeriodNanos(" + result.recommendedHeartbeatNanos + "L)");
        System.out.println("    .build();");
        System.out.println();
    }
}