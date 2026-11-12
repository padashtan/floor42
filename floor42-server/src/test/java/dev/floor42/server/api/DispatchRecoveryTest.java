package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Action;
import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.DenialReason;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.event.SessionEvent.RuntimeLineRecorded;
import dev.floor42.domain.event.StaffingEvent.StaffingAdvised;
import dev.floor42.server.app.MissionRecovery;
import dev.floor42.server.app.StaffProvisioner;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.runtime.ClaudeCodeProbe;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * When a session or a task goes wrong (ADR-0019, debt D11): a session the previous daemon left open is closed and the
 * director asked, a committee that cannot run waits for "Retry" instead of looping, a tool Claude Code refuses itself
 * is recorded, and an unfamiliar stream line the director accepted is recorded masked.
 */
@QuarkusTest
class DispatchRecoveryTest extends DispatchFixture {

    @Inject
    Journal journal;

    @Inject
    ClaudeCodeProbe probe;

    @Inject
    MissionRecovery recovery;

    @Inject
    StaffProvisioner provisioner;

    @Inject
    dev.floor42.server.app.OfficeSettings settings;

    void setSessionCap(String usd) {
        office.execute(c -> c.preferences()
                .changeSetting(
                        office.context(Actor.director(), Origin.REAL),
                        dev.floor42.domain.Setting.SESSION_BUDGET_USD,
                        usd));
    }

    void aSessionRuns(MissionId m) throws InterruptedException {
        await("a session runs", Duration.ofSeconds(30), () -> {
            answerAll(m, null);
            return office.query(c -> c.sessions().of(m).stream().anyMatch(AgentSession::isOpen));
        });
    }

    @Test
    void aSessionLeftOpenByThePreviousDaemonIsClosedAndTheDirectorAsked() throws Exception {
        MissionId m = mission("Write notes [fake:slow=4]");
        approve(m);
        aSessionRuns(m);
        Double ranUnder = office.query(c -> c.sessions().of(m).getFirst().capUsd());
        assertThat(ranUnder).as("the cap is journaled with the start").isNotNull();
        // The director changes the session cap while the daemon is down: the session still ran under the old one.
        double before = settings.sessionBudgetUsd();
        setSessionCap(Double.toString(before + 1.5));

        // What a restart finds in the journal: a session open, which no running daemon will ever end.
        try {
            recovery.recover();
        } finally {
            setSessionCap(Double.toString(before));
        }

        List<AgentSession> sessions = office.query(c -> c.sessions().of(m));
        assertThat(sessions).noneMatch(AgentSession::isOpen).anyMatch(s -> "interrupted".equals(s.outcome()));
        // Its cost died with it: the budget counts the cap it ran under, as a bound and not as a measure.
        assertThat(sessions).allMatch(s -> s.costUsdEstimated() == null && ranUnder.equals(s.costUsdBound()));
        double estimated = office.query(c -> c.verification().spentUsdEstimated(m));
        double bound = office.query(c -> c.verification().spentUsdBound(m));
        int bounded = office.query(c -> c.verification().sessionsBounded(m));
        assertThat(estimated).isZero();
        assertThat(bound).isEqualTo(ranUnder);
        assertThat(bounded).isEqualTo(1);
        boolean asked = office.query(c -> c.desk().of(m).stream()
                .anyMatch(q -> q.status() == QuestionStatus.OPEN && q.options().contains("Retry")));
        assertThat(asked).as("the director decides whether to run it again").isTrue();
        cancel(m);
    }

    @Inject
    dev.floor42.server.runtime.UnnamedLaunches unnamed;

    /** What MissionRecovery logs at WARN while the body runs. */
    List<String> warnings(Runnable body) {
        List<String> out = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(MissionRecovery.class.getName());
        java.util.logging.Handler h = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord r) {
                if (r.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue())
                    out.add(r.getMessage() + " " + java.util.Arrays.toString(r.getParameters()));
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        log.addHandler(h);
        try {
            body.run();
        } finally {
            log.removeHandler(h);
        }
        return out;
    }

