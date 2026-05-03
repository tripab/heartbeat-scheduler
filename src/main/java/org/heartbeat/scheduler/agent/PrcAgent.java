package org.heartbeat.scheduler.agent;

import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point for the PRC (Promotion-Ready Code) bytecode rewriter.
 *
 * Usage:
 *   java -javaagent:heartbeat-scheduler-*-agent.jar[=verbose] ...
 *
 * Optional agent arguments (comma-separated):
 *   verbose   — log each class transformation to stderr
 */
public class PrcAgent {

    public static void premain(String args, Instrumentation inst) {
        AgentConfig config = AgentConfig.parse(args);
        inst.addTransformer(new PrcClassTransformer(config));
    }
}
