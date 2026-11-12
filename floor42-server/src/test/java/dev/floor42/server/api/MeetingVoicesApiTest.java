package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.Action;
import dev.floor42.domain.ActionStage;
import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.AgentStatus;
import dev.floor42.domain.Meeting;
import dev.floor42.domain.MeetingTurn;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.SessionEvent;
import dev.floor42.server.app.LiveSeams;
import dev.floor42.server.app.MeetingVoices;
import dev.floor42.server.journal.Journal;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Turns of speech at the director's meeting (ADR-0049), with the fake Claude Code: each speaker answers once, in the
 * director's order, in a read-only session on the meeting's own ticket, within the meeting's cost cap. Each guard is
 * proven by what it keeps from happening: a write, a turn past the cap, a ticket on the board, a lost --resume.
 */
@QuarkusTest
class MeetingVoicesApiTest extends DispatchFixture {

    static final AgentId CHRIS = new AgentId("chris");
    static final AgentId DEHREN = new AgentId("dehren");

    @Inject
    Journal journal;

    @Inject
    MeetingVoices voices;

    @Inject
    dev.floor42.server.runtime.CostCap costCap;

    @Inject
    dev.floor42.server.app.LiveStartup startup;

    @Inject
    dev.floor42.server.runtime.UnnamedLaunches unnamed;

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    /** A real mission with Chris at work, its Write allowed by the table (only the turn's guard may refuse it). */
    MissionId atWork(String objective) throws Exception {
        MissionId m = mission(LiveMissionTest.gitRepository(), objective, "");
        for (String tool : List.of("Write", "Bash")) {
            api().body("{\"decision\":\"ALLOW\"}")
                    .put("/api/missions/" + m.value() + "/tools/" + tool)
                    .then()
                    .statusCode(200);
        }
        approve(m);
        await(
                "chris at work",
                Duration.ofSeconds(30),
                () -> sessions(m).stream().anyMatch(AgentSession::isOpen));
        return m;
    }

    String call(MissionId m, String place) {
        return api().body("{\"mission\":\"" + m.value() + "\",\"attendees\":[\"chris\",\"dehren\"],\"agenda\":"
                        + "\"Review the release notes\",\"place\":\"" + place + "\"}")
                .post("/api/meetings")
                .then()
                .statusCode(200)
                .body("place", equalTo(place))
                .extract()
                .path("id");
    }

