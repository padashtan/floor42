package dev.floor42.server.app;

import dev.floor42.domain.AgentId;

/** The live missions' package-private test seams, for the API tests in another package. */
public final class LiveSeams {

    private LiveSeams() {}

    /** Holds the agent the way a task outside the dispatcher does; fails if it is busy elsewhere. */
    public static AgentReservations.Reservation reserve(LiveMissions live, AgentId agent) {
        return live.reserve(agent).orElseThrow(() -> new AssertionError(agent + " is busy"));
    }

    /** Tries to hold the agent the way a task outside the dispatcher does: empty while something else holds it. */
    public static java.util.Optional<AgentReservations.Reservation> tryReserve(LiveMissions live, AgentId agent) {
        return live.reserve(agent);
    }

    /** Runs {@code pause} in the turn's thread between the decision to start a turn and its session's launch. */
    public static void beforeTurnLaunch(MeetingVoices voices, Runnable pause) {
        voices.beforeLaunch(pause);
    }

    /** The start-up's close-out of the rounds a stopped daemon left unfinished (ADR-0049). */
    public static void closeOutRounds(MeetingVoices voices) {
        voices.closeOut();
    }

    /** The start-up's handling of what the last daemon left, in its order, the recovery run in the caller's thread. */
    public static void resumeAfterRestart(LiveStartup startup) {
        startup.resume(Runnable::run);
    }

    /** The sandbox's measured tools, as the briefs write them. */
    public static String measuredTools(dev.floor42.domain.Company c) {
        return Briefs.measuredTools(c);
    }

    /** The start-up's measure of the "I install it" answers whose own measure the journal does not hold. */
    public static void measurePendingInstalls(DirectorAnswers answers) {
        answers.measurePendingInstalls(null);
    }

    /** Runs {@code hook} inside the re-measure's command, before it records; a throw there fails that command. */
    public static void beforeMeasureRecorded(MissingTools tools, Runnable hook) {
        tools.beforeRecord(hook);
    }

    /** Whether the mission waits for the director (an open hold, or an install answer not measured yet). */
    public static boolean waiting(dev.floor42.domain.Company c, dev.floor42.domain.MissionId m) {
        return MissionHolds.waiting(c, m);
    }
}