    /**
     * A session journaled before 1.9.0 has no cap: the restart bounds it by today's cap for the mission, and says so at
     * WARN. Played with a record started without a cap, beside the session that runs.
     */
    @Test
    void anInterruptedSessionWithoutACapInTheJournalIsBoundedByTodaysCapAndTheLogSaysSo() throws Exception {
        MissionId m = mission("Write notes [fake:slow=4]");
        approve(m);
        aSessionRuns(m);
        AgentSession running = office.query(c -> c.sessions().of(m).getFirst());
        String old = m.value() + ":before-1.9.0";
        office.execute(c -> c.sessions()
                .start(
                        office.context(Actor.system(), Origin.REAL),
                        old,
                        running.ticket(),
                        running.agent(),
                        "claude-sonnet-5-5",
                        "2.1.304"));
        double today = settings.sessionCapUsd(m);

        List<String> warned = warnings(recovery::recover);

        AgentSession closed = office.query(c -> c.sessions().find(old).orElseThrow());
        assertThat(closed.capUsd()).isNull();
        assertThat(closed.costUsdBound()).isEqualTo(today);
        assertThat(warned).anyMatch(w -> w.contains("no cap in the journal") && w.contains(old));
        cancel(m);
    }

    /**
     * The previous daemon launched a process and stopped before it named its session: nothing in the journal, only the
     * session folder (its process record, its launch record without a session id). The restart records it with the cap
     * it was launched with, so the budget counts it.
     */
    @Test
    void aLaunchThePreviousDaemonLeftWithoutASessionCountsForItsCap() throws Exception {
        MissionId m = mission("Write notes [fake:slow=4]");
        approve(m);
        aSessionRuns(m);
        AgentSession running = office.query(c -> c.sessions().of(m).getFirst());
        Path sessions = Files.createTempDirectory("floor42-sessions");
        Path dir = Files.createDirectories(sessions.resolve("planted-1"));
        Files.writeString(dir.resolve("process.txt"), "999999999 2026-11-01T00:00:00Z");
        Files.writeString(
                dir.resolve("launch.json"),
                "{\"mission\":\"" + m.value() + "\",\"ticket\":\""
                        + running.ticket().value() + "\",\"agent\":\""
                        + running.agent().value() + "\",\"model\":\"claude-sonnet-5-5\",\"sessionId\":null,"
                        + "\"capUsd\":0.07}");
        unnamed.scan(sessions);

        recovery.recover();

        AgentSession launch = office.query(
                c -> c.sessions().find(m.value() + ":launch-planted-1").orElseThrow());
        assertThat(launch.isOpen()).isFalse();
        assertThat(launch.costUsdEstimated()).isNull();
        assertThat(launch.costUsdBound()).isEqualTo(0.07);
        assertThat(unnamed.take()).as("counted once").isEmpty();
        cancel(m);
    }

    /** A session folder as a stopped daemon leaves it: its process record and its launch record. */
    static Path plant(Path sessions, String folder, MissionId m, AgentSession on, String sessionId, double cap)
            throws java.io.IOException {
        Path dir = Files.createDirectories(sessions.resolve(folder));
        Files.writeString(dir.resolve("process.txt"), "999999999 2026-11-01T00:00:00Z");
        Files.writeString(
                dir.resolve("launch.json"),
                "{\"mission\":\"" + m.value() + "\",\"ticket\":\"" + on.ticket().value() + "\",\"agent\":\""
                        + on.agent().value() + "\",\"model\":\"claude-sonnet-5-5\",\"sessionId\":"
                        + (sessionId == null ? "null" : "\"" + sessionId + "\"") + ",\"capUsd\":" + cap + "}");
        return dir;
    }

    private List<AgentSession> launches(MissionId m) {
        return office.query(c -> c.sessions().of(m).stream()
                .filter(s -> s.id().contains(":launch-"))
                .toList());
    }

