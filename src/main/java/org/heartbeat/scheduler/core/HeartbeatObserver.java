package org.heartbeat.scheduler.core;

/**
 * Observability hook for key heartbeat runtime events.
 *
 * <p>Decouples the core runtime from any specific observability backend (JFR,
 * OpenTelemetry, logging, etc.). Wire an implementation through
 * {@link HeartbeatConfig.Builder#observer(HeartbeatObserver)}.
 *
 * <p>The default is {@link #NOOP} — no events emitted.
 * For JFR-backed events use {@code JfrHeartbeatObserver} from the
 * {@code org.heartbeat.scheduler.jfr} package.
 */
public interface HeartbeatObserver {

    /**
     * Called when the heartbeat timer fires and decides a promotion is warranted.
     *
     * @param totalPolls      running poll count on this context
     * @param totalPromotions running promotion count on this context
     */
    void onPollCheck(long totalPolls, long totalPromotions);

    /**
     * Called when a frame is promoted to a virtual thread.
     *
     * @param carrier        name of the promoting carrier thread
     * @param frameAgeNanos  age of the promoted frame since fork() in nanoseconds
     * @param framesInFlight number of promotable frames on the stack at promotion time
     */
    void onPromotion(String carrier, long frameAgeNanos, int framesInFlight);

    /**
     * Called at the start of a blocking join() on a promoted task.
     * The returned {@link Runnable} must be invoked (in a {@code finally} block)
     * when the join completes to record the duration.
     *
     * @param carrier       name of the blocking carrier thread
     * @param taskAgeNanos  age of the awaited task since fork() in nanoseconds
     * @return a completion action that closes the observation (never null)
     */
    Runnable startJoinBlocked(String carrier, long taskAgeNanos);

    /** No-op observer — zero allocation, zero overhead. */
    HeartbeatObserver NOOP = new HeartbeatObserver() {
        @Override public void onPollCheck(long p, long pr) {}
        @Override public void onPromotion(String c, long f, int fi) {}
        @Override public Runnable startJoinBlocked(String c, long a) { return () -> {}; }
    };
}
