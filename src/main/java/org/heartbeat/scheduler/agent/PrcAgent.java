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
        inst.addTransformer(new PrcClassTransformer(parseVerbose(args)));
    }

    private static boolean parseVerbose(String args) {
        if (args == null || args.isBlank()) return false;
        for (String token : args.split(",")) {
            if ("verbose".equalsIgnoreCase(token.strip())) return true;
        }
        return false;
    }
}
