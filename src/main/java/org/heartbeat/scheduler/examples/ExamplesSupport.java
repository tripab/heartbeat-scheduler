package org.heartbeat.scheduler.examples;

import org.heartbeat.scheduler.core.HeartbeatConfig;

final class ExamplesSupport {
    static final int WORK_STEPS = 100;

    private ExamplesSupport() {}

    static int intArg(String[] args, int index, int defaultValue) {
        return args.length > index ? Integer.parseInt(args[index]) : defaultValue;
    }

    static HeartbeatConfig defaultHeartbeatConfig() {
        return HeartbeatConfig.newBuilder()
                .heartbeatPeriodMicros(30)
                .promotionCostMicros(2)
                .enableStatistics(true)
                .build();
    }

    static long rangeSum(int start, int end) {
        long sum = 0;
        for (int i = start; i < end; i++) {
            sum += work(i);
        }
        return sum;
    }

    static long work(int x) {
        long v = x;
        for (int k = 0; k < WORK_STEPS; k++) {
            v = v * 6364136223846793005L + 1442695040888963407L;
        }
        return v;
    }

    static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