    /**
     * The live daemon journals a launch that never named its session, then stops before its folder is cleared (the
     * process tree takes up to 10 s to stop): the restart finds the folder, sees the launch journaled under the same
     * id, and counts its cap once.
     */
    @Test
    void aLaunchJournaledLiveThenFoundAgainAtTheRestartCountsOnce() throws Exception {
        MissionId m = mission("Write notes [fake:slow=4]");
        approve(m);
        aSessionRuns(m);
        AgentSession running = office.query(c -> c.sessions().of(m).getFirst());
        Path sessions = Files.createTempDirectory("floor42-sessions");
        plant(sessions, "planted-live", m, running, null, 0.07);
        var spec = new dev.floor42.server.runtime.AgentRuntime.SessionSpec(
                m, running.ticket(), running.agent(), Origin.REAL, null, "brief", "claude-sonnet-5-5", null, List.of());
        new dev.floor42.server.app.MissionGovernor(office, spec)
                .observed(new dev.floor42.server.runtime.RuntimeEvent.SessionEnded(
                        true, null, null, null, null, null, null, 0.07, "planted-live"));

        unnamed.scan(sessions);
        recovery.countUnnamedLaunches();

        assertThat(launches(m))
                .singleElement()
                .satisfies(l -> assertThat(l.costUsdBound()).isEqualTo(0.07));
        cancel(m);
    }

    /**
     * The launch record names a session the journal never got (the daemon stopped between the two): its cap is not
     * lost, the restart counts it once as a launch; a named session the journal holds is left to its own record.
     */
    @Test
    void aLaunchNamingASessionTheJournalNeverGotCountsForItsCap() throws Exception {
        MissionId m = mission("Write notes [fake:slow=4]");
        approve(m);
        aSessionRuns(m);
        AgentSession running = office.query(c -> c.sessions().of(m).getFirst());
        Path sessions = Files.createTempDirectory("floor42-sessions");
        plant(sessions, "planted-named", m, running, "never-journaled", 0.05);
        plant(
                sessions,
                "planted-held",
                m,
                running,
                running.id().substring(m.value().length() + 1),
                0.09);

        unnamed.scan(sessions);
        recovery.countUnnamedLaunches();

        assertThat(launches(m)).singleElement().satisfies(l -> {
            assertThat(l.id()).isEqualTo(m.value() + ":launch-planted-named");
            assertThat(l.costUsdBound()).isEqualTo(0.05);
        });
        cancel(m);
    }

    /**
     * A session that crashed reported no cost: its cap counts, and once those bounds reach the budget the next dispatch
     * is held, even when the director asks for the step again. The question says the bound apart from the estimates.
     */
    @Test
    void theCapsOfSessionsWithoutACostHoldTheNextDispatchOnceTheyReachTheBudget() throws Exception {
        MissionId m = mission("Write notes [fake:crash]");
        api().body("{\"budgetUsd\":0.01}")
                .post("/api/missions/" + m.value() + "/approve")
                .then()
                .statusCode(200);
        await(
                "the crashed session ended",
                Duration.ofSeconds(30),
                () -> office.query(c -> {
                    List<AgentSession> s = c.sessions().of(m);
                    return !s.isEmpty() && s.stream().noneMatch(AgentSession::isOpen);
                }));

        await("the budget hold", Duration.ofSeconds(30), () -> {
            retryIfAsked(m);
            return budgetQuestion(m) != null;
        });
        quiet();

        int sessions = office.query(c -> c.sessions().of(m).size());
        double estimated = office.query(c -> c.verification().spentUsdEstimated(m));
        assertThat(sessions).as("no session started over the bound").isEqualTo(1);
        assertThat(estimated).isZero();
        assertThat(budgetQuestion(m).context())
                .contains("estimated 0.0000 USD by the runtime + up to")
                .contains("for 1 session(s) with no reported cost")
                .doesNotContain("Estimated spending");
        cancel(m);
    }

    private void retryIfAsked(MissionId m) {
        office.query(c -> c.desk().of(m).stream()
                        .filter(q ->
                                q.status() == QuestionStatus.OPEN && q.options().contains("Retry"))
                        .map(q -> q.id().value())
                        .toList())
                .forEach(q -> api().body("{\"answer\":\"Retry\"}").post("/api/questions/" + q + "/answer"));
    }

    private dev.floor42.domain.Question budgetQuestion(MissionId m) {
        return office.query(c -> c.desk().of(m).stream()
                .filter(q ->
                        q.status() == QuestionStatus.OPEN && q.kind() == dev.floor42.domain.QuestionKind.BUDGET_REACHED)
                .findFirst()
                .orElse(null));
    }

