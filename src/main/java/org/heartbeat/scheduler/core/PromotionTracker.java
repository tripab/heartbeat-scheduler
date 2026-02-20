package org.heartbeat.scheduler.core;

import org.heartbeat.scheduler.sync.PromotionPoint;

/**
 * Manages the doubly-linked list of promotable frames.
 * <p>
 * This is the core data structure for tracking which frames can be promoted.
 * The paper requires O(1) access to the oldest promotable frame (outermost
 * parallel call), which this class provides via a doubly-linked list.
 * <p>
 * Key invariants:
 * - Head points to the newest (most recently added) promotable frame
 * - Tail points to the oldest (ready to promote) promotable frame
 * - All operations are O(1)
 * <p>
 * Thread-safety: This class is NOT thread-safe. It's designed to be used
 * from a single carrier thread's context. Different carrier threads have
 * their own PromotionTracker instances.
 */
public class PromotionTracker {

    // Head of the list (newest frame)
    private PromotionPoint head;

    // Tail of the list (oldest frame - the one to promote)
    private PromotionPoint tail;

    // Number of frames in the list
    private int size;

    // Statistics
    private long totalFramesPushed;
    private long totalFramesPopped;
    private long totalFramesPromoted;

    /**
     * Create a new empty promotion tracker.
     */
    public PromotionTracker() {
        this.head = null;
        this.tail = null;
        this.size = 0;
        this.totalFramesPushed = 0;
        this.totalFramesPopped = 0;
        this.totalFramesPromoted = 0;
    }

    /**
     * Push a new promotable frame onto the stack.
     * The frame becomes the new head (newest frame).
     * <p>
     * This is called when entering a parallel construct (fork).
     *
     * @param frame The frame to push
     * @throws IllegalArgumentException if frame is null
     */
    public void pushFrame(PromotionPoint frame) {
        if (frame == null) {
            throw new IllegalArgumentException("Frame cannot be null");
        }

        if (head == null) {
            // Empty list - frame becomes both head and tail
            head = frame;
            tail = frame;
            frame.setPrev(null);
            frame.setNext(null);
        } else {
            // Insert at head
            frame.setNext(null);  // New head has no next
            frame.setPrev(head);   // Point back to old head
            head.setNext(frame);   // Old head points forward to new head
            head = frame;          // Update head
        }

        size++;
        totalFramesPushed++;
    }

    /**
     * Pop the head frame (newest frame) from the stack.
     * <p>
     * This is called when a parallel construct completes before its
     * frame was promoted (the common case for fine-grained tasks).
     *
     * @return The popped frame, or null if stack is empty
     */
    public PromotionPoint popFrame() {
        if (head == null) {
            return null;
        }

        PromotionPoint popped = head;
        head = head.getPrev();  // Move head back

        if (head == null) {
            // List is now empty
            tail = null;
        } else {
            head.setNext(null);  // New head has no next
        }

        // Detach the popped frame
        popped.setPrev(null);
        popped.setNext(null);

        size--;
        totalFramesPopped++;

        return popped;
    }

    /**
     * Get the oldest promotable frame (tail) without removing it.
     * <p>
     * This is the frame that should be promoted according to the paper's
     * strategy of always promoting the outermost parallel call.
     *
     * @return The oldest frame, or null if no frames exist
     */
    public PromotionPoint getOldestPromotable() {
        return tail;
    }

    /**
     * Get the newest frame (head) without removing it.
     *
     * @return The newest frame, or null if no frames exist
     */
    public PromotionPoint getNewestFrame() {
        return head;
    }

    /**
     * Promote the oldest frame (tail) and remove it from the list.
     * <p>
     * This is called when the heartbeat timer expires and we need to
     * convert the oldest frame into a parallel thread.
     *
     * @return The promoted frame, or null if no frames exist
     */
    public PromotionPoint promoteOldest() {
        if (tail == null) {
            return null;
        }

        PromotionPoint promoted = tail;
        tail = tail.getNext();  // Move tail forward

        if (tail == null) {
            // List is now empty
            head = null;
        } else {
            tail.setPrev(null);  // New tail has no prev
        }

        // Detach the promoted frame
        promoted.setPrev(null);
        promoted.setNext(null);

        // Mark as promoted
        promoted.markPromoted();

        size--;
        totalFramesPromoted++;

        return promoted;
    }

    /**
     * Remove a specific frame from anywhere in the list.
     * <p>
     * This is used when a frame completes or is otherwise invalidated
     * before promotion.
     *
     * @param frame The frame to remove
     * @return true if the frame was found and removed
     */
    public boolean removeFrame(PromotionPoint frame) {
        if (frame == null || size == 0) {
            return false;
        }

        // Check if it's the head
        if (frame == head) {
            popFrame();
            return true;
        }

        // Check if it's the tail
        if (frame == tail) {
            promoteOldest();  // This removes tail
            return true;
        }

        // It's in the middle - use the frame's own removal logic
        frame.removeFromList();
        size--;

        return true;
    }

