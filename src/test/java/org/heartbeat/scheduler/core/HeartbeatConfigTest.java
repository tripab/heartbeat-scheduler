package org.heartbeat.scheduler.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HeartbeatConfigTest {

    @Test
    void testDefaultConfiguration() {
        HeartbeatConfig config = HeartbeatConfig.newBuilder().build();

        assertThat(config.getHeartbeatPeriodNanos()).isEqualTo(30_000);
        assertThat(config.getPromotionCostNanos()).isEqualTo(1_500);
        assertThat(config.getNumCarrierThreads())
            .isEqualTo(Runtime.getRuntime().availableProcessors());
        assertThat(config.isStatisticsEnabled()).isFalse();
        assertThat(config.isDebugLoggingEnabled()).isFalse();
    }

    @Test
    void testCustomConfiguration() {
        HeartbeatConfig config = HeartbeatConfig.newBuilder()
            .heartbeatPeriodNanos(50_000)
            .promotionCostNanos(2_500)
            .numCarrierThreads(8)
            .enableStatistics(true)
            .enableDebugLogging(true)
            .build();

        assertThat(config.getHeartbeatPeriodNanos()).isEqualTo(50_000);
        assertThat(config.getPromotionCostNanos()).isEqualTo(2_500);
        assertThat(config.getNumCarrierThreads()).isEqualTo(8);
        assertThat(config.isStatisticsEnabled()).isTrue();
        assertThat(config.isDebugLoggingEnabled()).isTrue();
    }

    @Test
    void testMicrosConvenience() {
        HeartbeatConfig config = HeartbeatConfig.newBuilder()
            .heartbeatPeriodMicros(50)  // 50μs
            .promotionCostMicros(2)      // 2μs
            .build();

        assertThat(config.getHeartbeatPeriodNanos()).isEqualTo(50_000);
        assertThat(config.getPromotionCostNanos()).isEqualTo(2_000);
    }

    @Test
    void testTargetOverheadPercent() {
        HeartbeatConfig config = HeartbeatConfig.newBuilder()
            .promotionCostMicros(2)      // τ = 2μs
            .targetOverheadPercent(5.0)  // 5% overhead
            .build();

        // For 5% overhead: N = (100/5) * τ = 20 * 2μs = 40μs
        assertThat(config.getHeartbeatPeriodNanos()).isEqualTo(40_000);
        assertThat(config.getExpectedOverheadPercentage()).isCloseTo(5.0, within(0.01));
    }

    @Test
    void testExpectedOverheadCalculation() {
        // N = 30μs, τ = 1.5μs
        HeartbeatConfig config = HeartbeatConfig.newBuilder()
            .heartbeatPeriodNanos(30_000)
            .promotionCostNanos(1_500)
            .build();

        // Overhead = τ/N = 1.5/30 = 0.05 = 5%
        assertThat(config.getExpectedOverheadFraction()).isCloseTo(0.05, within(0.001));
        assertThat(config.getExpectedOverheadPercentage()).isCloseTo(5.0, within(0.1));
    }

    @Test
    void testSpanIncreaseCalculation() {
        // N = 30μs, τ = 1.5μs
        HeartbeatConfig config = HeartbeatConfig.newBuilder()
            .heartbeatPeriodNanos(30_000)
            .promotionCostNanos(1_500)
            .build();

        // Span increase = 1 + N/τ = 1 + 30/1.5 = 1 + 20 = 21
        assertThat(config.getSpanIncreaseFactor()).isCloseTo(21.0, within(0.1));
    }

    @Test
    void testInvalidHeartbeatPeriod() {
        assertThatThrownBy(() -> 
            HeartbeatConfig.newBuilder()
                .heartbeatPeriodNanos(0)
                .build()
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> 
            HeartbeatConfig.newBuilder()
                .heartbeatPeriodNanos(-1000)
                .build()
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testInvalidPromotionCost() {
        assertThatThrownBy(() -> 
            HeartbeatConfig.newBuilder()
                .promotionCostNanos(0)
                .build()
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> 
            HeartbeatConfig.newBuilder()
                .promotionCostNanos(-1000)
                .build()
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testInvalidCarrierThreads() {
        assertThatThrownBy(() -> 
            HeartbeatConfig.newBuilder()
                .numCarrierThreads(0)
                .build()
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> 
            HeartbeatConfig.newBuilder()
                .numCarrierThreads(-1)
                .build()
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testInvalidTargetOverhead() {
        assertThatThrownBy(() -> 
            HeartbeatConfig.newBuilder()
                .targetOverheadPercent(0.0)
                .build()
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> 
            HeartbeatConfig.newBuilder()
                .targetOverheadPercent(100.0)
                .build()
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> 
            HeartbeatConfig.newBuilder()
                .targetOverheadPercent(-5.0)
                .build()
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> 
            HeartbeatConfig.newBuilder()
                .targetOverheadPercent(150.0)
                .build()
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testOverhead100PercentValidation() {
        // N must be > τ, otherwise overhead > 100%
        assertThatThrownBy(() -> 
            HeartbeatConfig.newBuilder()
                .heartbeatPeriodNanos(1_000)
                .promotionCostNanos(1_000)
                .build()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("must be greater than");

        assertThatThrownBy(() -> 
            HeartbeatConfig.newBuilder()
                .heartbeatPeriodNanos(1_000)
                .promotionCostNanos(2_000)
                .build()
        ).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testToString() {
        HeartbeatConfig config = HeartbeatConfig.newBuilder()
            .heartbeatPeriodMicros(30)
            .promotionCostNanos(1500)
            .numCarrierThreads(4)
            .build();

        String str = config.toString();
        assertThat(str).contains("30.00μs");
        assertThat(str).contains("1.50μs");
        assertThat(str).contains("carriers=4");
    }
}