    JsonPath ask(String meeting, String speakers, double cap, String question) {
        return api().body("{\"speakers\":[" + speakers + "],\"capUsd\":" + cap + ",\"question\":\"" + question + "\"}")
                .post("/api/meetings/" + meeting + "/views")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    @Test
    void eachSpeakerAnswersInTurnReadingOnlyOnTheMeetingsOwnTicket() throws Exception {
        MissionId m = atWork("Describe this repository [fake:meeting]");
        String meeting = call(m, "CONFERENCE");
        await("chris seated", Duration.ofSeconds(20), () -> status(CHRIS) == AgentStatus.IN_MEETING);

        JsonPath asked = ask(meeting, "\"dehren\",\"chris\"", 1.0, "[fake:meeting-turn-write] Is it ready?");
        assertThat(asked.getList("rounds[0].turns.attendee")).containsExactly("dehren", "chris");
        quiet();

        Meeting mt = office.query(c -> c.meetings().find(meeting).orElseThrow());
        assertThat(mt.rounds().getFirst().turns())
                .extracting(MeetingTurn::attendee)
                .as("in the director's order")
                .containsExactly(DEHREN, CHRIS);
        assertThat(mt.rounds().getFirst().turns())
                .allSatisfy(t -> assertThat(t.answer()).contains("README.md"));
        readOnly(m, mt);
        assertThat(api().get("/api/missions/" + m.value())
                        .then()
                        .extract()
                        .<String>jsonPath()
                        .getList("tickets.id"))
                .as("the meeting's ticket is never on the board")
                .doesNotContain(mt.ticket().value());
        assertThat(status(CHRIS)).as("still seated after speaking").isEqualTo(AgentStatus.IN_MEETING);
        JsonPath view = api().get("/api/meetings").then().extract().jsonPath();
        assertThat(view.getString("find { it.id == '" + meeting + "' }.rounds[0].turns[0].costBasis"))
                .isEqualTo("ESTIMATED_BY_CLAUDE_CODE");
        closeAndCancel(meeting, m);
        figuresLeaveTheTurnsOut(m, mt);
    }

    /** Turns are the mission's spend, not its work: no session count, failure or work time of theirs (ADR-0049). */
    private void figuresLeaveTheTurnsOut(MissionId m, Meeting mt) {
        List<AgentSession> all = sessions(m);
        long work = all.stream().filter(s -> !s.ticket().equals(mt.ticket())).count();
        assertThat(work).as("turns ran").isLessThan(all.size());
        JsonPath report = api().get("/api/missions/" + m.value() + "/report")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(report.getInt("totals.sessions")).isEqualTo((int) work);
        long workMs = all.stream()
                .filter(s -> !s.ticket().equals(mt.ticket()) && s.endedAt() != null)
                .mapToLong(s -> Duration.between(s.startedAt(), s.endedAt()).toMillis())
                .sum();
        JsonPath view = api().get("/api/missions/" + m.value()).then().extract().jsonPath();
        assertThat(view.getString("scorecard.find { it.label == 'Agent time' }.value"))
                .as("the scorecard's agent time: work sessions only")
                .isEqualTo(String.format(java.util.Locale.ROOT, "%.1f s", workMs / 1000.0));
        JsonPath dehren = api().get("/api/agents/dehren/profile")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        long dehrenWork = office.query(c -> c.missions().all().stream()
                .filter(x -> x.origin() == dev.floor42.domain.Origin.REAL)
                .flatMap(x -> c.sessions().of(x.id()).stream())
                .filter(s -> s.agent().equals(DEHREN))
                .filter(s -> !dev.floor42.server.app.TurnSessions.isTurn(c, s))
                .count());
        assertThat(dehren.getInt("record.sessions"))
                .as("its turn is no work session")
                .isEqualTo((int) dehrenWork);
    }

    /** The guard: the table allows Write and Bash, the turn's governor refuses them; the worktree is untouched. */
    private void readOnly(MissionId m, Meeting mt) {
        List<Action> turns = office.query(c -> c.actions().of(m).stream()
                .filter(a -> mt.ticket().equals(a.ticket()))
                .toList());
        assertThat(turns)
                .filteredOn(a -> List.of("Write", "Bash").contains(a.tool()))
                .hasSize(4)
                .allSatisfy(a -> assertThat(a.stage()).isEqualTo(ActionStage.DENIED));
        assertThat(turns)
                .filteredOn(a -> a.tool().equals("Read"))
                .allSatisfy(a -> assertThat(a.stage()).isEqualTo(ActionStage.EXECUTED));
        String where =
                office.query(c -> c.workshop().workspace(m, CHRIS).orElseThrow().path());
        Path worktree = Path.of(where);
        assertThat(worktree.resolve("turn-wrote.txt"))
                .as("nothing written at the meeting")
                .doesNotExist();
    }

    @Test
    void aTurnPastTheMeetingsCapIsNotRun() throws Exception {
        MissionId m = atWork("Describe this repository [fake:meeting]");
        String meeting = call(m, "TABLE");
        await("chris seated", Duration.ofSeconds(20), () -> status(CHRIS) == AgentStatus.IN_MEETING);

        ask(meeting, "\"dehren\",\"chris\"", 0.06, "Is the cap kept?");
        quiet();

        Meeting mt = office.query(c -> c.meetings().find(meeting).orElseThrow());
        List<MeetingTurn> turns = mt.rounds().getFirst().turns();
        assertThat(turns.getFirst().heard())
                .as("the first turn spends 0.0123 of 0.06: 0.0477 left, under the 0.05 a turn needs")
                .isTrue();
        assertThat(turns.getLast().why()).isEqualTo("the meeting's cost cap is reached");
        assertThat(turns.getLast().session()).as("no session ran for it").isNull();
        assertThat(promptsAsking("Is the cap kept?"))
                .as("one turn session, allowed the cap")
                .containsExactly("0.06");
        closeAndCancel(meeting, m);
    }

    /** A turn between the stop and the close does not take the stopped work session's place: --resume still runs. */
    @Test
    void aTurnLeavesTheHeldWorkSessionToBeContinuedWithResume() throws Exception {
        MissionId m = atWork("Build it [fake:meeting-long]");
        await(
                "the long command running",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.actions().of(m).stream()
                        .anyMatch(a -> "Bash".equals(a.tool()) && a.stage() == ActionStage.AUTHORIZED)));
        String meeting = call(m, "TABLE");
        await(
                "the session stopped",
                Duration.ofSeconds(40),
                () -> sessions(m).stream().noneMatch(AgentSession::isOpen));
        AgentSession stopped = sessions(m).getLast();
        String where =
                office.query(c -> c.workshop().workspace(m, CHRIS).orElseThrow().path());
        Path worktree = Path.of(where);
        transcript(worktree, stopped);

        ask(meeting, "\"chris\"", 1.0, "Where are you?");
        quiet();
        List<MeetingTurn> heard = office.query(c ->
                c.meetings().find(meeting).orElseThrow().rounds().getFirst().turns());
        assertThat(heard).singleElement().satisfies(t -> assertThat(t.heard()).isTrue());
        // Briefed with a note, the resumed session plays the usual script (the fake's stubborn one never ends).
        api().body("{\"attendee\":\"chris\",\"text\":\"Go on\"}")
                .post("/api/meetings/" + meeting + "/notes")
                .then()
                .statusCode(200);
        api().post("/api/meetings/" + meeting + "/close").then().statusCode(200);

        await(
                "the continuation asked for",
                Duration.ofSeconds(10),
                () -> journal.readAll().stream()
                        .anyMatch(e -> e.event() instanceof SessionEvent.SessionContinuationRequested r
                                && r.session().equals(stopped.id())));
        await("the next session resumes the stopped one", Duration.ofSeconds(30), () -> resumed(m));
        cancel(m);
        await(
                "chris free again",
                Duration.ofSeconds(30),
                () -> sessions(m).stream().noneMatch(AgentSession::isOpen));
    }

