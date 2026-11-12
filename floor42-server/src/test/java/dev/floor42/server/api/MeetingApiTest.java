package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import dev.floor42.domain.ActionStage;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.AgentStatus;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.OptionCode;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MeetingEvent;
import dev.floor42.domain.event.SessionEvent;
import dev.floor42.server.app.DirectorMeetings;
import dev.floor42.server.app.LiveMissions;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.runtime.AgentRuntime;
import dev.floor42.server.runtime.HookRegistry;
import dev.floor42.server.runtime.RuntimeEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The director's meeting (ADR-0047), with the fake Claude Code: a developer mid-session is called to the table, its next
 * tool call is refused for the meeting and its session ends without a report, a failure or a "Retry"; the director's
 * note is journaled; once the meeting closes, the developer's next session is briefed with what was said. Director
 * only: an agent's credential gets 403 on every route.
 */
@QuarkusTest
class MeetingApiTest extends DispatchFixture {

    static final AgentId CHRIS = new AgentId("chris");
    static final AgentId DEHREN = new AgentId("dehren");

    @Inject
    HookRegistry hooks;

    @Inject
    DirectorMeetings meetings;

    @Inject
    LiveMissions live;

    @Inject
    Journal journal;

    @Inject
    dev.floor42.server.runtime.RunningSessions running;

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    List<AgentSession> sessions(MissionId m) {
        return office.query(c -> c.sessions().of(m));
    }

    @Test
    void aDeveloperMidSessionIsHeldThenResumesWithTheDirectorsNoteInItsBrief() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Meeting\",\"objective\":\"Describe this repository [fake:meeting]\","
                        + "\"repository\":\"" + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        MissionId m = new MissionId(id);
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(200);
        await(
                "chris at work, reading files",
                Duration.ofSeconds(30),
                () -> sessions(m).stream().anyMatch(AgentSession::isOpen)
                        && office.query(c -> c.actions().of(m).size()) >= 2);

        api().get("/api/meetings/invitation?mission=" + id)
                .then()
                .statusCode(200)
                .body("suggested", hasItem("chris"));
        api().body("{\"mission\":\"" + id + "\",\"attendees\":[\"chris\"],\"agenda\":\"The routes\"}")
                .post("/api/meetings")
                .then()
                .statusCode(200)
                .body("open", equalTo(true))
                .body("attendees[0].id", equalTo("chris"));
        String meeting = office.query(c -> c.meetings().openOn(m).orElseThrow().id());

        await(
                "chris's session ended at its next tool call",
                Duration.ofSeconds(20),
                () -> sessions(m).stream().noneMatch(AgentSession::isOpen));
        AgentSession held = sessions(m).getLast();
        assertThat(held.failed()).as("it ended its turn itself").isFalse();
        AgentStatus status = office.query(c -> c.queries().statusOf(CHRIS));
        assertThat(status).isEqualTo(AgentStatus.IN_MEETING);
        TicketStatus ticket = office.query(c -> c.tickets().of(m).getFirst().status());
        assertThat(ticket).as("no report, no review: the ticket waits").isEqualTo(TicketStatus.IN_PROGRESS);
        boolean noRetry = office.query(c -> c.desk().of(m).stream()
                .noneMatch(q -> q.status() == QuestionStatus.OPEN && q.offers(OptionCode.RETRY)));
        assertThat(noRetry).as("nothing failed: no Retry").isTrue();
        quiet();
        assertThat(sessions(m)).as("no new step while the meeting is open").hasSize(1);

        String note = "Add the health route " + Instant.now().toEpochMilli();
        api().body("{\"attendee\":\"chris\",\"text\":\"" + note + "\"}")
                .post("/api/meetings/" + meeting + "/notes")
                .then()
                .statusCode(200)
                .body("notes", hasSize(1))
                .body("notes[0].attendee", equalTo("chris"));
        refusals(id, meeting);
        agentCredentialRefused(id, meeting);

