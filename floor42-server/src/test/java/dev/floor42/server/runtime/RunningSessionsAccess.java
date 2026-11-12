package dev.floor42.server.runtime;

import dev.floor42.domain.AgentId;

/** For tests outside the runtime: a process recorded as an agent's running session, as the runtime records one. */
public final class RunningSessionsAccess {

    private RunningSessionsAccess() {}

    public static void started(RunningSessions sessions, AgentId agent, Process process) {
        sessions.started(agent, process);
    }

    public static String ended(RunningSessions sessions, AgentId agent, Process process) {
        return sessions.ended(agent, process);
    }

    /** As the runtime records a session of a mission. */
    public static void started(
            RunningSessions sessions, AgentId agent, dev.floor42.domain.MissionId mission, Process process) {
        sessions.started(agent, mission, process);
    }
}
