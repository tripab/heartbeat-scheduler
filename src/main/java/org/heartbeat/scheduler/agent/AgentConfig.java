package org.heartbeat.scheduler.agent;

/**
 * Parsed configuration from the -javaagent argument string.
 */
public class AgentConfig {

    public final boolean verbose;

    private AgentConfig(boolean verbose) {
        this.verbose = verbose;
    }

    public static AgentConfig parse(String args) {
        if (args == null || args.isBlank()) return new AgentConfig(false);
        boolean verbose = false;
        for (String token : args.split(",")) {
            if ("verbose".equalsIgnoreCase(token.strip())) verbose = true;
        }
        return new AgentConfig(verbose);
    }
}
