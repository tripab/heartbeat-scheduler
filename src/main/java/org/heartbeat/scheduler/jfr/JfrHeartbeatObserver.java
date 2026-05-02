package org.heartbeat.scheduler.jfr;

import org.heartbeat.scheduler.core.HeartbeatObserver;

/**
 * JFR-backed implementation of {@link HeartbeatObserver}.
 *
 * <p>Emits {@link HeartbeatEvents.PromotionEvent}, {@link HeartbeatEvents.PollCheckEvent},
 * and {@link HeartbeatEvents.JoinBlockedEvent} when a JFR recording is active.
 * All three event types are gated with {@code isEnabled()} so no work is done
 * when no recording is active.
 *
 * <p>Wire into the runtime via:
 * <pre>{@code
 * HeartbeatConfig config = HeartbeatConfig.newBuilder()
 *     .observer(JfrHeartbeatObserver.INSTANCE)
 *     .build();
 * }</pre>
 */
public final class JfrHeartbeatObserver implements HeartbeatObserver {

    public static final JfrHeartbeatObserver INSTANCE = new JfrHeartbeatObserver();

    @Override
    public void onPollCheck(long totalPolls, long totalPromotions) {
        HeartbeatEvents.PollCheckEvent jfr = new HeartbeatEvents.PollCheckEvent();
        if (jfr.isEnabled()) {
            jfr.totalPolls = totalPolls;
            jfr.totalPromotions = totalPromotions;
            jfr.commit();
        }
    }

    @Override
    public void onPromotion(String carrier, long frameAgeNanos, int framesInFlight) {
        HeartbeatEvents.PromotionEvent jfr = new HeartbeatEvents.PromotionEvent();
        if (jfr.isEnabled()) {
            jfr.carrier = carrier;
            jfr.frameAgeNanos = frameAgeNanos;
            jfr.framesInFlight = framesInFlight;
            jfr.commit();
        }
    }

    @Override
    public Runnable startJoinBlocked(String carrier, long taskAgeNanos) {
        HeartbeatEvents.JoinBlockedEvent jfr = new HeartbeatEvents.JoinBlockedEvent();
        boolean enabled = jfr.isEnabled();
        if (enabled) {
            jfr.begin();
            jfr.carrier = carrier;
            jfr.taskAgeNanos = taskAgeNanos;
        }
        return enabled ? jfr::commit : () -> {};
    }
}