    /**
     * The daemon stopped between the director's request and the planner's advice: nothing resumes the review, so it is
     * closed as interrupted. Before, its ticket stayed in progress for good, the planner was refused from every meeting
     * (AGENT_IN_STAFFING) and a new request was refused (STAFFING_UNDER_WAY).
     */
    @Test
    void aStaffingReviewLeftUnadvisedByThePreviousDaemonIsClosedAndThePlannerFreed() throws Exception {
        MissionId m = mission("Write notes [fake:slow=4]");
        approve(m);
        aSessionRuns(m);
        provisioner.ensurePlanner();
        AgentId planner = new AgentId("galen");
        CommandContext director = office.context(Actor.director(), Origin.REAL);
        office.execute(c -> c.staff().requestStaffingReview(director, m, planner));
        TicketId ticket =
                office.query(c -> c.staff().staffingReview(m).orElseThrow().ticket());
        TicketStatus before =
                office.query(c -> c.tickets().find(ticket).orElseThrow().status());
        assertThat(before).isEqualTo(TicketStatus.IN_PROGRESS);

        recovery.recover();

        boolean advised =
                office.query(c -> c.staff().staffingReview(m).orElseThrow().advised());
        assertThat(advised).as("closed, not left under way").isTrue();
        TicketStatus after =
                office.query(c -> c.tickets().find(ticket).orElseThrow().status());
        assertThat(after).isEqualTo(TicketStatus.DONE);
        String why = journal.readAll().stream()
                .map(e -> e.event())
                .filter(e -> e instanceof StaffingAdvised a && a.mission().equals(m))
                .map(e -> ((StaffingAdvised) e).advice())
                .findFirst()
                .orElseThrow();
        assertThat(why).contains("interrupted");
        boolean asked = office.query(c ->
                c.desk().of(m).stream().anyMatch(q -> q.status() == QuestionStatus.OPEN && ticket.equals(q.ticket())));
        assertThat(asked).as("no Retry about a ticket nobody works").isFalse();
        // The planner is free and a new request is accepted again.
        office.execute(c -> c.staff().requestStaffingReview(director, m, planner));
        cancel(m);
    }

    /**
     * The planner's session is open on the review ticket (as {@code MissionGovernor.started} logs it for runUnheld):
     * it is ended as interrupted, and the director is not asked to "Retry" a ticket nobody works.
     */
    @Test
    void aPlannerSessionLeftOpenOnAStaffingReviewIsEndedWithoutARetryQuestion() throws Exception {
        MissionId m = mission("Write notes [fake:slow=4]");
        approve(m);
        aSessionRuns(m);
        provisioner.ensurePlanner();
        AgentId planner = new AgentId("galen");
        CommandContext director = office.context(Actor.director(), Origin.REAL);
        office.execute(c -> c.staff().requestStaffingReview(director, m, planner));
        TicketId ticket =
                office.query(c -> c.staff().staffingReview(m).orElseThrow().ticket());
        office.execute(c -> c.sessions()
                .start(
                        office.context(Actor.system(), Origin.REAL),
                        "report-staff-" + m.value(),
                        ticket,
                        planner,
                        "claude-sonnet-5-5",
                        "2"));

        recovery.recover();

        AgentSession planned = office.query(c -> c.sessions().of(m).stream()
                .filter(s -> s.id().equals("report-staff-" + m.value()))
                .findFirst()
                .orElseThrow());
        assertThat(planned.isOpen()).isFalse();
        assertThat(planned.outcome()).isEqualTo(AgentSession.INTERRUPTED);
        boolean asked = office.query(c ->
                c.desk().of(m).stream().anyMatch(q -> q.status() == QuestionStatus.OPEN && ticket.equals(q.ticket())));
        assertThat(asked).as("no Retry about the staffing ticket").isFalse();
        cancel(m);
    }

    @Test
    void aSessionLeftOpenInASuspendedMissionIsClosedToo() throws Exception {
        MissionId m = mission("Write notes [fake:slow=4]");
        approve(m);
        aSessionRuns(m);
        office.execute(
                c -> c.missions().suspend(office.context(Actor.director(), Origin.REAL), m, "incident under review"));

        // The daemon stopped while the suspended mission's session ran: before, only missions in progress were
        // recovered, and its agent stayed at work for good.
        recovery.recover();

        List<AgentSession> sessions = office.query(c -> c.sessions().of(m));
        assertThat(sessions).noneMatch(AgentSession::isOpen).anyMatch(s -> "interrupted".equals(s.outcome()));
        MissionStatus status =
                office.query(c -> c.missions().find(m).orElseThrow().status());
        assertThat(status).as("the work still waits for the director's resume").isEqualTo(MissionStatus.SUSPENDED);
        cancel(m);
    }

