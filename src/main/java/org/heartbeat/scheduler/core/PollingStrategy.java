package org.heartbeat.scheduler.core;

/**
 * Strategy for when to check the heartbeat timer.
 * Different strategies for different use cases.
 * <p>
 * The polling strategy determines how often we check if it's time
 * to promote a frame. More frequent polling means lower latency
 * but higher overhead.
 */
public interface PollingStrategy {
    /**
     * Should we poll the timer now?
     *
     * @return true if it's time to check the timer
     */
    boolean shouldPoll();

    /**
     * Record that we polled.
     * Resets internal counters/timers.
     */
    void recordPoll();

    /**
     * Get the name of this polling strategy.
     */
    String getName();

    /**
     * Reset the strategy to initial state.
     */
    void reset();
}
