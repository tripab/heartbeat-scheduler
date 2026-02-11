package org.heartbeat.scheduler.utils;

import jdk.internal.vm.Continuation;

/**
 * Utilities for calibrating timing costs.
 * Measures τ (promotion cost) and other overheads for the system.
 * <p>
 * This implements the calibration protocol described in the paper
 * for determining appropriate values of N and τ.
 */
public class TimingCalibration {

    /**
     * Estimate the cost of creating and scheduling a virtual thread.
     * This estimates τ in the paper's notation.
     *
     * @return Average cost in nanoseconds
     */
    public static long estimateVirtualThreadCost() {
        final int warmup = 100;
        final int iterations = 1_000;

        // Warmup - let JIT optimize
        for (int i = 0; i < warmup; i++) {
            try {
                Thread.ofVirtual().start(() -> {
                }).join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        // Measure
        long totalTime = 0;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();

            try {
                Thread vt = Thread.ofVirtual().start(() -> {
                    // Minimal work
                });
                vt.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }

            long end = System.nanoTime();
            totalTime += (end - start);
        }

        return totalTime / iterations;
    }

    /**
     * Estimate continuation yield/resume cost.
     *
     * @return Average cost in nanoseconds
     */
    public static long estimateContinuationCost() {
        final int warmup = 100;
        final int iterations = 1_000;

        jdk.internal.vm.ContinuationScope scope = new jdk.internal.vm.ContinuationScope("calibration");

        // Warmup
        for (int i = 0; i < warmup; i++) {
            Continuation cont = new Continuation(scope, () -> {
                Continuation.yield(scope);
            });
            cont.run();
            cont.run();
        }

        // Measure
        long totalTime = 0;
        for (int i = 0; i < iterations; i++) {
            Continuation cont = new Continuation(scope, () -> {
                Continuation.yield(scope);
            });

            long start = System.nanoTime();
            cont.run();  // Will yield
            cont.run();  // Resume
            long end = System.nanoTime();

            totalTime += (end - start);
        }

        return totalTime / iterations;
    }

    /**
     * Measure System.nanoTime() overhead.
     *
     * @return Average overhead in nanoseconds
     */
    public static long measureNanoTimeOverhead() {
        final int warmup = 1_000;
        final int iterations = 10_000;
        long sum = 0;

        // Warmup
        for (int i = 0; i < warmup; i++) {
            System.nanoTime();
        }

        // Measure
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            long end = System.nanoTime();
            sum += (end - start);
        }

        return sum / iterations;
    }

    /**
     * Run comprehensive calibration and return results.
     *
     * @return Calibration results
     */
    public static CalibrationResults calibrate() {
        long nanoTimeOverhead = measureNanoTimeOverhead();
        long virtualThreadCost = estimateVirtualThreadCost();
        long continuationCost = estimateContinuationCost();

        // Use the more expensive of the two as our τ estimate
        long promotionCost = Math.max(virtualThreadCost, continuationCost);

        // Recommend heartbeat period (N = 20τ for 5% overhead)
        long recommendedPeriod = promotionCost * 20;

        return new CalibrationResults(
                nanoTimeOverhead,
                virtualThreadCost,
                continuationCost,
                promotionCost,
                recommendedPeriod
        );
    }

    /**
     * Run calibration and print results to console.
     */
    public static CalibrationResults calibrateAndPrint() {
        System.out.println("Running timing calibration...");
        System.out.println();

        CalibrationResults results = calibrate();

        System.out.printf("System.nanoTime() overhead:   %6d ns (%.3f μs)%n",
                results.nanoTimeOverhead,
                results.nanoTimeOverhead / 1000.0);

        System.out.printf("Virtual thread creation:      %6d ns (%.3f μs)%n",
                results.virtualThreadCost,
                results.virtualThreadCost / 1000.0);

        System.out.printf("Continuation yield/resume:    %6d ns (%.3f μs)%n",
                results.continuationCost,
                results.continuationCost / 1000.0);

        System.out.printf("Estimated promotion cost (τ): %6d ns (%.3f μs)%n",
                results.promotionCost,
                results.promotionCost / 1000.0);

        System.out.println();
        System.out.printf("Recommended heartbeat period (N = 20τ): %6d ns (%.3f μs)%n",
                results.recommendedHeartbeatPeriod,
                results.recommendedHeartbeatPeriod / 1000.0);

        System.out.printf("Expected overhead: %.2f%%%n",
                (results.promotionCost * 100.0) / results.recommendedHeartbeatPeriod);

        System.out.printf("Expected span increase: %.2fx%n",
                1.0 + ((double) results.recommendedHeartbeatPeriod / results.promotionCost));

        System.out.println();

        return results;
    }

    /**
     * Results from calibration.
     */
    public record CalibrationResults(long nanoTimeOverhead, long virtualThreadCost, long continuationCost,
                                     long promotionCost, long recommendedHeartbeatPeriod) {

        /**
         * Get expected overhead as a fraction (τ/N).
         */
        public double getExpectedOverheadFraction() {
            return (double) promotionCost / recommendedHeartbeatPeriod;
        }

        /**
         * Get expected overhead as a percentage.
         */
        public double getExpectedOverheadPercent() {
            return getExpectedOverheadFraction() * 100.0;
        }

        /**
         * Get expected span increase factor (1 + N/τ).
         */
        public double getExpectedSpanIncrease() {
            return 1.0 + ((double) recommendedHeartbeatPeriod / promotionCost);
        }

        @Override
        public String toString() {
            return String.format(
                    "CalibrationResults[τ=%.2fμs, N=%.2fμs, overhead=%.2f%%, spanIncrease=%.2fx]",
                    promotionCost / 1000.0,
                    recommendedHeartbeatPeriod / 1000.0,
                    getExpectedOverheadPercent(),
                    getExpectedSpanIncrease()
            );
        }
    }

    /**
     * Main method for standalone calibration.
     */
    static void main() {
        System.out.println("Heartbeat Scheduling - Timing Calibration Utility");
        System.out.println("=".repeat(70));
        System.out.println();

        var result = calibrateAndPrint();
        System.out.println(result);
    }
}
