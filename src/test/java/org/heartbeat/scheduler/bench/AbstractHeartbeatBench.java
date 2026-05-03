package org.heartbeat.scheduler.bench;

import org.heartbeat.scheduler.core.HeartbeatConfig;
import org.heartbeat.scheduler.executor.VirtualThreadExecutor;
import org.heartbeat.scheduler.utils.TimingCalibration;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Shared JMH preamble for all heartbeat benchmarks.
 *
 * <p>Provides: JMH timing/fork annotations, the default {@link HeartbeatConfig}
 * factory, the shared {@code executor} field, and a {@code @TearDown} that
 * closes it. Concrete benchmarks supply their own {@code @Setup} (which assigns
 * {@code this.executor}) and their {@code @Benchmark} methods.
 *
 * <p>Subclasses that manage additional resources (e.g. a {@code ForkJoinPool})
 * add their own {@code @TearDown} method; JMH calls all tear-down methods in
 * the hierarchy.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(value = 3, jvmArgsPrepend = "--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED")
public abstract class AbstractHeartbeatBench {

    /** Calibrated once per JVM; shared across all benchmark subclasses. */
    protected static final TimingCalibration.CalibrationResults CALIBRATION =
            TimingCalibration.calibrate();

    protected VirtualThreadExecutor executor;

    /**
     * Default config derived from machine-measured τ: period = 20τ (5% overhead target),
     * cost = τ. Using calibrated values avoids hardcoded assumptions that break on
     * machines where virtual-thread creation cost differs significantly from 2 μs.
     */
    protected static HeartbeatConfig defaultConfig() {
        return HeartbeatConfig.newBuilder()
                .heartbeatPeriodNanos(CALIBRATION.recommendedHeartbeatPeriod())
                .promotionCostNanos(CALIBRATION.promotionCost())
                .build();
    }

    @TearDown(Level.Trial)
    public void closeExecutor() {
        executor.close();
    }
}
