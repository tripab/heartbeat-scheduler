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

    // premain without Instrumentation — not needed but required by spec when
    // the two-argument form is absent; keep for completeness.
    public static void premain(String args) {
        throw new IllegalStateException("Instrumentation argument required");
    }
}
