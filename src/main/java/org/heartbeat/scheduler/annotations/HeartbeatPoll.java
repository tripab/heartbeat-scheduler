package org.heartbeat.scheduler.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional annotation to override polling granularity for a @Parallel method.
 * When present, the rewriter inserts a poll every {@code every} backedge traversals
 * rather than on every backedge.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface HeartbeatPoll {
    /** Poll every N backedge traversals. Default is 1 (poll on every backedge). */
    int every() default 1;
}
