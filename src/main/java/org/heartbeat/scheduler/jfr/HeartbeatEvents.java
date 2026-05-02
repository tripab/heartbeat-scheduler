package org.heartbeat.scheduler.jfr;

import jdk.jfr.*;

/**
 * JFR event classes for the heartbeat scheduler.
 *
 * <p>Events are emitted at three key runtime sites:
 * <ul>
 *   <li>{@link PromotionEvent} — when a frame is promoted to a virtual thread</li>
 *   <li>{@link PollCheckEvent} — when the heartbeat timer fires and a promotion is needed</li>
 *   <li>{@link JoinBlockedEvent} — when join() blocks waiting for a promoted task to finish</li>
 * </ul>
 *
 * <p>Each call site guards allocation with {@code XxxEvent.isEnabled()} so that
 * no objects are allocated when no JFR recording is active.
 *
 * <p>View in Java Mission Control or parse with:
 * <pre>{@code jfr print --events org.heartbeat.scheduler.Promotion recording.jfr}</pre>
 */
public final class HeartbeatEvents {

    private HeartbeatEvents() {}

    /**
     * Emitted when the heartbeat fires and a frame is promoted to a virtual thread.
     */
    @Name("org.heartbeat.scheduler.Promotion")
    @Category({"Heartbeat Scheduler"})
    @Label("Promotion")
    @StackTrace(false)
    public static class PromotionEvent extends Event {
        /** Name of the carrier thread that performed the promotion. */
        @Label("Carrier thread")
        public String carrier;

        /** Age of the promoted frame in nanoseconds (time since fork()). */
        @Label("Frame age (ns)")
        public long frameAgeNanos;

        /** Number of promotable frames on the promotion stack at promotion time. */
        @Label("Frames in flight")
        public int framesInFlight;
    }

    /**
     * Emitted when the heartbeat timer fires (shouldPromote returns true).
     * This captures the moment the timer decides a promotion is warranted,
     * regardless of whether a promotable frame exists.
     */
    @Name("org.heartbeat.scheduler.PollCheck")
    @Category({"Heartbeat Scheduler"})
    @Label("Poll Check")
    @StackTrace(false)
    public static class PollCheckEvent extends Event {
        /** Running total of polls on this context at the time the timer fired. */
        @Label("Total polls")
        public long totalPolls;

        /** Running total of promotions on this context so far. */
        @Label("Total promotions")
        public long totalPromotions;
    }

    /**
     * Duration event emitted when join() blocks waiting for a promoted task.
     * The event duration is the actual wall-clock blocking time.
     */
    @Name("org.heartbeat.scheduler.JoinBlocked")
    @Category({"Heartbeat Scheduler"})
    @Label("Join Blocked")
    @StackTrace(false)
    public static class JoinBlockedEvent extends Event {
        /** Name of the carrier thread that is blocking. */
        @Label("Carrier thread")
        public String carrier;

        /** Age of the task being waited on in nanoseconds. */
        @Label("Task age (ns)")
        public long taskAgeNanos;
    }
}