        api().post("/api/meetings/" + meeting + "/close").then().statusCode(200).body("open", equalTo(false));
        await("chris back at work, briefed with the note", Duration.ofSeconds(30), () -> briefWith(note));
        assertThat(sessions(m)).as("a new session after the meeting").hasSizeGreaterThanOrEqualTo(2);
        journaled(m, meeting, note);
        // The shared journal's later tests need chris free: the mission ends here, its session with it.
        api().body("{\"reason\":\"test over\"}")
                .post("/api/missions/" + id + "/cancel")
                .then()
                .statusCode(200);
        await(
                "chris free again",
                Duration.ofSeconds(30),
                () -> sessions(m).stream().noneMatch(AgentSession::isOpen));
    }

    /**
     * After a restart the journal says a meeting is open but no reservation is in memory. Whatever observer of the
     * start-up runs first (the retrospectives' recovery, the staffing reviews'), no reservation takes an attendee: the
     * refusal reads the journal. The start-up then holds them again; the close frees them.
     */
    @Test
    void anOpenMeetingHoldsItsAttendeesAgainAfterARestart() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        MissionId m = mission(repo, "Plan nothing yet", "");
        // As the journal stands after a restart: called, never gathered by this daemon.
        office.execute(c -> c.meetings()
                .call(
                        office.context(dev.floor42.domain.Actor.director(), dev.floor42.domain.Origin.REAL),
                        m,
                        List.of(DEHREN),
                        null));
        String meeting = office.query(c -> c.meetings().openOn(m).orElseThrow().id());
        assertThat(dev.floor42.server.app.LiveSeams.tryReserve(live, DEHREN))
                .as("refused from the journal alone, before any regathering")
                .isEmpty();

        meetings.regather();

        assertThat(meetings.holds(meeting, DEHREN)).isTrue();
        assertThat(dev.floor42.server.app.LiveSeams.tryReserve(live, DEHREN))
                .as("a retrospective or a staffing review cannot take it")
                .isEmpty();
        api().post("/api/meetings/" + meeting + "/close").then().statusCode(200);
        var after = dev.floor42.server.app.LiveSeams.tryReserve(live, DEHREN);
        assertThat(after).as("free again after the close").isPresent();
        live.release(after.get());
    }

    /** The start-up regathers the meetings before the retrospectives' and staffing reviews' own observers run. */
    @Test
    void theStartUpHoldsTheMeetingsBeforeTheOtherObserversRun() throws Exception {
        int live = priorityOf(dev.floor42.server.app.LiveStartup.class);
        assertThat(live).isLessThan(jakarta.enterprise.inject.spi.ObserverMethod.DEFAULT_PRIORITY);
        assertThat(priorityOf(dev.floor42.server.app.Retrospectives.class))
                .isEqualTo(jakarta.enterprise.inject.spi.ObserverMethod.DEFAULT_PRIORITY);
        assertThat(priorityOf(dev.floor42.server.app.Staffing.class))
                .isEqualTo(jakarta.enterprise.inject.spi.ObserverMethod.DEFAULT_PRIORITY);
        // First of all: the last daemon's leftovers are stopped before the recovery relaunches any session.
        assertThat(priorityOf(dev.floor42.server.runtime.ClaudeCodeRuntime.class, "onStart"))
                .isLessThan(live);
    }

    private static int priorityOf(Class<?> bean) throws NoSuchMethodException {
        return priorityOf(bean, "listen");
    }

    private static int priorityOf(Class<?> bean, String method) throws NoSuchMethodException {
        var p = bean.getDeclaredMethod(method, io.quarkus.runtime.StartupEvent.class)
                .getParameters()[0];
        var priority = p.getAnnotation(jakarta.annotation.Priority.class);
        return priority == null ? jakarta.enterprise.inject.spi.ObserverMethod.DEFAULT_PRIORITY : priority.value();
    }

    /**
     * A session that ignores the refusal (it asks again and again) is stopped after the wind-down, but only between
     * two calls: its long shell command, running when the meeting is called, ends first and writes its file; the
     * worktree keeps the work in progress. At the close, Floor42 asks for --resume (measured safe once Claude Code's
     * transcript is there) and the next session continues the stopped one, briefed with the note.
     */
    @Test
    void aSessionIgnoringTheRefusalIsStoppedBetweenTwoCallsThenContinuedWithResume() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        MissionId m = mission(repo, "Build it [fake:meeting-long]", "");
        api().body("{\"decision\":\"ALLOW\"}")
                .put("/api/missions/" + m.value() + "/tools/Bash")
                .then()
                .statusCode(200);
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + m.value() + "/approve")
                .then()
                .statusCode(200);
        await("the long shell command running", Duration.ofSeconds(30), () -> bashAt(m, ActionStage.AUTHORIZED));
        String where =
                office.query(c -> c.workshop().workspace(m, CHRIS).orElseThrow().path());
        Path worktree = Path.of(where);
        Files.writeString(worktree.resolve("wip.txt"), "work in progress\n");

        api().body("{\"mission\":\"" + m.value() + "\",\"attendees\":[\"chris\"]}")
                .post("/api/meetings")
                .then()
                .statusCode(200);
        String meeting = office.query(c -> c.meetings().openOn(m).orElseThrow().id());
        await(
                "the session stopped",
                Duration.ofSeconds(40),
                () -> sessions(m).stream().noneMatch(AgentSession::isOpen));

        assertThat(worktree.resolve("long-done.txt"))
                .as("the call under way ran to its end")
                .exists();
        assertThat(bashAt(m, ActionStage.EXECUTED))
                .as("and its result was recorded")
                .isTrue();
        assertThat(Files.readString(worktree.resolve("wip.txt"))).isEqualTo("work in progress\n");
        AgentSession stopped = sessions(m).getLast();
        assertThat(stopped.failed()).as("stopped by Floor42").isTrue();
        boolean noRetry = office.query(c -> c.desk().of(m).stream()
                .noneMatch(q -> q.status() == QuestionStatus.OPEN && q.offers(OptionCode.RETRY)));
        assertThat(noRetry).as("no Retry: the meeting stopped it").isTrue();

        transcript(worktree, stopped);
        String note = "Keep the build green " + Instant.now().toEpochMilli();
        api().body("{\"attendee\":\"chris\",\"text\":\"" + note + "\"}")
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
        await("the next session resumes the stopped one", Duration.ofSeconds(30), () -> resumedWith(note));
        api().body("{\"reason\":\"test over\"}")
                .post("/api/missions/" + m.value() + "/cancel")
                .then()
                .statusCode(200);
        await(
                "chris free again",
                Duration.ofSeconds(30),
                () -> sessions(m).stream().noneMatch(AgentSession::isOpen));
    }

    /**
     * The shell command's result never comes (the session goes on talking): the call counts as running only while it
     * shows signs of life, so the session is still stopped, between two calls, once it has been silent past the bound.
     */
    @Test
    void aCallWhoseResultNeverComesNoLongerKeepsTheSessionFromBeingStopped() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        MissionId m = mission(repo, "Build it [fake:meeting-lost]", "");
        api().body("{\"decision\":\"ALLOW\"}")
                .put("/api/missions/" + m.value() + "/tools/Bash")
                .then()
                .statusCode(200);
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + m.value() + "/approve")
                .then()
                .statusCode(200);
        await("the shell command started", Duration.ofSeconds(30), () -> bashAt(m, ActionStage.AUTHORIZED));

        api().body("{\"mission\":\"" + m.value() + "\",\"attendees\":[\"chris\"]}")
                .post("/api/meetings")
                .then()
                .statusCode(200);
        String meeting = office.query(c -> c.meetings().openOn(m).orElseThrow().id());
        await(
                "the session stopped",
                Duration.ofSeconds(40),
                () -> sessions(m).stream().noneMatch(AgentSession::isOpen));

        AgentSession stopped = sessions(m).getLast();
        assertThat(stopped.failed()).as("stopped by Floor42").isTrue();
        assertThat(bashAt(m, ActionStage.EXECUTED))
                .as("the lost call never reported a result")
                .isFalse();
        // Stopped for the meeting before its result: no cost reported, so the cap it ran under counts, as a bound.
        assertThat(stopped.costUsdEstimated()).isNull();
        assertThat(stopped.costUsdBound()).isPositive().isEqualTo(stopped.capUsd());
        double bound = office.query(c -> c.verification().spentUsdBound(m));
        assertThat(bound).isGreaterThanOrEqualTo(stopped.costUsdBound());
        api().post("/api/meetings/" + meeting + "/close").then().statusCode(200);
        api().body("{\"reason\":\"test over\"}")
                .post("/api/missions/" + m.value() + "/cancel")
                .then()
                .statusCode(200);
        await(
                "chris free again",
                Duration.ofSeconds(30),
                () -> sessions(m).stream().noneMatch(AgentSession::isOpen));
    }

    /**
     * A session the gate does not hold (a retrospective's turn, a staffing review: they hold their agent with a
     * reservation) is never stopped for a meeting, whatever the wind-down: the meeting waits for its agent instead.
     */
    @Test
    void aSessionOutsideTheGateIsNeverStoppedTheMeetingWaitsForIt() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        MissionId m = mission(repo, "Plan nothing yet", "");
        var retro = dev.floor42.server.app.LiveSeams.tryReserve(live, DEHREN).orElseThrow();
        Process turn = new ProcessBuilder(
                        System.getProperty("os.name").startsWith("Windows")
                                ? List.of("ping", "-n", "60", "127.0.0.1")
                                : List.of("sleep", "60"))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        dev.floor42.server.runtime.RunningSessionsAccess.started(running, DEHREN, turn);
        try {
            api().body("{\"mission\":\"" + m.value() + "\",\"attendees\":[\"dehren\"]}")
                    .post("/api/meetings")
                    .then()
                    .statusCode(200);
            String meeting =
                    office.query(c -> c.meetings().openOn(m).orElseThrow().id());
            Instant past = Instant.now().plus(Duration.ofSeconds(5));
            await(
                    "past the wind-down (3 s in tests)",
                    Duration.ofSeconds(10),
                    () -> Instant.now().isAfter(past));

            assertThat(turn.isAlive()).as("its turn was not cut").isTrue();
            assertThat(meetings.holds(meeting, DEHREN))
                    .as("the meeting waits for it")
                    .isFalse();
            live.release(retro);
            await(
                    "the meeting holds it once its turn is over",
                    Duration.ofSeconds(10),
                    () -> meetings.holds(meeting, DEHREN));
            api().post("/api/meetings/" + meeting + "/close").then().statusCode(200);
        } finally {
            dev.floor42.server.runtime.RunningSessionsAccess.ended(running, DEHREN, turn);
            turn.destroyForcibly();
        }
    }

    /** A mission cancelled during its meeting frees the attendees at once: the meeting's hold goes with it. */
    @Test
    void aMissionEndingDuringItsMeetingFreesTheAttendees() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        MissionId m = mission(repo, "Plan nothing yet", "");
        api().body("{\"mission\":\"" + m.value() + "\",\"attendees\":[\"dehren\"]}")
                .post("/api/meetings")
                .then()
                .statusCode(200);
        String meeting = office.query(c -> c.meetings().openOn(m).orElseThrow().id());
        await("dehren held", Duration.ofSeconds(10), () -> meetings.holds(meeting, DEHREN));

        api().body("{\"reason\":\"test over\"}")
                .post("/api/missions/" + m.value() + "/cancel")
                .then()
                .statusCode(200);

        await("its hold released", Duration.ofSeconds(10), () -> !meetings.holds(meeting, DEHREN));
        var free = dev.floor42.server.app.LiveSeams.tryReserve(live, DEHREN);
        assertThat(free).as("free for other work").isPresent();
        live.release(free.get());
        api().post("/api/meetings/" + meeting + "/close").then().statusCode(200);
    }

    /** A planner writing a staffing review is not called: its review is never cut, and never given up for a meeting. */
    @Test
    void aPlannerWritingAStaffingReviewIsNotCalled() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        // The staffing brief names the mission's title: the planner's session takes 4 s, long enough to be at work.
        MissionId m = new MissionId(api().body("{\"title\":\"Staff [fake:slow=4]\",\"objective\":\"Write the notes\","
                        + "\"repository\":\"" + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .<String>path("id"));
        await(
                "the plan proposed",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.missions().find(m).orElseThrow().status()
                        == dev.floor42.domain.MissionStatus.AWAITING_APPROVAL));
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + m.value() + "/approve")
                .then()
                .statusCode(200);
        api().post("/api/missions/" + m.value() + "/staffing").then().statusCode(200);

        api().body("{\"mission\":\"" + m.value() + "\",\"attendees\":[\"geoffrey\"]}")
                .post("/api/meetings")
                .then()
                .statusCode(409)
                .body("code", equalTo("AGENT_IN_STAFFING"));
        await(
                "the review advised",
                Duration.ofSeconds(60),
                () -> office.query(
                        c -> c.staff().staffingReview(m).map(r -> r.advised()).orElse(false)));
        api().body("{\"reason\":\"test over\"}")
                .post("/api/missions/" + m.value() + "/cancel")
                .then()
                .statusCode(200);
        quiet();
    }

    private boolean bashAt(MissionId m, ActionStage stage) {
        return office.query(c ->
                c.actions().of(m).stream().anyMatch(a -> "Bash".equals(a.tool()) && a.stage() == stage));
    }

    /** Claude Code's own transcript of the stopped session, where SessionContinuity looks for it (as ResumeApiTest). */
    private static void transcript(Path worktree, AgentSession s) throws java.io.IOException {
        String claudeId = s.id().substring(s.id().indexOf(':') + 1);
        String w = worktree.toString();
        Path projects = Path.of("target/test-claude-home/projects").resolve(w.replaceAll("[^A-Za-z0-9]", "-"));
        Files.createDirectories(projects);
        Files.writeString(
                projects.resolve(claudeId + ".jsonl"),
                "{\"type\":\"user\",\"cwd\":\"" + w.replace("\\", "\\\\") + "\",\"sessionId\":\"" + claudeId + "\"}\n");
    }

    /** A session started with --resume, its brief restating the note. */
    private boolean resumedWith(String note) {
        try (Stream<Path> files = Files.walk(dataDir.resolve("sessions"), 2)) {
            return files.filter(p -> p.getFileName().toString().equals("fake-prompt.txt"))
                    .filter(p -> read(p).contains("to you: " + note))
                    .anyMatch(p -> read(p.resolveSibling("fake-args.txt")).contains("--resume"));
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private void refusals(String mission, String meeting) {
        api().body("{\"mission\":\"" + mission + "\",\"attendees\":[\"dehren\"]}")
                .post("/api/meetings")
                .then()
                .statusCode(409)
                .body("code", equalTo("MEETING_OPEN"));
        api().body("{\"attendee\":\"dehren\",\"text\":\"x\"}")
                .post("/api/meetings/" + meeting + "/notes")
                .then()
                .statusCode(409)
                .body("code", equalTo("NOT_AN_ATTENDEE"));
        api().body("{\"text\":\"x\",\"use\":\"LOUDLY\"}")
                .post("/api/meetings/" + meeting + "/notes")
                .then()
                .statusCode(400);
        api().post("/api/meetings/MT-999/close").then().statusCode(404).body("code", equalTo("UNKNOWN_MEETING"));
    }

    private void agentCredentialRefused(String mission, String meeting) {
        String credential = hooks.register(new AgentRuntime.Governor() {
            @Override
            public AgentRuntime.Verdict decide(RuntimeEvent.ToolRequested call) {
                return AgentRuntime.Verdict.DENY;
            }

            @Override
            public String violationNow(dev.floor42.server.runtime.RuntimeEvent.ToolRequested call) {
                return null;
            }

            @Override
            public void completed(RuntimeEvent.ToolCompleted result) {}
        });
        try {
            RequestSpecification agent =
                    given().header("Authorization", "Bearer " + credential).contentType("application/json");
            agent.get("/api/meetings").then().statusCode(403);
            given().header("Authorization", "Bearer " + credential)
                    .contentType("application/json")
                    .body("{\"mission\":\"" + mission + "\",\"attendees\":[\"chris\"]}")
                    .post("/api/meetings")
                    .then()
                    .statusCode(403);
            given().header("Authorization", "Bearer " + credential)
                    .contentType("application/json")
                    .body("{\"text\":\"x\"}")
                    .post("/api/meetings/" + meeting + "/notes")
                    .then()
                    .statusCode(403);
            given().header("Authorization", "Bearer " + credential)
                    .post("/api/meetings/" + meeting + "/close")
                    .then()
                    .statusCode(403);
        } finally {
            hooks.unregister(credential);
        }
    }

    /** A session's brief, as the fake Claude Code received it, restates the note under the meeting's heading. */
    private boolean briefWith(String note) {
        try (Stream<Path> files = Files.walk(dataDir.resolve("sessions"), 2)) {
            return files.filter(p -> p.getFileName().toString().equals("fake-prompt.txt"))
                    .map(MeetingApiTest::read)
                    .anyMatch(b -> b.contains("Director's meeting:") && b.contains("to you: " + note));
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private void journaled(MissionId m, String meeting, String note) {
        List<EventEnvelope> mine = journal.readAll().stream()
                .filter(e -> m.equals(e.mission()))
                .filter(e -> e.event() instanceof MeetingEvent)
                .toList();
        assertThat(mine)
                .extracting(e -> e.event().getClass().getSimpleName())
                .containsExactly("MeetingCalled", "MeetingNoteGiven", "MeetingClosed");
        assertThat(mine).allSatisfy(e -> {
            assertThat(e.origin()).isEqualTo(dev.floor42.domain.Origin.REAL);
            assertThat(e.actor().isDirector()).isTrue();
        });
        assertThat(mine.get(1).event()).isEqualTo(new MeetingEvent.MeetingNoteGiven(meeting, CHRIS, note));
        assertThat(mine.getLast().event()).isEqualTo(new MeetingEvent.MeetingClosed(meeting, m, List.of(CHRIS)));
    }
}
