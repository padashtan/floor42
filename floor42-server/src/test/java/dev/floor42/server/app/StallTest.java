package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Criticality;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.ReviewVerdict;
import dev.floor42.domain.Setting;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.WorkRole;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.workflow.Workflow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The stall alert does not cry wolf: a mission is STUCK only when the dispatcher's own rule would start a task and
 * nothing ran for the stall setting; everything that rightly holds work back is a WAITING with its reason. The
 * dispatcher is off in this profile, so nothing starts by itself: the state is exactly what each test builds, and no
 * model is called.
 */
@QuarkusTest
@TestProfile(StallTest.DispatcherOff.class)
class StallTest {

    /** Nothing starts by itself: each test reads the state it built. */
    public static class DispatcherOff implements QuarkusTestProfile {
        @Override
        public java.util.Map<String, String> getConfigOverrides() {
            return java.util.Map.of("floor42.live-missions.enabled", "false");
        }
    }

    static final AgentId CHRIS = new AgentId("chris");
    static final AgentId DARIUS = new AgentId("darius");

    @Inject
    OfficeService office;

    @Inject
    LiveMissions live;

    @Inject
    MissionHolds holds;

    @Inject
    Workspaces workspaces;

    @Inject
    Progress progress;

    @Inject
    AwaySummary away;

    @Inject
    dev.floor42.server.security.SessionToken token;

    final List<AgentReservations.Reservation> reserved = new java.util.ArrayList<>();

    @AfterEach
    void releaseAgents() {
        reserved.forEach(live::release);
        reserved.clear();
    }

    CommandContext director() {
        return office.context(Actor.director(), Origin.REAL);
    }

    void hire(AgentId id, String name, String role) {
        if (office.query(c -> c.staff().find(id).isPresent())) return;
        office.execute(
                c -> c.staff().hire(director(), id, name, role, "anthropic", "claude-sonnet-5-5", "medium", List.of()));
    }

    static Path gitRepository() throws Exception {
        Path repo = Files.createTempDirectory("office-stall");
        Files.writeString(repo.resolve("README.md"), "# Fake project\n");
        run(repo, "git", "init", "-q", "-b", "main");
        run(repo, "git", "add", ".");
        run(repo, "git", "-c", "user.name=test", "-c", "user.email=test@localhost", "commit", "-q", "-m", "init");
        return repo;
    }

