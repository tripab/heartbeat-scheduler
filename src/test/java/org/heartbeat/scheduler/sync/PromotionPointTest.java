package org.heartbeat.scheduler.sync;

import org.heartbeat.scheduler.task.HeartbeatTask;
import org.heartbeat.scheduler.vthread.ContinuationScope;
import org.heartbeat.scheduler.vthread.HeartbeatContinuation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromotionPointTest {

    private ContinuationScope scope;
    private HeartbeatContinuation continuation;
    private HeartbeatTask<Integer> dummyTask;

    @BeforeEach
    void setUp() {
        scope = new ContinuationScope("test");
        continuation = new HeartbeatContinuation(scope, () -> {
        });
        dummyTask = new HeartbeatTask<>() {
            @Override
            protected Integer compute() {
                return 0;
            }
        };
    }

    @Test
    void testCreation() {
        PromotionPoint point = new PromotionPoint(continuation, scope, dummyTask);

        assertThat(point.getContinuation()).isSameAs(continuation);
        assertThat(point.getScope()).isSameAs(scope);
        assertThat(point.getTask()).isSameAs(dummyTask);
        assertThat(point.isPromoted()).isFalse();
        assertThat(point.getCreationTime()).isGreaterThan(0);
    }

    @Test
    void testNullContinuation() {
        assertThatThrownBy(() -> new PromotionPoint(null, scope, dummyTask))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testNullScope() {
        assertThatThrownBy(() -> new PromotionPoint(continuation, null, dummyTask))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testNullTask() {
        assertThatThrownBy(() -> new PromotionPoint(continuation, scope, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testMarkPromoted() {
        PromotionPoint point = new PromotionPoint(continuation, scope, dummyTask);

        assertThat(point.isPromoted()).isFalse();

        point.markPromoted();
        assertThat(point.isPromoted()).isTrue();

        // Cannot promote twice
        assertThatThrownBy(point::markPromoted)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Already promoted");
    }

    @Test
    void testInitialListState() {
        PromotionPoint point = new PromotionPoint(continuation, scope, dummyTask);

        assertThat(point.isHead()).isTrue();
        assertThat(point.isTail()).isTrue();
        assertThat(point.getPrev()).isNull();
        assertThat(point.getNext()).isNull();
    }

    @Test
    void testDoublyLinkedList() {
        PromotionPoint p1 = new PromotionPoint(continuation, scope, dummyTask);
        PromotionPoint p2 = new PromotionPoint(
                new HeartbeatContinuation(scope, () -> {
                }), scope, dummyTask
        );
        PromotionPoint p3 = new PromotionPoint(
                new HeartbeatContinuation(scope, () -> {
                }), scope, dummyTask
        );

        // Build list: p1 <-> p2 <-> p3
        p2.insertAfter(p1);
        p3.insertAfter(p2);

        // Check forward links
        assertThat(p1.getNext()).isSameAs(p2);
        assertThat(p2.getNext()).isSameAs(p3);
        assertThat(p3.getNext()).isNull();

        // Check backward links
        assertThat(p1.getPrev()).isNull();
        assertThat(p2.getPrev()).isSameAs(p1);
        assertThat(p3.getPrev()).isSameAs(p2);

        // Check head/tail
        assertThat(p1.isHead()).isFalse();
        assertThat(p1.isTail()).isTrue();
        assertThat(p2.isHead()).isFalse();
        assertThat(p2.isTail()).isFalse();
        assertThat(p3.isHead()).isTrue();
        assertThat(p3.isTail()).isFalse();
    }

    @Test
    void testInsertAfter() {
        PromotionPoint p1 = new PromotionPoint(continuation, scope, dummyTask);
        PromotionPoint p2 = new PromotionPoint(
                new HeartbeatContinuation(scope, () -> {
                }), scope, dummyTask
        );

        p2.insertAfter(p1);

        assertThat(p1.getNext()).isSameAs(p2);
        assertThat(p2.getPrev()).isSameAs(p1);
    }

    @Test
    void testInsertBefore() {
        PromotionPoint p1 = new PromotionPoint(continuation, scope, dummyTask);
        PromotionPoint p2 = new PromotionPoint(
                new HeartbeatContinuation(scope, () -> {
                }), scope, dummyTask
        );

        p2.insertBefore(p1);

        assertThat(p1.getPrev()).isSameAs(p2);
        assertThat(p2.getNext()).isSameAs(p1);
    }

    @Test
    void testRemoveFromList() {
        PromotionPoint p1 = new PromotionPoint(continuation, scope, dummyTask);
        PromotionPoint p2 = new PromotionPoint(
                new HeartbeatContinuation(scope, () -> {
                }), scope, dummyTask
        );
        PromotionPoint p3 = new PromotionPoint(
                new HeartbeatContinuation(scope, () -> {
                }), scope, dummyTask
        );

        // Build list: p1 <-> p2 <-> p3
        p2.insertAfter(p1);
        p3.insertAfter(p2);

        // Remove p2
        p2.removeFromList();

        // Should have: p1 <-> p3
        assertThat(p1.getNext()).isSameAs(p3);
        assertThat(p3.getPrev()).isSameAs(p1);
        assertThat(p2.getPrev()).isNull();
        assertThat(p2.getNext()).isNull();
    }

    @Test
    void testRemoveHead() {
        PromotionPoint p1 = new PromotionPoint(continuation, scope, dummyTask);
        PromotionPoint p2 = new PromotionPoint(
                new HeartbeatContinuation(scope, () -> {
                }), scope, dummyTask
        );

        p2.insertAfter(p1);
        p1.removeFromList();

        // p2 should be new head
        assertThat(p2.isHead()).isTrue();
        assertThat(p2.getPrev()).isNull();
    }

    @Test
    void testRemoveTail() {
        PromotionPoint p1 = new PromotionPoint(continuation, scope, dummyTask);
        PromotionPoint p2 = new PromotionPoint(
                new HeartbeatContinuation(scope, () -> {
                }), scope, dummyTask
        );

        p2.insertAfter(p1);
        p2.removeFromList();

        // p1 should be new tail
        assertThat(p1.isTail()).isTrue();
        assertThat(p1.getNext()).isNull();
    }

    @Test
    void testComplexListOperations() {
        // Build a list with 5 nodes
        PromotionPoint[] points = new PromotionPoint[5];
        for (int i = 0; i < 5; i++) {
            HeartbeatContinuation cont = new HeartbeatContinuation(scope, () -> {
            });
            points[i] = new PromotionPoint(cont, scope, dummyTask);
            if (i > 0) {
                points[i].insertAfter(points[i - 1]);
            }
        }

        // Verify structure
        for (int i = 0; i < 5; i++) {
            if (i == 0) {
                assertThat(points[i].isTail()).isTrue();
                assertThat(points[i].getPrev()).isNull();
            } else {
                assertThat(points[i].getPrev()).isSameAs(points[i - 1]);
            }

            if (i == 4) {
                assertThat(points[i].isHead()).isTrue();
                assertThat(points[i].getNext()).isNull();
            } else {
                assertThat(points[i].getNext()).isSameAs(points[i + 1]);
            }
        }

        // Remove middle node
        points[2].removeFromList();

        // Verify updated structure
        assertThat(points[1].getNext()).isSameAs(points[3]);
        assertThat(points[3].getPrev()).isSameAs(points[1]);
    }

}
