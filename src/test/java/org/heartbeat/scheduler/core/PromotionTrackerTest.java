package org.heartbeat.scheduler.core;

import org.heartbeat.scheduler.sync.PromotionPoint;
import org.heartbeat.scheduler.vthread.ContinuationScope;
import org.heartbeat.scheduler.vthread.HeartbeatContinuation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PromotionTrackerTest {

    private PromotionTracker tracker;
    private ContinuationScope scope;

    @BeforeEach
    void setUp() {
        tracker = new PromotionTracker();
        scope = new ContinuationScope("test");
    }

    private PromotionPoint createFrame() {
        HeartbeatContinuation cont = new HeartbeatContinuation(scope, () -> {
        });
        return new PromotionPoint(cont, scope);
    }

    @Test
    void testInitialState() {
        assertThat(tracker.isEmpty()).isTrue();
        assertThat(tracker.size()).isEqualTo(0);
        assertThat(tracker.hasPromotableFrames()).isFalse();
        assertThat(tracker.getOldestPromotable()).isNull();
        assertThat(tracker.getNewestFrame()).isNull();
        assertThat(tracker.getTotalFramesPushed()).isEqualTo(0);
        assertThat(tracker.getTotalFramesPopped()).isEqualTo(0);
        assertThat(tracker.getTotalFramesPromoted()).isEqualTo(0);
    }

    @Test
    void testPushSingleFrame() {
        PromotionPoint frame = createFrame();
        tracker.pushFrame(frame);

        assertThat(tracker.size()).isEqualTo(1);
        assertThat(tracker.isEmpty()).isFalse();
        assertThat(tracker.hasPromotableFrames()).isTrue();
        assertThat(tracker.getOldestPromotable()).isSameAs(frame);
        assertThat(tracker.getNewestFrame()).isSameAs(frame);
        assertThat(tracker.getTotalFramesPushed()).isEqualTo(1);
    }

    @Test
    void testPushNullFrame() {
        assertThatThrownBy(() -> tracker.pushFrame(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void testPushMultipleFrames() {
        PromotionPoint f1 = createFrame();
        PromotionPoint f2 = createFrame();
        PromotionPoint f3 = createFrame();

        tracker.pushFrame(f1);
        tracker.pushFrame(f2);
        tracker.pushFrame(f3);

        assertThat(tracker.size()).isEqualTo(3);

        // f1 is oldest (tail), f3 is newest (head)
        assertThat(tracker.getOldestPromotable()).isSameAs(f1);
        assertThat(tracker.getNewestFrame()).isSameAs(f3);

        // Verify list structure: f1 <-> f2 <-> f3
        assertThat(f1.isTail()).isTrue();
        assertThat(f1.getNext()).isSameAs(f2);
        assertThat(f2.getPrev()).isSameAs(f1);
        assertThat(f2.getNext()).isSameAs(f3);
        assertThat(f3.getPrev()).isSameAs(f2);
        assertThat(f3.isHead()).isTrue();
    }

    @Test
    void testPopFromEmptyTracker() {
        PromotionPoint popped = tracker.popFrame();
        assertThat(popped).isNull();
    }

    @Test
    void testPopSingleFrame() {
        PromotionPoint frame = createFrame();
        tracker.pushFrame(frame);

        PromotionPoint popped = tracker.popFrame();

        assertThat(popped).isSameAs(frame);
        assertThat(tracker.isEmpty()).isTrue();
        assertThat(tracker.size()).isEqualTo(0);
        assertThat(tracker.getTotalFramesPopped()).isEqualTo(1);

        // Frame should be detached
        assertThat(frame.getPrev()).isNull();
        assertThat(frame.getNext()).isNull();
    }

    @Test
    void testPopMultipleFrames() {
        PromotionPoint f1 = createFrame();
        PromotionPoint f2 = createFrame();
        PromotionPoint f3 = createFrame();

        tracker.pushFrame(f1);
        tracker.pushFrame(f2);
        tracker.pushFrame(f3);

        // Pop in LIFO order (stack behavior)
        PromotionPoint p1 = tracker.popFrame();
        assertThat(p1).isSameAs(f3);
        assertThat(tracker.size()).isEqualTo(2);
        assertThat(tracker.getNewestFrame()).isSameAs(f2);

        PromotionPoint p2 = tracker.popFrame();
        assertThat(p2).isSameAs(f2);
        assertThat(tracker.size()).isEqualTo(1);
        assertThat(tracker.getNewestFrame()).isSameAs(f1);

        PromotionPoint p3 = tracker.popFrame();
        assertThat(p3).isSameAs(f1);
        assertThat(tracker.isEmpty()).isTrue();
    }

    @Test
    void testPromoteOldestFromEmptyTracker() {
        PromotionPoint promoted = tracker.promoteOldest();
        assertThat(promoted).isNull();
    }

    @Test
    void testPromoteOldestSingleFrame() {
        PromotionPoint frame = createFrame();
        tracker.pushFrame(frame);

        PromotionPoint promoted = tracker.promoteOldest();

        assertThat(promoted).isSameAs(frame);
        assertThat(promoted.isPromoted()).isTrue();
        assertThat(tracker.isEmpty()).isTrue();
        assertThat(tracker.getTotalFramesPromoted()).isEqualTo(1);
    }

    @Test
    void testPromoteOldestMultipleFrames() {
        PromotionPoint f1 = createFrame();
        PromotionPoint f2 = createFrame();
        PromotionPoint f3 = createFrame();

        tracker.pushFrame(f1);
        tracker.pushFrame(f2);
        tracker.pushFrame(f3);

        // Promote oldest (FIFO for promotions)
        PromotionPoint p1 = tracker.promoteOldest();
        assertThat(p1).isSameAs(f1);
        assertThat(p1.isPromoted()).isTrue();
        assertThat(tracker.size()).isEqualTo(2);
        assertThat(tracker.getOldestPromotable()).isSameAs(f2);

        PromotionPoint p2 = tracker.promoteOldest();
        assertThat(p2).isSameAs(f2);
        assertThat(tracker.size()).isEqualTo(1);
        assertThat(tracker.getOldestPromotable()).isSameAs(f3);

        PromotionPoint p3 = tracker.promoteOldest();
        assertThat(p3).isSameAs(f3);
        assertThat(tracker.isEmpty()).isTrue();
    }

    @Test
    void testMixedPushPopPromote() {
        PromotionPoint f1 = createFrame();
        PromotionPoint f2 = createFrame();
        PromotionPoint f3 = createFrame();

        tracker.pushFrame(f1);
        tracker.pushFrame(f2);

        // Pop newest (f2)
        PromotionPoint popped = tracker.popFrame();
        assertThat(popped).isSameAs(f2);
        assertThat(tracker.size()).isEqualTo(1);

        tracker.pushFrame(f3);
        assertThat(tracker.size()).isEqualTo(2);

        // Promote oldest (f1)
        PromotionPoint promoted = tracker.promoteOldest();
        assertThat(promoted).isSameAs(f1);
        assertThat(tracker.size()).isEqualTo(1);
        assertThat(tracker.getOldestPromotable()).isSameAs(f3);
    }

    @Test
    void testRemoveFrame() {
        PromotionPoint f1 = createFrame();
        PromotionPoint f2 = createFrame();
        PromotionPoint f3 = createFrame();

        tracker.pushFrame(f1);
        tracker.pushFrame(f2);
        tracker.pushFrame(f3);

        // Remove middle frame
        boolean removed = tracker.removeFrame(f2);

        assertThat(removed).isTrue();
        assertThat(tracker.size()).isEqualTo(2);

        // List should be: f1 <-> f3
        assertThat(f1.getNext()).isSameAs(f3);
        assertThat(f3.getPrev()).isSameAs(f1);
    }

    @Test
    void testRemoveNullFrame() {
        boolean removed = tracker.removeFrame(null);
        assertThat(removed).isFalse();
    }

    @Test
    void testRemoveFrameFromEmptyTracker() {
        PromotionPoint frame = createFrame();
        boolean removed = tracker.removeFrame(frame);
        assertThat(removed).isFalse();
    }

    @Test
    void testRemoveHead() {
        PromotionPoint f1 = createFrame();
        PromotionPoint f2 = createFrame();

        tracker.pushFrame(f1);
        tracker.pushFrame(f2);

        // Remove head (newest)
        boolean removed = tracker.removeFrame(f2);

        assertThat(removed).isTrue();
        assertThat(tracker.size()).isEqualTo(1);
        assertThat(tracker.getNewestFrame()).isSameAs(f1);
    }

    @Test
    void testRemoveTail() {
        PromotionPoint f1 = createFrame();
        PromotionPoint f2 = createFrame();

        tracker.pushFrame(f1);
        tracker.pushFrame(f2);

        // Remove tail (oldest)
        boolean removed = tracker.removeFrame(f1);

        assertThat(removed).isTrue();
        assertThat(tracker.size()).isEqualTo(1);
        assertThat(tracker.getOldestPromotable()).isSameAs(f2);
    }

    @Test
    void testClear() {
        tracker.pushFrame(createFrame());
        tracker.pushFrame(createFrame());
        tracker.pushFrame(createFrame());

        assertThat(tracker.size()).isEqualTo(3);

        tracker.clear();

        assertThat(tracker.isEmpty()).isTrue();
        assertThat(tracker.size()).isEqualTo(0);
        assertThat(tracker.getOldestPromotable()).isNull();
        assertThat(tracker.getNewestFrame()).isNull();
    }

    @Test
    void testStatistics() {
        PromotionPoint f1 = createFrame();
        PromotionPoint f2 = createFrame();
        PromotionPoint f3 = createFrame();

        tracker.pushFrame(f1);
        tracker.pushFrame(f2);
        tracker.pushFrame(f3);

        assertThat(tracker.getTotalFramesPushed()).isEqualTo(3);

        tracker.popFrame();
        assertThat(tracker.getTotalFramesPopped()).isEqualTo(1);

        tracker.promoteOldest();
        assertThat(tracker.getTotalFramesPromoted()).isEqualTo(1);

        // Promotion rate = 1 / (1 + 1) = 0.5
        assertThat(tracker.getPromotionRate()).isCloseTo(0.5, within(0.01));
    }

    @Test
    void testPromotionRateAllPopped() {
        tracker.pushFrame(createFrame());
        tracker.pushFrame(createFrame());

        tracker.popFrame();
        tracker.popFrame();

        // All popped, none promoted
        assertThat(tracker.getPromotionRate()).isCloseTo(0.0, within(0.01));
    }

    @Test
    void testPromotionRateAllPromoted() {
        tracker.pushFrame(createFrame());
        tracker.pushFrame(createFrame());

        tracker.promoteOldest();
        tracker.promoteOldest();

        // All promoted, none popped
        assertThat(tracker.getPromotionRate()).isCloseTo(1.0, within(0.01));
    }

    @Test
    void testGetOldestFrameAge() throws InterruptedException {
        assertThat(tracker.getOldestFrameAgeNanos()).isEqualTo(-1);
        assertThat(tracker.getOldestFrameAgeMicros()).isEqualTo(-1);

        PromotionPoint frame = createFrame();
        tracker.pushFrame(frame);

        Thread.sleep(1); // Let some time pass

        long ageNanos = tracker.getOldestFrameAgeNanos();
        long ageMicros = tracker.getOldestFrameAgeMicros();

        assertThat(ageNanos).isGreaterThan(0);
        assertThat(ageMicros).isGreaterThan(0);
    }

    @Test
    void testResetStatistics() {
        tracker.pushFrame(createFrame());
        tracker.popFrame();
        tracker.pushFrame(createFrame());
        tracker.promoteOldest();

        assertThat(tracker.getTotalFramesPushed()).isEqualTo(2);
        assertThat(tracker.getTotalFramesPopped()).isEqualTo(1);
        assertThat(tracker.getTotalFramesPromoted()).isEqualTo(1);

        tracker.resetStatistics();

        assertThat(tracker.getTotalFramesPushed()).isEqualTo(0);
        assertThat(tracker.getTotalFramesPopped()).isEqualTo(0);
        assertThat(tracker.getTotalFramesPromoted()).isEqualTo(0);
    }

    @Test
    void testGetStatisticsSnapshot() {
        tracker.pushFrame(createFrame());
        tracker.pushFrame(createFrame());
        tracker.popFrame();

        PromotionTracker.TrackerStatistics stats = tracker.getStatistics();

        assertThat(stats.currentSize).isEqualTo(1);
        assertThat(stats.totalPushed).isEqualTo(2);
        assertThat(stats.totalPopped).isEqualTo(1);
        assertThat(stats.totalPromoted).isEqualTo(0);
        assertThat(stats.getPromotionRate()).isCloseTo(0.0, within(0.01));
    }

    @Test
    void testValidateConsistencyEmpty() {
        tracker.validateConsistency(); // Should not throw
    }

    @Test
    void testValidateConsistencySingleFrame() {
        tracker.pushFrame(createFrame());
        tracker.validateConsistency(); // Should not throw
    }

    @Test
    void testValidateConsistencyMultipleFrames() {
        tracker.pushFrame(createFrame());
        tracker.pushFrame(createFrame());
        tracker.pushFrame(createFrame());
        tracker.validateConsistency(); // Should not throw
    }

    @Test
    void testComplexScenario() {
        // Simulate realistic usage pattern
        PromotionPoint[] frames = new PromotionPoint[10];
        for (int i = 0; i < 10; i++) {
            frames[i] = createFrame();
            tracker.pushFrame(frames[i]);
        }

        assertThat(tracker.size()).isEqualTo(10);

        // Pop some frames (tasks completing quickly)
        tracker.popFrame();
        tracker.popFrame();
        assertThat(tracker.size()).isEqualTo(8);

        // Promote some frames (tasks taking longer)
        tracker.promoteOldest();
        tracker.promoteOldest();
        assertThat(tracker.size()).isEqualTo(6);

        // Push more frames
        tracker.pushFrame(createFrame());
        tracker.pushFrame(createFrame());
        assertThat(tracker.size()).isEqualTo(8);

        // Mix of operations
        tracker.popFrame();
        tracker.promoteOldest();
        tracker.popFrame();
        assertThat(tracker.size()).isEqualTo(5);

        // Verify statistics
        assertThat(tracker.getTotalFramesPushed()).isEqualTo(12);
        assertThat(tracker.getTotalFramesPopped()).isEqualTo(4);
        assertThat(tracker.getTotalFramesPromoted()).isEqualTo(3);

        // Validate consistency
        tracker.validateConsistency();
    }

    @Test
    void testToString() {
        tracker.pushFrame(createFrame());
        tracker.pushFrame(createFrame());
        tracker.popFrame();

        String str = tracker.toString();

        assertThat(str).contains("size=1");
        assertThat(str).contains("pushed=2");
        assertThat(str).contains("popped=1");
    }
}