    @Test
    void aToolsHeartbeatShowsOnItsActionAndALineTheDirectorAcceptedIsRecordedMasked() throws Exception {
        api().body("{\"value\":\"brand_new_line\"}")
                .put("/api/settings/ACCEPTED_STREAM_LINES")
                .then()
                .statusCode(200);
        try {
            MissionId m = mission("Write notes [fake:progress]");
            approve(m);
            // The fake emits the new line just after the heartbeat: wait for both, not for the first one only.
            await("a heartbeat and the accepted line are recorded", Duration.ofSeconds(60), () -> {
                answerAll(m, null);
                return office.query(c -> c.actions().of(m).stream().anyMatch(a -> a.runningSeconds() != null))
                        && !linesOf(m).isEmpty();
            });
            List<Long> running = office.query(c -> c.actions().of(m).stream()
                    .filter(a -> a.runningSeconds() != null)
                    .map(a -> a.runningSeconds())
                    .toList());
            assertThat(running).containsOnly(30L);
            assertThat(linesOf(m)).isNotEmpty().allMatch(l -> l.contains("[masked]") && !l.contains("sk-ant"));
            assertThat(probe.unfamiliar()).noneMatch(u -> u.kind().equals("brand_new_line"));
            cancel(m);
        } finally {
            api().body("{\"value\":null}").put("/api/settings/ACCEPTED_STREAM_LINES");
        }
    }

    /**
     * The stream lines recorded for this mission's sessions: {@code SessionLog.runtimeLine} records each one under its
     * session's mission, so the envelope's mission is the filter.
     */
    List<String> linesOf(MissionId m) {
        return journal.readAll().stream()
                .filter(e -> m.equals(e.mission()))
                .map(e -> e.event())
                .filter(e -> e instanceof RuntimeLineRecorded)
                .map(e -> ((RuntimeLineRecorded) e).content())
                .toList();
    }

    @Test
    void aToolClaudeCodeRefusesItselfIsRecordedAndTheReviewGoesOn() throws Exception {
        // Seen live on M-7: the reviewer called Bash, which its session does not offer; no hook ran, and Floor42
        // stopped the review on an action it had never heard of, leaving the session open for good.
        MissionId m = mission("Write notes [fake:unoffered]");
        approve(m);
        await("the ticket is reviewed", Duration.ofSeconds(60), () -> {
            allowActions(m);
            return office.query(
                    c -> c.tickets().of(m).stream().anyMatch(t -> !t.reviews().isEmpty()));
        });
        List<Action> refused = office.query(c -> c.actions().of(m).stream()
                .filter(a -> a.denialReason() == DenialReason.BLOCKED_UPSTREAM)
                .toList());
        assertThat(refused).isNotEmpty().allMatch(a -> a.tool().equals("Bash"));
        boolean open = office.query(c -> c.sessions().of(m).stream().anyMatch(s -> s.isOpen()));
        assertThat(open).as("every session ended").isFalse();
        cancel(m);
    }

    @Test
    void aCommitteeThatCannotRunWaitsForRetryInsteadOfLooping() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        MissionId m =
                mission(repo, "Should the APIs sit behind a gateway?", ",\"committee\":[\"security\",\"developer\"]");
        // The repository disappears before the committee meets: Floor42 cannot prepare the chair's worktree.
        try (Stream<Path> files = Files.walk(repo)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                p.toFile().setWritable(true);
                p.toFile().delete();
            });
        }
        approve(m);

        await(
                "the director is asked",
                Duration.ofSeconds(20),
                () -> office.query(c -> c.desk().of(m).stream()
                        .anyMatch(q ->
                                q.status() == QuestionStatus.OPEN && q.options().contains("Retry"))));
        // A loop would never let the dispatcher go quiet: this fails then, instead of counting too early.
        quiet();
        int questions = office.query(c -> c.desk().of(m).size());
        assertThat(questions).as("one question, not one per attempt").isEqualTo(1);
        int sessions = office.query(c -> c.sessions().of(m).size());
        assertThat(sessions).isZero();
        cancel(m);
    }
}
