package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.server.app.MissionScheduler;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.SchedulerQuiet;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * What the dispatcher's tests share (ADR-0019, with the fake Claude Code). Each test starts on a quiet dispatcher with
 * no usage window at risk, whatever ran before it, and leaves it so: no test counts on another's cleanup. Each one
 * creates its own mission and asserts on its own mission, tickets and agents, never on counts over the shared journal.
 */
abstract class DispatchFixture extends ApiTestBase {

    @Inject
    OfficeService office;

    @Inject
    MissionScheduler scheduler;

    @BeforeEach
    void startQuiet() throws InterruptedException {
        settle();
    }

    @AfterEach
    void leaveQuiet() throws InterruptedException {
        settle();
    }

    void settle() throws InterruptedException {
        quiet();
        windowsReset();
    }

    /** Waits until the dispatcher runs nothing and has nothing about to start: what "nothing starts" is checked at. */
    void quiet() throws InterruptedException {
        SchedulerQuiet.await(scheduler);
    }

    /** The same, leaving out the tasks the filter excludes (one resting until a plan reset, for instance). */
    void quietBut(Predicate<String> excluded) throws InterruptedException {
        SchedulerQuiet.await(scheduler, excluded.negate());
    }

    MissionId mission(Path repo, String objective, String extra) {
        return new MissionId(api().body("{\"title\":\"Dispatch\",\"objective\":\"" + objective + "\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"" + extra + "}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id"));
    }

    MissionId mission(String objective) throws Exception {
        return mission(LiveMissionTest.gitRepository(), objective, "");
    }

    void approve(MissionId m) {
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + m.value() + "/approve")
                .then()
                .statusCode(200);
    }

    void cancel(MissionId m) {
        api().body("{\"reason\":\"test done\"}").post("/api/missions/" + m.value() + "/cancel");
    }

    /** Hires a developer through the API and returns its id. */
    String hireDeveloper(String name) {
        return api().body("{\"name\":\"" + name + "\",\"role\":\"Java developer\",\"workRole\":\"DEVELOPER\","
                        + "\"model\":\"claude-sonnet-5-5\",\"effort\":\"medium\"}")
                .post("/api/agents")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }

    /** Dismisses the agent once its work has ended (its session process included). */
    void dismissWhenFree(String agent) throws InterruptedException {
        await(
                agent + " is free to leave",
                Duration.ofSeconds(30),
                () -> api().body("{\"reason\":\"test\"}")
                                .post("/api/agents/" + agent + "/dismiss")
                                .statusCode()
                        == 200);
    }

    /** Answers every open question of the mission: ALLOW for permissions, the given answer for the plan limit. */
    void answerAll(MissionId m, String planLimit) {
        List<Question> open = office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN)
                .toList());
        for (Question q : open) {
            String a = q.kind() == QuestionKind.PLAN_LIMIT ? planLimit : "ALLOW";
            if (a != null)
                api().body("{\"answer\":\"" + a + "\"}").post("/api/questions/" + q.id().value() + "/answer");
        }
    }

    /** Allows the action approvals of the mission only. */
    void allowActions(MissionId m) {
        office.query(c -> c.desk().of(m).stream()
                        .filter(q -> q.status() == QuestionStatus.OPEN && q.kind() == QuestionKind.ACTION_APPROVAL)
                        .map(q -> q.id().value())
                        .toList())
                .forEach(q -> api().body("{\"answer\":\"ALLOW\"}").post("/api/questions/" + q + "/answer"));
    }

    boolean done(MissionId m) {
        return office.query(c -> c.tickets().of(m).stream().allMatch(t -> t.status() == TicketStatus.DONE));
    }

    long planLimitQuestions(MissionId m) {
        return office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.kind() == QuestionKind.PLAN_LIMIT)
                .count());
    }

    /** No usage window at risk: one would hold every session that follows until it resets. */
    void windowsReset() throws InterruptedException {
        await(
                "the window to reset",
                Duration.ofSeconds(20),
                () -> office.query(c -> c.verification().planUsage().latest().values().stream()
                        .noneMatch(r -> r.current(Instant.now()))));
    }

    /** The agents of the sessions open on this mission now. */
    List<AgentId> workingAgents(MissionId m) {
        return office.query(c -> c.sessions().of(m).stream()
                .filter(AgentSession::isOpen)
                .map(AgentSession::agent)
                .toList());
    }

    TicketId ticket(MissionId m, String title, String objective, List<TicketId> dependsOn) {
        return ((TicketCreated) office.execute(c -> c.tickets()
                                .create(
                                        office.context(Actor.system(), Origin.REAL),
                                        m,
                                        title,
                                        objective,
                                        Priority.NORMAL,
                                        dependsOn,
                                        List.of("The report answers the objective")))
                        .getFirst()
                        .event())
                .ticket();
    }

    void assign(TicketId t, String agent) {
        office.execute(c -> c.tickets().assign(office.context(Actor.system(), Origin.REAL), t, new AgentId(agent)));
    }

    TicketStatus status(TicketId t) {
        return office.query(c -> c.tickets().find(t).orElseThrow().status());
    }

    String assignee(TicketId t) {
        return office.query(c -> c.tickets().find(t).orElseThrow().assignee().value());
    }
}
