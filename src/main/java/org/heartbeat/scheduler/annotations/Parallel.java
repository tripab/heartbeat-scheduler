package org.heartbeat.scheduler.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a parallel task eligible for PRC (Promotion-Ready Code) instrumentation.
 * The bytecode rewriter inserts HeartbeatContext.checkHeartbeatStatic() calls at method entry
 * and loop backedges of methods annotated with @Parallel.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface Parallel {
}