    static void run(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).as(String.join(" ", cmd)).isZero();
    }

    record Setup(MissionId mission, TicketId ticket, Path repo) {}

    /** A real mission in progress with one ticket for Chris, which the dispatcher would start at once. */
    Setup missionWithWork() throws Exception {
        hire(CHRIS, "Chris", "Java developer");
        hire(DARIUS, "Darius", "Test engineer");
        Path repo = gitRepository();
        MissionId m = ((MissionCreated) office.execute(c -> c.missions()
                                .create(director(), "Stall", "Watch for stalls", repo.toString(), Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        TicketId t = ticket(m, "Write notes");
        office.execute(c -> c.tickets().assign(director(), t, CHRIS));
        office.execute(c -> c.missions().submitPlan(director(), m));
        office.execute(c -> c.verification().setBudget(director(), m, null));
        office.execute(c -> c.missions().approvePlan(director(), m));
        return new Setup(m, t, repo);
    }

    TicketId ticket(MissionId m, String title) {
        return ((TicketCreated) office.execute(c -> c.tickets()
                                .create(director(), m, title, "", Priority.NORMAL, List.of(), List.of("report")))
                        .getFirst()
                        .event())
                .ticket();
    }

    Instant later(int minutes) {
        return Instant.now().plus(Duration.ofMinutes(minutes));
    }

    @Test
    void workTheDispatcherWouldStartIsStuckOnlyOnceTheSettingHasPassed() throws Exception {
        Setup s = missionWithWork();

        Stall now = live.stall(s.mission(), Instant.now());
        assertThat(now.state()).isEqualTo("DUE");
        assertThat(now.reason()).isEqualTo("WOULD_START");
        assertThat(now.work()).isEqualTo("PRODUCE");
        assertThat(now.ticket()).isEqualTo(s.ticket().value());
        assertThat(now.agent()).isEqualTo(CHRIS.value());
        Progress.MissionProgress p = progress.of(s.mission());
        assertThat(p.stuck()).as("not stuck before the setting has passed").isEmpty();

        Stall after = live.stall(s.mission(), later(6));
        assertThat(after.state()).isEqualTo("STUCK");
        assertThat(after.since()).isNotNull();

        // The director's setting decides how long is too long.
        office.execute(c -> c.preferences().changeSetting(director(), Setting.STALL_MINUTES, "10"));
        try {
            assertThat(live.stall(s.mission(), later(6)).state()).isEqualTo("DUE");
            assertThat(live.stall(s.mission(), later(11)).state()).isEqualTo("STUCK");
        } finally {
            office.execute(c -> c.preferences().changeSetting(director(), Setting.STALL_MINUTES, null));
        }
    }

    @Test
    void anOpenQuestionIsAWaitNotAStall() throws Exception {
        Setup s = missionWithWork();
        // A failed session asks "Retry": the whole mission waits for the answer.
        office.execute(c -> c.desk()
                .ask(
                        office.context(Actor.system(), Origin.REAL),
                        s.mission(),
                        "The session failed",
                        "the session ended without a report",
                        List.of(LiveMissions.RETRY, "Leave it"),
                        LiveMissions.RETRY,
                        Criticality.HIGH,
                        false,
                        s.ticket()));
        Stall st = live.stall(s.mission(), later(60));
        assertThat(st.state()).isEqualTo("WAITING");
        assertThat(st.reason()).isEqualTo("QUESTION");
        assertThat(st.ticket()).isEqualTo(s.ticket().value());
        assertThat(st.since()).isNotNull();
    }

    @Test
    void aQuestionOnTheTicketAloneIsAWaitToo() throws Exception {
        Setup s = missionWithWork();
        office.execute(c -> c.desk()
                .ask(
                        office.context(Actor.system(), Origin.REAL),
                        s.mission(),
                        "Which format?",
                        "the notes",
                        List.of("Markdown", "Plain text"),
                        "Markdown",
                        Criticality.MEDIUM,
                        false,
                        s.ticket()));
        Stall st = live.stall(s.mission(), later(60));
        assertThat(st.state()).isEqualTo("WAITING");
        assertThat(st.reason()).isEqualTo("QUESTION");
    }

    @Test
    void proposedTicketsWaitForTheDirector() throws Exception {
        Setup s = missionWithWork();
        office.execute(c -> c.tickets().cancel(director(), s.ticket(), "replaced by a proposal"));
        office.execute(c -> c.missions().enableTicketValidation(director(), s.mission()));
        TicketId proposed = ((TicketCreated) office.execute(c -> c.tickets()
                                .create(
                                        office.context(Actor.system(), Origin.REAL),
                                        s.mission(),
                                        "Proposed notes",
                                        "",
                                        Priority.NORMAL,
                                        List.of(),
                                        List.of("report")))
                        .getFirst()
                        .event())
                .ticket();
        TicketStatus status =
                office.query(c -> c.tickets().find(proposed).orElseThrow().status());
        assertThat(status).isEqualTo(TicketStatus.PROPOSED);

        Stall st = live.stall(s.mission(), later(60));
        assertThat(st.state()).isEqualTo("WAITING");
        assertThat(st.reason()).isEqualTo("PROPOSED");
        assertThat(st.ticket()).isEqualTo(proposed.value());
    }

    @Test
    void aRestingMissionWaitsForTheReset() throws Exception {
        Setup s = missionWithWork();
        Instant until = later(30);
        holds.restUntil(s.mission(), until);
        try {
            Stall st = live.stall(s.mission(), later(10));
            assertThat(st.state()).isEqualTo("WAITING");
            assertThat(st.reason()).isEqualTo("RESTING");
            assertThat(st.until()).isEqualTo(until);
        } finally {
            holds.restUntil(s.mission(), null);
        }
    }

    @Test
    void aMissionWhoseAgentWorksElsewhereWaitsItsTurnAndSaysWhere() throws Exception {
        Setup elsewhere = missionWithWork();
        Setup s = missionWithWork();
        // Chris runs a session on the other mission; every developer is taken.
        office.execute(c -> c.tickets().start(office.context(Actor.agent(CHRIS), Origin.REAL), elsewhere.ticket()));
        String session = "stall-" + UUID.randomUUID();
        office.execute(c -> c.sessions()
                .start(
                        office.context(Actor.system(), Origin.REAL),
                        session,
                        elsewhere.ticket(),
                        CHRIS,
                        "claude-sonnet-5-5",
                        "test"));
        List<AgentId> developers = office.query(c ->
                Staff.active(c, WorkRole.DEVELOPER).stream().map(a -> a.id()).toList());
        for (AgentId d : developers) {
            java.util.Optional<AgentReservations.Reservation> r = live.reserve(d);
            assertThat(r).isPresent();
            reserved.add(r.get());
        }
        try {
            Stall st = live.stall(s.mission(), later(60));
            assertThat(st.state()).isEqualTo("WAITING");
            assertThat(st.reason()).isEqualTo("AGENT_BUSY");
            assertThat(st.agent()).isEqualTo(CHRIS.value());
            assertThat(st.elsewhere()).isEqualTo(elsewhere.mission().value());
            assertThat(st.ticket()).isEqualTo(s.ticket().value());
        } finally {
            office.execute(c -> c.sessions()
                    .end(office.context(Actor.system(), Origin.REAL), session, false, "done", 1, null, null, null));
        }
    }

    @Test
    void finishedWorkHeldInTheWorkshopIsAWait() throws Exception {
        Setup s = missionWithWork();
        // Chris finishes the first ticket; his worktree still holds its changes, which the director has not accepted.
        office.execute(c -> c.tickets().start(office.context(Actor.agent(CHRIS), Origin.REAL), s.ticket()));
        Path worktree = workspaces.worktree(s.repo().toString(), s.mission(), CHRIS);
        office.execute(c -> c.workshop()
                .prepareWorkspace(
                        office.context(Actor.system(), Origin.REAL),
                        s.mission(),
                        CHRIS,
                        worktree.toString(),
                        workspaces.branch(worktree),
                        workspaces.head(worktree)));
        Files.writeString(worktree.resolve("README.md"), "# Fake project\n\nNotes.\n");
        office.execute(
                c -> c.tickets().submitForReview(office.context(Actor.agent(CHRIS), Origin.REAL), s.ticket(), "notes"));
        office.execute(c -> c.tickets()
                .review(office.context(Actor.agent(DARIUS), Origin.REAL), s.ticket(), ReviewVerdict.VALIDATED, "fine"));
        TicketId next = ticket(s.mission(), "More notes");
        assertThat(live.held(s.mission())).containsKey(CHRIS);

        Stall st = live.stall(s.mission(), later(60));
        assertThat(st.state()).isEqualTo("WAITING");
        assertThat(st.reason()).isEqualTo("HELD_PATCH");
        assertThat(st.agent()).isEqualTo(CHRIS.value());
        assertThat(st.ticket()).isEqualTo(next.value());
    }

    /**
     * The dispatch lock is shared by every mission: reading a worktree with git (up to a minute per call) must not hold
     * it, or every other mission's dispatcher and every reservation waits behind that git.
     */
    @Test
    void gitRunsOutsideTheDispatchLock() throws Exception {
        Setup s = missionWithWork();
        office.execute(c -> c.tickets().start(office.context(Actor.agent(CHRIS), Origin.REAL), s.ticket()));
        Path worktree = workspaces.worktree(s.repo().toString(), s.mission(), CHRIS);
        office.execute(c -> c.workshop()
                .prepareWorkspace(
                        office.context(Actor.system(), Origin.REAL),
                        s.mission(),
                        CHRIS,
                        worktree.toString(),
                        workspaces.branch(worktree),
                        workspaces.head(worktree)));
        office.execute(
                c -> c.tickets().submitForReview(office.context(Actor.agent(CHRIS), Origin.REAL), s.ticket(), "notes"));
        office.execute(c -> c.tickets()
                .review(office.context(Actor.agent(DARIUS), Origin.REAL), s.ticket(), ReviewVerdict.VALIDATED, "fine"));
        ticket(s.mission(), "More notes");
        // Nobody else may take the next ticket: only Chris, held by the slow git below.
        office.query(c -> Staff.active(c, WorkRole.DEVELOPER).stream()
                        .map(a -> a.id())
                        .filter(a -> !a.equals(CHRIS))
                        .toList())
                .forEach(a -> live.reserve(a).ifPresent(reserved::add));
        java.util.concurrent.CountDownLatch inGit = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch gitMayEnd = new java.util.concurrent.CountDownLatch(1);
        io.quarkus.test.junit.QuarkusMock.installMockForType(
                new Workshop() {
                    @Override
                    public List<Change> changes(dev.floor42.domain.Workspace w) {
                        inGit.countDown();
                        try {
                            gitMayEnd.await(30, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return List.of(new Change("README.md", "M", 1, 0));
                    }
                },
                Workshop.class);
        Thread dispatcher = Thread.ofVirtual().start(() -> live.produce(s.mission()));
        try {
            assertThat(inGit.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // While that git runs, another reservation goes through at once.
            java.util.concurrent.CompletableFuture<java.util.Optional<AgentReservations.Reservation>> other =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> live.reserve(DARIUS));
            java.util.Optional<AgentReservations.Reservation> r = other.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(r).isPresent();
            reserved.add(r.get());
        } finally {
            gitMayEnd.countDown();
            dispatcher.join(Duration.ofSeconds(10));
        }
    }

    @Test
    void aReservationIsReleasedOnlyByItsHolder() throws Exception {
        AgentId agent = new AgentId("reserved-" + UUID.randomUUID());
        AgentReservations.Reservation first = live.reserve(agent).orElseThrow();
        assertThat(live.reserve(agent)).as("one holder at a time").isEmpty();

        live.release(first);
        AgentReservations.Reservation second = live.reserve(agent).orElseThrow();
        // A late or repeated release by the first holder does not free the agent the second one holds.
        live.release(first);
        assertThat(live.busy(agent)).isTrue();
        assertThat(live.reserve(agent)).isEmpty();

        live.release(second);
        assertThat(live.busy(agent)).isFalse();
    }

    @Test
    void waitingForABusyAgentEndsWithAClearFailure() throws Exception {
        AgentId agent = new AgentId("reserved-" + UUID.randomUUID());
        AgentReservations.Reservation holder = live.reserve(agent).orElseThrow();
        reserved.add(holder);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> live.await(agent, Duration.ofMillis(100)))
                .isInstanceOf(AgentReservations.ReservationTimeout.class)
                .hasMessageContaining(agent.value())
                .hasMessageContaining("busy elsewhere");

        live.release(holder);
        reserved.remove(holder);
        AgentReservations.Reservation mine = live.await(agent, Duration.ofMillis(100));
        live.release(mine);
    }

    @Test
    void aMissionNotInProgressOrSimulatedIsNeverStuck() throws Exception {
        MissionId sim = ((MissionCreated) office.execute(c -> c.missions()
                                .create(office.context(Actor.director(), Origin.SIMULATED), "Demo", "Simulated"))
                        .getFirst()
                        .event())
                .mission();
        assertThat(live.stall(sim, later(60)).state()).isEqualTo("IDLE");

        Setup s = missionWithWork();
        office.execute(c -> c.tickets().cancel(director(), s.ticket(), "nothing left"));
        Stall st = live.stall(s.mission(), later(60));
        assertThat(st.state()).isEqualTo("IDLE");
        assertThat(st.reason()).isEqualTo("NOTHING");
    }

    /** One ticket delivered from start to validated review, with a session that reports its cost. */
    TicketId deliver(Origin origin, Double cost) throws Exception {
        hire(CHRIS, "Chris", "Java developer");
        hire(DARIUS, "Darius", "Test engineer");
        CommandContext director = office.context(Actor.director(), origin);
        String repo = gitRepository().toString();
        MissionId m = ((MissionCreated) office.execute(c -> origin == Origin.REAL
                                ? c.missions().create(director, "Away", "Deliver", repo, Workflow.BUILT_IN)
                                : c.missions().create(director, "Away", "Deliver"))
                        .getFirst()
                        .event())
                .mission();
        TicketId t = ((TicketCreated) office.execute(c -> c.tickets()
                                .create(
                                        director,
                                        m,
                                        "Delivered " + origin,
                                        "",
                                        Priority.NORMAL,
                                        List.of(),
                                        List.of("report")))
                        .getFirst()
                        .event())
                .ticket();
        office.execute(c -> c.tickets().assign(director, t, CHRIS));
        office.execute(c -> c.missions().submitPlan(director, m));
        office.execute(c -> c.verification().setBudget(director, m, null));
        office.execute(c -> c.missions().approvePlan(director, m));
        office.execute(c -> c.tickets().start(office.context(Actor.agent(CHRIS), origin), t));
        String session = "away-" + UUID.randomUUID();
        office.execute(c -> c.sessions()
                .start(office.context(Actor.system(), origin), session, t, CHRIS, "claude-sonnet-5-5", "test"));
        office.execute(c ->
                c.sessions().end(office.context(Actor.system(), origin), session, false, "done", 1, null, null, cost));
        office.execute(c -> c.tickets().submitForReview(office.context(Actor.agent(CHRIS), origin), t, "notes"));
        office.execute(c ->
                c.tickets().review(office.context(Actor.agent(DARIUS), origin), t, ReviewVerdict.VALIDATED, "fine"));
        return t;
    }

    @Test
    void theSummaryTellsWhatHappenedSinceTheAcknowledgedSequenceAndLeavesTheDemoOut() throws Exception {
        long head = away.since(null).until();
        assertThat(away.since(null).delivered()).isEmpty();

        TicketId real = deliver(Origin.REAL, 0.12);
        TicketId simulated = deliver(Origin.SIMULATED, 5.0);

        AwaySummary.AwayView s = away.since(head);
        assertThat(s.since()).isEqualTo(head);
        assertThat(s.until()).isGreaterThan(head);
        assertThat(s.more()).isFalse();
        assertThat(s.delivered()).extracting(AwaySummary.AwayLine::ticket).containsExactly(real.value());
        assertThat(s.delivered().getFirst().text()).isEqualTo("Delivered REAL");
        assertThat(s.delivered()).extracting(AwaySummary.AwayLine::ticket).doesNotContain(simulated.value());
        assertThat(s.sessions()).isEqualTo(1);
        assertThat(s.costUsdEstimated()).as("the real session's estimate only").isEqualTo(0.12);

        // Acknowledged: nothing new since, and no cost is invented.
        AwaySummary.AwayView after = away.since(s.until());
        assertThat(after.delivered()).isEmpty();
        assertThat(after.sessions()).isZero();
        assertThat(after.costUsdEstimated()).isNull();

        io.restassured.RestAssured.given()
                .header("Authorization", "Bearer " + token.value())
                .get("/api/journal/summary?since=" + head)
                .then()
                .statusCode(200)
                .body("delivered.ticket", org.hamcrest.Matchers.hasItem(real.value()))
                .body("until", org.hamcrest.Matchers.notNullValue());
    }
}
