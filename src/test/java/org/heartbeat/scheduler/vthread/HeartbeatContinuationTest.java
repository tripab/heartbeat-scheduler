package org.heartbeat.scheduler.vthread;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeartbeatContinuationTest {

    private ContinuationScope scope = new ContinuationScope("test");

    @Test
    void testRunToCompletion() {
        AtomicBoolean ran = new AtomicBoolean(false);
        HeartbeatContinuation cont = new HeartbeatContinuation(scope, () -> ran.set(true));

        assertThat(cont.isDone()).isFalse();
        assertThat(cont.hasYielded()).isFalse();

        cont.resume();

        assertThat(ran.get()).isTrue();
        assertThat(cont.isDone()).isTrue();
        assertThat(cont.hasYielded()).isFalse();
    }

    @Test
    void testYieldAndResume() {
        AtomicInteger step = new AtomicInteger(0);
        HeartbeatContinuation cont = new HeartbeatContinuation(scope, () -> {
            step.set(1);
            HeartbeatContinuation.yieldCurrent(scope);
            step.set(2);
        });

        cont.resume(); // runs until yield
        assertThat(step.get()).isEqualTo(1);
        assertThat(cont.isDone()).isFalse();

        cont.resume(); // resumes after yield, runs to completion
        assertThat(step.get()).isEqualTo(2);
        assertThat(cont.isDone()).isTrue();
    }

    @Test
    void testMultipleYields() {
        AtomicInteger step = new AtomicInteger(0);
        HeartbeatContinuation cont = new HeartbeatContinuation(scope, () -> {
            step.incrementAndGet();
            HeartbeatContinuation.yieldCurrent(scope);
            step.incrementAndGet();
            HeartbeatContinuation.yieldCurrent(scope);
            step.incrementAndGet();
        });

        cont.resume();
        assertThat(step.get()).isEqualTo(1);
        assertThat(cont.isDone()).isFalse();

        cont.resume();
        assertThat(step.get()).isEqualTo(2);
        assertThat(cont.isDone()).isFalse();

        cont.resume();
        assertThat(step.get()).isEqualTo(3);
        assertThat(cont.isDone()).isTrue();
    }

    @Test
    void testResumeAfterDoneIsNoOp() {
        HeartbeatContinuation cont = new HeartbeatContinuation(scope, () -> {});
        cont.resume();
        assertThat(cont.isDone()).isTrue();

        // Should not throw
        cont.resume();
        assertThat(cont.isDone()).isTrue();
    }

    @Test
    void testNullScopeThrows() {
        assertThatThrownBy(() -> new HeartbeatContinuation(null, () -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Scope");
    }

    @Test
    void testNullTargetThrows() {
        assertThatThrownBy(() -> new HeartbeatContinuation(scope, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target");
    }

}
