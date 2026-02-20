package org.heartbeat.scheduler.sync;

import org.heartbeat.scheduler.vthread.ContinuationScope;
import org.heartbeat.scheduler.vthread.HeartbeatContinuation;

/**
 * Represents a point where a continuation can be promoted.
 * Corresponds to PAIRL frames in the paper's semantics.
 * <p>
 * These form a doubly-linked list to maintain O(1) access to
 * the oldest promotable frame, as required by the paper's
 * promotion strategy.
 */
public class PromotionPoint {
    private final HeartbeatContinuation continuation;
    private final long creationTime;
    private final ContinuationScope scope;
    private volatile boolean promoted;

    // Doubly-linked list for tracking promotion order
    // prev points to newer frames, next points to older frames
    private PromotionPoint prev;
    private PromotionPoint next;

    /**
     * Create a new promotion point.
     *
     * @param continuation The continuation that can be promoted
     * @param scope        The scope of this promotion point
     */
    public PromotionPoint(
            HeartbeatContinuation continuation,
            ContinuationScope scope
    ) {
        if (continuation == null) {
            throw new IllegalArgumentException("Continuation cannot be null");
        }
        if (scope == null) {
            throw new IllegalArgumentException("Scope cannot be null");
        }

        this.continuation = continuation;
        this.scope = scope;
        this.creationTime = System.nanoTime();
        this.promoted = false;
    }

    /**
     * Get the continuation at this promotion point.
     */
    public HeartbeatContinuation getContinuation() {
        return continuation;
    }

    /**
     * Get the creation time of this promotion point.
     */
    public long getCreationTime() {
        return creationTime;
    }

    /**
     * Get the age of this promotion point in nanoseconds.
     */
    public long getAgeNanos() {
        return System.nanoTime() - creationTime;
    }

    /**
     * Get the age of this promotion point in microseconds.
     */
    public long getAgeMicros() {
        return getAgeNanos() / 1_000;
    }

    /**
     * Get the continuation scope.
     */
    public ContinuationScope getScope() {
        return scope;
    }

    /**
     * Check if this promotion point has been promoted.
     */
    public boolean isPromoted() {
        return promoted;
    }

    /**
     * Mark this promotion point as promoted.
     * Once promoted, it should not be promoted again.
     */
    public void markPromoted() {
        if (promoted) {
            throw new IllegalStateException("Already promoted");
        }
        this.promoted = true;
    }

    // Doubly-linked list accessors

    /**
     * Get the previous (newer) promotion point in the list.
     */
    public PromotionPoint getPrev() {
        return prev;
    }

    /**
     * Set the previous (newer) promotion point.
     */
    public void setPrev(PromotionPoint prev) {
        this.prev = prev;
    }

    /**
     * Get the next (older) promotion point in the list.
     */
    public PromotionPoint getNext() {
        return next;
    }

    /**
     * Set the next (older) promotion point.
     */
    public void setNext(PromotionPoint next) {
        this.next = next;
    }

    /**
     * Check if this is the head (newest) of the list.
     */
    public boolean isHead() {
        return next == null;
    }

    /**
     * Check if this is the tail (oldest) of the list.
     */
    public boolean isTail() {
        return prev == null;
    }

    /**
     * Remove this promotion point from the doubly-linked list.
     * Updates the prev and next pointers of adjacent nodes.
     */
    public void removeFromList() {
        if (prev != null) {
            prev.next = next;
        }
        if (next != null) {
            next.prev = prev;
        }
        prev = null;
        next = null;
    }

    /**
     * Insert this promotion point after the given node.
     */
    public void insertAfter(PromotionPoint node) {
        if (node == null) {
            throw new IllegalArgumentException("Node cannot be null");
        }

        this.prev = node;
        this.next = node.next;

        if (node.next != null) {
            node.next.prev = this;
        }
        node.next = this;
    }

    /**
     * Insert this promotion point before the given node.
     */
    public void insertBefore(PromotionPoint node) {
        if (node == null) {
            throw new IllegalArgumentException("Node cannot be null");
        }

        this.next = node;
        this.prev = node.prev;

        if (node.prev != null) {
            node.prev.next = this;
        }
        node.prev = this;
    }

    @Override
    public String toString() {
        return String.format(
                "PromotionPoint[scope=%s, age=%.2fμs, promoted=%s, isHead=%s, isTail=%s]",
                scope.name(),
                getAgeMicros() / 1000.0,
                promoted,
                isHead(),
                isTail()
        );
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof PromotionPoint other)) return false;

        // Two promotion points are equal if they have the same continuation
        return continuation.equals(other.continuation);
    }

    @Override
    public int hashCode() {
        return continuation.hashCode();
    }
}
