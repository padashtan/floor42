package dev.floor42.server.runtime;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Claude Code process of each agent's running session, in memory: one session per agent at a time. Floor42 stops a
 * session here only for a reason it names (the director called the agent to a meeting, ADR-0047, or cancelled the
 * mission); the reason goes into that session's notes.
 */
@ApplicationScoped
public class RunningSessions {

    private final Map<AgentId, Process> processes = new ConcurrentHashMap<>();
    private final Map<Process, String> stopped = new ConcurrentHashMap<>();
    /** Agents whose next process is stopped as soon as it registers, for this reason (a meeting closing, ADR-0049). */
    private final Map<AgentId, String> barred = new ConcurrentHashMap<>();
    /** The mission each running process works for. */
    private final Map<Process, MissionId> missions = new ConcurrentHashMap<>();
    /** Missions the director cancelled: a process of theirs is stopped as soon as it registers, for this reason. */
    private final Map<MissionId, Mark> closed = new ConcurrentHashMap<>();

    /**
     * How long a cancellation's mark stays: longer than a launch takes to register its process. Past it the domain
     * refuses any session start on the closed mission anyway, so the mark is dropped at the next look.
     */
    static final java.time.Duration MARK_KEPT = java.time.Duration.ofMinutes(2);

    private record Mark(String reason, java.time.Instant until) {}

    private final java.time.Clock clock;

    public RunningSessions() {
        this(java.time.Clock.systemUTC());
    }

    /** With the clock the cancellation marks expire by (a test moves it). */
    RunningSessions(java.time.Clock clock) {
        this.clock = clock;
    }

    void started(AgentId agent, Process process) {
        started(agent, null, process);
    }

    void started(AgentId agent, MissionId mission, Process process) {
        processes.put(agent, process);
        if (mission != null) missions.put(process, mission);
        String reason = barred.get(agent);
        if (reason == null && mission != null) reason = mark(mission);
        if (reason == null) return;
        // Launched after the close looked: stopped before its brief is written, so no model call is made.
        stopped.put(process, reason);
        ClaudeCodeRuntime.stopTree(process);
    }

    /** From now until {@link #unbar}, any process of this agent that registers is stopped at once, for this reason. */
    public void bar(AgentId agent, String reason) {
        barred.put(agent, reason);
    }

    public void unbar(AgentId agent) {
        barred.remove(agent);
    }

    /** The session ended: why Floor42 stopped it, or null when it did not. */
    String ended(AgentId agent, Process process) {
        processes.remove(agent, process);
        missions.remove(process);
        return stopped.remove(process);
    }

    /**
     * The mission was cancelled: every process working for it is stopped, children too, and any that registers later
     * is stopped before its brief is written. The mark is written before the look, as {@link #started} registers
     * before it reads the mark: one of the two always sees the other. Returns how many were running.
     */
    public int closeMission(MissionId mission, String reason) {
        java.time.Instant now = clock.instant();
        closed.values().removeIf(x -> x.until().isBefore(now));
        closed.put(mission, new Mark(reason, now.plus(MARK_KEPT)));
        int stoppedNow = 0;
        for (Map.Entry<Process, MissionId> e : missions.entrySet()) {
            Process p = e.getKey();
            if (!mission.equals(e.getValue()) || !p.isAlive()) continue;
            stopped.put(p, reason);
            ClaudeCodeRuntime.stopTree(p);
            stoppedNow++;
        }
        return stoppedNow;
    }

    /** The reason of a cancellation still marked, or null; a mark past its time is dropped here. */
    private String mark(MissionId mission) {
        Mark m = closed.get(mission);
        if (m == null) return null;
        if (m.until().isAfter(clock.instant())) return m.reason();
        closed.remove(mission, m);
        return null;
    }

    /** True while a live session of this agent runs. */
    public boolean running(AgentId agent) {
        Process p = processes.get(agent);
        return p != null && p.isAlive();
    }

    /** As {@link #stop(AgentId, String)}, but only when the running process works for this mission. */
    public boolean stop(AgentId agent, MissionId mission, String reason) {
        Process p = processes.get(agent);
        if (p == null || !mission.equals(missions.get(p))) return false;
        return stop(agent, reason);
    }

    /** Stops the agent's session and everything it started, for this reason. False when none was running. */
    public boolean stop(AgentId agent, String reason) {
        Process p = processes.get(agent);
        if (p == null || !p.isAlive()) return false;
        stopped.put(p, reason);
        ClaudeCodeRuntime.stopTree(p);
        return true;
    }
}