    /**
     * The mission ends while a turn runs: the turn is stopped and recorded before the meeting lets its speaker go, so no
     * other session of that agent can start under it (and a later close can never stop that other session).
     */
    @Test
    void aMissionEndingDuringATurnStopsTheTurnBeforeTheSpeakerIsFree() throws Exception {
        MissionId m = atWork("Describe this repository [fake:meeting]");
        String meeting = call(m, "TABLE");
        await("chris seated", Duration.ofSeconds(20), () -> status(CHRIS) == AgentStatus.IN_MEETING);
        ask(meeting, "\"chris\"", 1.0, "[fake:slow=8] Take your time?");
        await(
                "chris speaking",
                Duration.ofSeconds(20),
                () -> office.query(c -> c.sessions().of(m).stream()
                        .anyMatch(s -> s.isOpen() && s.ticket().value().endsWith(".turns"))));
        cancel(m);
        quiet();

        List<MeetingTurn> turns = office.query(c ->
                c.meetings().find(meeting).orElseThrow().rounds().getFirst().turns());
        assertThat(turns).singleElement().satisfies(t -> {
            assertThat(t.heard()).as("stopped, not left to finish its 8 s").isFalse();
            assertThat(t.budgetUsd())
                    .as("charged what it was allowed: no estimate came")
                    .isNotNull();
        });
        // The turn's session is a session of the mission: its cap is the turn's charge, counted once (ADR-0049).
        AgentSession turnSession =
                office.query(c -> c.sessions().find(turns.getFirst().session()).orElseThrow());
        assertThat(turnSession.costUsdBound()).isEqualTo(turns.getFirst().budgetUsd());
        double sessionBounds = sessions(m).stream()
                .filter(s -> s.costUsdEstimated() == null && s.costUsdBound() != null)
                .mapToDouble(AgentSession::costUsdBound)
                .sum();
        double missionBound = office.query(c -> c.verification().spentUsdBound(m));
        assertThat(missionBound)
                .as("the sessions' caps, the turn's charge not added a second time")
                .isCloseTo(sessionBounds, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(sessions(m)).noneMatch(AgentSession::isOpen);
        api().post("/api/meetings/" + meeting + "/close").then().statusCode(200);
    }

    /**
     * The close lands after a turn's start was decided but before its process registers: the process is stopped as it
     * registers, before its brief is written, and the close returns only once the round has ended.
     */
    @Test
    void aCloseBetweenATurnsStartAndItsLaunchStopsItBeforeItsBrief() throws Exception {
        MissionId m = atWork("Describe this repository [fake:meeting]");
        String meeting = call(m, "TABLE");
        await("chris seated", Duration.ofSeconds(20), () -> status(CHRIS) == AgentStatus.IN_MEETING);
        CountDownLatch decided = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        LiveSeams.beforeTurnLaunch(voices, () -> {
            decided.countDown();
            try {
                go.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            // Briefed with a note, the work session that follows the close plays the usual script and ends.
            api().body("{\"attendee\":\"chris\",\"text\":\"Go on\"}")
                    .post("/api/meetings/" + meeting + "/notes")
                    .then()
                    .statusCode(200);
            ask(meeting, "\"chris\"", 1.0, "Closed before the brief?");
            assertThat(decided.await(20, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Integer> closing =
                    CompletableFuture.supplyAsync(() -> api().post("/api/meetings/" + meeting + "/close")
                            .then()
                            .extract()
                            .statusCode());
            await(
                    "closed in the journal",
                    Duration.ofSeconds(10),
                    () -> office.query(
                            c -> !c.meetings().find(meeting).orElseThrow().open()));
            assertThat(closing).as("the close waits for the turn").isNotDone();
            go.countDown();
            assertThat(closing.get(60, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            go.countDown();
            LiveSeams.beforeTurnLaunch(voices, () -> {});
        }
        assertThat(promptsAsking("Closed before the brief?"))
                .as("no process got the brief")
                .isEmpty();
        List<MeetingTurn> turns = office.query(c ->
                c.meetings().find(meeting).orElseThrow().rounds().getFirst().turns());
        assertThat(turns).singleElement().satisfies(t -> assertThat(t.heard()).isFalse());
        cancel(m);
        await(
                "chris free again",
                Duration.ofSeconds(30),
                () -> sessions(m).stream().noneMatch(AgentSession::isOpen));
    }

    /** A daemon stopped before the turn's session was journaled: its start cannot be ruled out, so it is charged. */
    @Test
    void aRestartBeforeTheTurnsSessionWasJournaledStillChargesIt() throws Exception {
        restartMidTurn(false);
    }

    /** Stopped once the turn's session was journaled: the session is ended as interrupted and the turn charged. */
    @Test
    void aRestartAfterTheTurnsSessionWasJournaledChargesItAndEndsIt() throws Exception {
        restartMidTurn(true);
    }

    /**
     * The daemon stopped after launching the turn's process and before it named its session: only its folder is left.
     * Driven through the start-up's own order (the runtime's scan, then LiveStartup: the launches, the meetings' close
     * out, the recovery), the launch becomes the turn's session and its cap counts once, not once as a launch and once
     * as the turn's charge.
     */
    @Test
    void aRestartWithTheTurnsLaunchLeftUnnamedCountsItsCapOnce() throws Exception {
        MissionId m = atWork("Describe this repository [fake:meeting]");
        String meeting = call(m, "TABLE");
        await("chris seated", Duration.ofSeconds(20), () -> status(CHRIS) == AgentStatus.IN_MEETING);
        await(
                "chris's work session held",
                Duration.ofSeconds(20),
                () -> sessions(m).stream().noneMatch(AgentSession::isOpen));
        var director = office.context(Actor.director(), Origin.REAL);
        office.execute(c -> c.meetings().askForViews(director, meeting, List.of(CHRIS, DEHREN), 1.0, null));
        TicketId ticket =
                office.query(c -> c.meetings().find(meeting).orElseThrow().ticket());
        double allowed = Math.min(1.0, costCap.sessionCapUsd(m));
        Path folders = java.nio.file.Files.createTempDirectory("floor42-sessions");
        Path dir = java.nio.file.Files.createDirectories(folders.resolve("turn-left"));
        java.nio.file.Files.writeString(dir.resolve("process.txt"), "999999999 2026-11-01T00:00:00Z");
        java.nio.file.Files.writeString(
                dir.resolve("launch.json"),
                "{\"mission\":\"" + m.value() + "\",\"ticket\":\"" + ticket.value()
                        + "\",\"agent\":\"chris\",\"model\":\"claude-sonnet-5-5\",\"sessionId\":null,\"capUsd\":"
                        + allowed + "}");
        unnamed.scan(folders);
        try {
            LiveSeams.resumeAfterRestart(startup);

            Meeting mt = office.query(c -> c.meetings().find(meeting).orElseThrow());
            assertThat(mt.rounds().getFirst().turns().getFirst().session())
                    .as("the launch is the turn's session")
                    .isEqualTo(m.value() + ":launch-turn-left");
            double[] parts = office.query(c -> new double[] {
                c.verification().spentUsdBound(m),
                c.sessions().of(m).stream()
                        .filter(s -> !s.ticket().equals(ticket) && s.costUsdEstimated() == null)
                        .map(AgentSession::costUsdBound)
                        .filter(java.util.Objects::nonNull)
                        .mapToDouble(Double::doubleValue)
                        .sum()
            });
            assertThat(parts[0] - parts[1])
                    .as("the turn's cap, once, beside the work sessions' caps")
                    .isCloseTo(allowed, org.assertj.core.api.Assertions.within(1e-9));
            // The meeting's figure holds a charged budget: it is called charged, never estimated.
            java.util.Map<String, Object> view = api().get("/api/meetings")
                    .then()
                    .statusCode(200)
                    .extract()
                    .jsonPath()
                    .getMap("find { it.id == '" + meeting + "' }");
            assertThat(view).doesNotContainKey("spentUsdEstimated");
            assertThat(((Number) view.get("spentUsdCharged")).doubleValue()).isEqualTo(allowed);
        } finally {
            closeAndCancel(meeting, m);
        }
    }

    private void restartMidTurn(boolean journaled) throws Exception {
        MissionId m = atWork("Describe this repository [fake:meeting]");
        String meeting = call(m, "TABLE");
        await("chris seated", Duration.ofSeconds(20), () -> status(CHRIS) == AgentStatus.IN_MEETING);
        // Its work session ends at its next call, refused for the meeting (the fake's would loop on another refusal).
        await(
                "chris's work session held",
                Duration.ofSeconds(20),
                () -> sessions(m).stream().noneMatch(AgentSession::isOpen));
        var director = office.context(Actor.director(), Origin.REAL);
        // As the journal stands after a crash: asked, no round thread in this daemon.
        office.execute(c -> c.meetings().askForViews(director, meeting, List.of(CHRIS, DEHREN), 1.0, null));
        if (journaled) {
            TicketId ticket =
                    office.query(c -> c.meetings().find(meeting).orElseThrow().ticket());
            office.execute(c -> c.sessions()
                    .start(
                            office.context(Actor.system(), Origin.REAL),
                            m.value() + ":crashed-" + meeting,
                            ticket,
                            CHRIS,
                            "claude-sonnet-5-5",
                            null));
        }
        try {
            LiveSeams.closeOutRounds(voices);
            closedOut(m, meeting);
        } finally {
            closeAndCancel(meeting, m);
        }
    }

    private void closedOut(MissionId m, String meeting) {
        Meeting mt = office.query(c -> c.meetings().find(meeting).orElseThrow());
        List<MeetingTurn> turns = mt.rounds().getFirst().turns();
        assertThat(turns).extracting(MeetingTurn::why).containsOnly("the daemon stopped during the round");
        double allowed = Math.min(1.0, costCap.sessionCapUsd(m));
        assertThat(turns.getFirst().budgetUsd())
                .as("chris may have run: charged its budget")
                .isEqualTo(allowed);
        assertThat(turns.getLast().budgetUsd()).as("dehren was not reached").isNull();
        double spent = office.query(c -> c.meetings().spentUsdCharged(mt));
        assertThat(spent).isEqualTo(allowed);
        // The mission's figures: the charge is part of the one upper bound, beside the estimate, never in it, and
        // counted once: the work session held for the meeting counts for its own cap (ADR-0049).
        List<AgentSession> work = sessions(m).stream()
                .filter(s -> !s.ticket().equals(mt.ticket()))
                .toList();
        double held = work.stream()
                .map(AgentSession::costUsdBound)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();
        long heldCount = work.stream().filter(s -> s.costUsdBound() != null).count();
        double bound = office.query(c -> c.verification().spentUsdBound(m));
        assertThat(bound).isCloseTo(held + allowed, org.assertj.core.api.Assertions.within(1e-9));
        int bounded = office.query(c -> c.verification().sessionsBounded(m));
        assertThat(bounded).as("the turn once, beside the held sessions").isEqualTo((int) heldCount + 1);
        JsonPath report = api().get("/api/missions/" + m.value() + "/report")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(report.getDouble("totals.costBound.costUsd.value"))
                .isCloseTo(held + allowed, org.assertj.core.api.Assertions.within(1e-6));
        assertThat(report.getString("totals.costBound.costUsd.basis")).isEqualTo("UPPER_BOUND");
        assertThat(sessions(m))
                .as("no turn session left open")
                .noneMatch(s -> s.isOpen() && s.ticket().equals(mt.ticket()));
    }

    @Test
    void refusalsSayWhy() throws Exception {
        MissionId m = mission("Plan nothing yet");
        String meeting = api().body("{\"mission\":\"" + m.value() + "\",\"attendees\":[\"dehren\"]}")
                .post("/api/meetings")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        api().body("{\"speakers\":[\"dehren\"],\"capUsd\":1}")
                .post("/api/meetings/" + meeting + "/views")
                .then()
                .statusCode(409)
                .body("code", equalTo("MISSION_NOT_ACTIVE"));
        api().body("{\"speakers\":[\"dehren\"]}")
                .post("/api/meetings/" + meeting + "/views")
                .then()
                .statusCode(400);
        api().body("{\"mission\":\"" + m.value() + "\",\"attendees\":[\"chris\"],\"place\":\"ROOF\"}")
                .post("/api/meetings")
                .then()
                .statusCode(400);
        api().post("/api/meetings/" + meeting + "/close").then().statusCode(200);
        cancel(m);
    }

    /** The mission ends first: no work session starts again behind the close (the fake's would loop for minutes). */
    private void closeAndCancel(String meeting, MissionId m) throws InterruptedException {
        cancel(m);
        api().post("/api/meetings/" + meeting + "/close").then().statusCode(200);
        await(
                "chris free again",
                Duration.ofSeconds(30),
                () -> sessions(m).stream().noneMatch(AgentSession::isOpen));
    }

    private List<AgentSession> sessions(MissionId m) {
        return office.query(c -> c.sessions().of(m));
    }

    private AgentStatus status(AgentId a) {
        return office.query(c -> c.queries().statusOf(a));
    }

    /** The --max-budget-usd of every turn session asked this question, as the fake Claude Code received it. */
    private List<String> promptsAsking(String question) throws java.io.IOException {
        try (Stream<Path> files = Files.walk(dataDir.resolve("sessions"), 2)) {
            return files.filter(p -> p.getFileName().toString().equals("fake-prompt.txt"))
                    .filter(p -> read(p).contains("The question: " + question))
                    .map(p -> List.of(read(p.resolveSibling("fake-args.txt")).split("\n")))
                    .map(args -> args.get(args.indexOf("--max-budget-usd") + 1))
                    .toList();
        }
    }

    private boolean resumed(MissionId m) {
        try (Stream<Path> files = Files.walk(dataDir.resolve("sessions"), 2)) {
            return files.filter(p -> p.getFileName().toString().equals("fake-prompt.txt"))
                    .filter(p -> !read(p).contains("The director asks for your view.") && read(p).contains(m.value()))
                    .anyMatch(p -> read(p.resolveSibling("fake-args.txt")).contains("--resume"));
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** Claude Code's own transcript of the stopped session, where SessionContinuity looks for it (as MeetingApiTest). */
    private static void transcript(Path worktree, AgentSession s) throws java.io.IOException {
        String claudeId = s.id().substring(s.id().indexOf(':') + 1);
        String w = worktree.toString();
        Path projects = Path.of("target/test-claude-home/projects").resolve(w.replaceAll("[^A-Za-z0-9]", "-"));
        Files.createDirectories(projects);
        Files.writeString(
                projects.resolve(claudeId + ".jsonl"),
                "{\"type\":\"user\",\"cwd\":\"" + w.replace("\\", "\\\\") + "\",\"sessionId\":\"" + claudeId + "\"}\n");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (java.io.IOException e) {
            return "";
        }
    }
}