    /**
     * Check if the tracker has any promotable frames.
     *
     * @return true if there are frames to promote
     */
    public boolean hasPromotableFrames() {
        return size > 0;
    }

    /**
     * Get the number of frames currently tracked.
     *
     * @return The number of frames
     */
    public int size() {
        return size;
    }

    /**
     * Check if the tracker is empty.
     *
     * @return true if no frames are tracked
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Get the age of the oldest frame in nanoseconds.
     *
     * @return Age in nanoseconds, or -1 if no frames exist
     */
    public long getOldestFrameAgeNanos() {
        return tail != null ? tail.getAgeNanos() : -1;
    }

    /**
     * Get the age of the oldest frame in microseconds.
     *
     * @return Age in microseconds, or -1 if no frames exist
     */
    public long getOldestFrameAgeMicros() {
        return tail != null ? tail.getAgeMicros() : -1;
    }

    /**
     * Clear all frames from the tracker.
     * Used for cleanup or reset.
     */
    public void clear() {
        head = null;
        tail = null;
        size = 0;
    }

    /**
     * Get total number of frames pushed since creation.
     */
    public long getTotalFramesPushed() {
        return totalFramesPushed;
    }

    /**
     * Get total number of frames popped since creation.
     */
    public long getTotalFramesPopped() {
        return totalFramesPopped;
    }

    /**
     * Get total number of frames promoted since creation.
     */
    public long getTotalFramesPromoted() {
        return totalFramesPromoted;
    }

    /**
     * Get the promotion rate (promoted / (promoted + popped)).
     * <p>
     * This indicates what fraction of frames were actually promoted
     * vs completing before promotion.
     *
     * @return Promotion rate between 0.0 and 1.0
     */
    public double getPromotionRate() {
        long total = totalFramesPromoted + totalFramesPopped;
        return total > 0 ? (double) totalFramesPromoted / total : 0.0;
    }

    /**
     * Reset statistics.
     */
    public void resetStatistics() {
        totalFramesPushed = 0;
        totalFramesPopped = 0;
        totalFramesPromoted = 0;
    }

    /**
     * Get a snapshot of tracker statistics.
     */
    public TrackerStatistics getStatistics() {
        return new TrackerStatistics(
                size,
                totalFramesPushed,
                totalFramesPopped,
                totalFramesPromoted,
                getOldestFrameAgeNanos()
        );
    }

    /**
     * Validate internal consistency (for debugging).
     *
     * @throws IllegalStateException if inconsistent
     */
    void validateConsistency() {
        if (size == 0) {
            if (head != null || tail != null) {
                throw new IllegalStateException(
                        "Size is 0 but head/tail are not null"
                );
            }
            return;
        }

        if (size == 1) {
            if (head != tail) {
                throw new IllegalStateException(
                        "Size is 1 but head != tail"
                );
            }
            if (head.getPrev() != null || head.getNext() != null) {
                throw new IllegalStateException(
                        "Single frame should have null prev/next"
                );
            }
            return;
        }

        // Size > 1
        if (head == null || tail == null) {
            throw new IllegalStateException(
                    "Size > 1 but head or tail is null"
            );
        }

        if (head == tail) {
            throw new IllegalStateException(
                    "Size > 1 but head == tail"
            );
        }

        // Count nodes
        int count = 0;
        PromotionPoint current = tail;
        while (current != null) {
            count++;
            if (current == head) {
                break;
            }
            current = current.getNext();
        }

        if (count != size) {
            throw new IllegalStateException(
                    String.format("Size mismatch: expected %d, counted %d", size, count)
            );
        }
    }

    @Override
    public String toString() {
        return String.format(
                "PromotionTracker[size=%d, pushed=%d, popped=%d, promoted=%d, rate=%.2f%%]",
                size,
                totalFramesPushed,
                totalFramesPopped,
                totalFramesPromoted,
                getPromotionRate() * 100.0
        );
    }

    /**
     * Immutable snapshot of tracker statistics.
     */
    public static class TrackerStatistics {
        public final int currentSize;
        public final long totalPushed;
        public final long totalPopped;
        public final long totalPromoted;
        public final long oldestFrameAgeNanos;

        private TrackerStatistics(
                int currentSize,
                long totalPushed,
                long totalPopped,
                long totalPromoted,
                long oldestFrameAgeNanos
        ) {
            this.currentSize = currentSize;
            this.totalPushed = totalPushed;
            this.totalPopped = totalPopped;
            this.totalPromoted = totalPromoted;
            this.oldestFrameAgeNanos = oldestFrameAgeNanos;
        }

        public double getPromotionRate() {
            long total = totalPromoted + totalPopped;
            return total > 0 ? (double) totalPromoted / total : 0.0;
        }

        @Override
        public String toString() {
            return String.format(
                    "TrackerStatistics[size=%d, pushed=%d, popped=%d, promoted=%d, rate=%.2f%%, oldestAge=%.2fμs]",
                    currentSize,
                    totalPushed,
                    totalPopped,
                    totalPromoted,
                    getPromotionRate() * 100.0,
                    oldestFrameAgeNanos / 1000.0
            );
        }
    }
}
