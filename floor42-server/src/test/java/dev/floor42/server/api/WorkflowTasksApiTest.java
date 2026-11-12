package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.AgentSession;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.event.WorkflowEvent.WorkflowPinned;
import dev.floor42.domain.event.WorkflowEvent.WorkflowStepTicketOpened;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The director's tasks end to end (ADR-0061, increment 4 of ADR-0052), with the fake Claude Code: a workflow of one
 * task opens on the approval with no ticket, the approval opens the task's ticket and links it, and the task is
 * worked by a session of its role. The writer's preview is on in tests, as for the checks.
 */
@QuarkusTest
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class WorkflowTasksApiTest {

    @Inject
    SessionToken token;

    @Inject
    OfficeService office;

    @Inject
    Journal journal;

    RequestSpecification api() {
        return given().header("Authorization", "Bearer " + token.value()).contentType("application/json");
    }

    static String task(String key, String slot, String runBy, boolean reviewed) {
        return "{\"step\":\"task\",\"key\":\"" + key + "\",\"title\":\"Step " + key
                + "\",\"does\":\"Do what the objective says.\","
                + "\"why\":\"The director asked for it\",\"handsOver\":\"A report\",\"slot\":\"" + slot
                + "\",\"runBy\":\"" + runBy + "\",\"reviewed\":" + reviewed + "}";
    }

    static String save(String name, String steps) {
        return "{\"name\":\"" + name + "\",\"steps\":[" + steps + "]}";
    }

    MissionId start(String steps, String plan) throws Exception {
        String name = "Tasks " + UUID.randomUUID().toString().substring(0, 6);
        api().body(save(name, steps)).post("/api/workflows").then().statusCode(200);
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Tasks\",\"objective\":\"Describe this repository\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\",\"plan\":" + plan + ",\"workflow\":\"" + name
                        + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        return new MissionId(id);
    }

    void approve(MissionId m) {
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + m.value() + "/approve")
                .then()
                .statusCode(200);
    }

    List<Ticket> tickets(MissionId m) {
        return office.query(c -> c.tickets().of(m));
    }

    /** Answers what the sessions ask the director, until the condition holds. */
    void untilDone(MissionId m, java.util.function.BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            String q = office.query(c -> c.desk().of(m).stream()
                    .filter(x -> x.status() == QuestionStatus.OPEN)
                    .map(x -> x.id().value())
                    .findFirst()
                    .orElse(null));
            if (q != null) api().body("{\"answer\":\"ALLOW\"}").post("/api/questions/" + q + "/answer");
            Thread.sleep(50);
        }
        if (!done.getAsBoolean()) {
            String state = office.query(c -> c.tickets().of(m).stream()
                            .map(t -> t.id() + " " + t.status() + " by " + t.assignee() + " reviews "
                                    + t.reviews().size())
                            .toList()
                    + " questions "
                    + c.desk().of(m).stream()
                            .map(q -> q.status() + ": " + q.id())
                            .toList()
                    + " sessions " + c.sessions().of(m).size());
            throw new AssertionError("The tasks did not end: " + state);
        }
    }

    @Test
    void aResearcherTaskOpensOnTheApprovalWithNoTicketAndIsDoneWithItsReport() throws Exception {
        MissionId m = start(
                "{\"step\":\"approval\"}," + task("research", "beforeAcceptance", "researcher", false)
                        + ",{\"step\":\"accept\"}",
                "false");
        // The table keeps the web closed by default and the step never widens it: the director opens it.
        for (String tool : List.of("WebSearch", "WebFetch")) {
            api().body("{\"decision\":\"ALLOW\"}")
                    .put("/api/missions/" + m.value() + "/tools/" + tool)
                    .then()
                    .statusCode(200);
        }
        assertThat(tickets(m)).as("the plan is submitted with no ticket").isEmpty();
        MissionStatus status =
                office.query(c -> c.missions().find(m).orElseThrow().status());
        assertThat(status).isEqualTo(MissionStatus.AWAITING_APPROVAL);
        assertThat(journal.readAll().stream()
                        .filter(e -> e.event() instanceof WorkflowPinned p
                                && p.mission().equals(m))
                        .map(e -> ((WorkflowPinned) e.event()).schemaVersion())
                        .toList())
                .containsExactly(3);

        approve(m);
        untilDone(
                m,
                () -> tickets(m).stream().allMatch(t -> t.status() == TicketStatus.DONE)
                        && !tickets(m).isEmpty());

        Ticket t = tickets(m).getFirst();
        assertThat(journal.readAll().stream()
                        .map(e -> e.event())
                        .filter(e -> e instanceof WorkflowStepTicketOpened o
                                && o.mission().equals(m))
                        .map(e -> ((WorkflowStepTicketOpened) e).step())
                        .toList())
                .containsExactly("research");
        assertThat(t.reviews()).as("an unreviewed task is done with its report").isEmpty();
        assertThat(t.deliverable()).isNotBlank();
        List<AgentSession> sessions = office.query(c -> c.sessions().of(m));
        assertThat(sessions).hasSize(1);
    }

    @Test
    void aReviewerTaskIsReadByAnotherReviewerBeforeItIsDone() throws Exception {
        MissionId m = start(
                "{\"step\":\"approval\"}," + task("audit", "beforeAcceptance", "reviewer", true)
                        + ",{\"step\":\"accept\"}",
                "false");
        // A reviewer's task is read by another reviewer: the default staff has one, so a second one is hired.
        api().body("{\"name\":\"Rey\",\"workRole\":\"REVIEWER\",\"model\":\"claude-haiku-5-5\"}")
                .post("/api/agents")
                .then()
                .statusCode(200);
        approve(m);
        untilDone(m, () -> !tickets(m).isEmpty() && tickets(m).getFirst().status() == TicketStatus.DONE);

        Ticket t = tickets(m).getFirst();
        assertThat(t.reviews()).hasSize(1);
        assertThat(t.reviews().getFirst().reviewer()).isNotEqualTo(t.assignee());
        List<AgentSession> sessions = office.query(c -> c.sessions().of(m));
        assertThat(sessions).hasSize(2);
    }

    @Test
    void aDeveloperTaskBeforeProductionRunsFirstAndIsAlwaysReviewed() throws Exception {
        MissionId m = start(
                "{\"step\":\"plan\"},{\"step\":\"approval\"}," + task("design", "beforeProduction", "developer", true)
                        + ",{\"step\":\"produce\"},{\"step\":\"review\"},{\"step\":\"accept\"}",
                "false");
        approve(m);
        untilDone(m, () -> {
            List<Ticket> all = tickets(m);
            return all.size() == 2 && all.stream().allMatch(t -> t.status() == TicketStatus.DONE);
        });
        List<Ticket> all = tickets(m);
        Ticket design = all.stream()
                .filter(t -> t.title().equals("Step design"))
                .findFirst()
                .orElseThrow();
        Ticket delivery = all.stream()
                .filter(t -> !t.title().equals("Step design"))
                .findFirst()
                .orElseThrow();
        assertThat(design.reviews()).as("a developer's task is reviewed").hasSize(1);
        assertThat(office.query(c -> c.sessions().of(m)).stream()
                        .filter(s -> s.ticket().equals(design.id()))
                        .map(AgentSession::startedAt)
                        .min(java.util.Comparator.naturalOrder())
                        .orElseThrow())
                .as("the design step started before the production of the delivery")
                .isBeforeOrEqualTo(office.query(c -> c.sessions().of(m)).stream()
                        .filter(s -> s.ticket().equals(delivery.id()))
                        .map(AgentSession::startedAt)
                        .min(java.util.Comparator.naturalOrder())
                        .orElseThrow());
    }

    @Inject
    dev.floor42.server.app.StallDiagnosis stalls;

    /** A reviewed task of a reviewer with the default staff (one reviewer): the stall says what to do. */
    @Test
    @org.junit.jupiter.api.Order(1)
    void aReviewedReviewerTaskWithOneReviewerOnStaffNamesTheSecondReviewerTheMissionNeeds() throws Exception {
        MissionId m = start(
                "{\"step\":\"approval\"}," + task("audit", "beforeAcceptance", "reviewer", true)
                        + ",{\"step\":\"accept\"}",
                "false");
        long reviewers = office.query(c -> c.staff().all().stream()
                .filter(a -> !a.dismissed()
                        && dev.floor42.server.app.Staff.roleOf(a) == dev.floor42.domain.WorkRole.REVIEWER)
                .count());
        org.junit.jupiter.api.Assumptions.assumeTrue(
                reviewers == 1, "another test of this daemon already hired a second reviewer");
        approve(m);
        await(
                "the task in review",
                Duration.ofSeconds(40),
                () -> !tickets(m).isEmpty() && tickets(m).getFirst().status() == TicketStatus.IN_REVIEW);
        await(
                "the stall",
                Duration.ofSeconds(10),
                () -> "NEEDS_SECOND_REVIEWER"
                        .equals(stalls.stall(m, java.time.Instant.now()).reason()));
        assertThat(stalls.stall(m, java.time.Instant.now()).ticket())
                .isEqualTo(tickets(m).getFirst().id().value());
    }

    /** The table keeps the web closed: one clear question, nothing started. */
    @Test
    void aResearcherTaskWithTheDefaultTableAsksOneClearQuestionBeforeAnythingStarts() throws Exception {
        MissionId m = start(
                "{\"step\":\"approval\"}," + task("research", "beforeAcceptance", "researcher", false)
                        + ",{\"step\":\"accept\"}",
                "false");
        approve(m);
        await("the question", Duration.ofSeconds(40), () -> noToolQuestions(m) > 0);
        Thread.sleep(1500);
        assertThat(tickets(m).getFirst().status())
                .as("the ticket is not started")
                .isEqualTo(TicketStatus.TODO);
        assertThat(tickets(m).getFirst().assignee()).isNull();
        List<AgentSession> none = office.query(c -> c.sessions().of(m));
        assertThat(none).isEmpty();
        assertThat(noToolQuestions(m)).as("asked once").isEqualTo(1L);
    }

    long noToolQuestions(MissionId m) {
        return office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN
                        && q.phrase() != null
                        && q.phrase().code().equals("noToolForStep"))
                .count());
    }

    /** What a task session is offered never exceeds the mission's table, for each runner. */
    @Test
    void theToolsOfATaskSessionNeverExceedTheMissionTable() throws Exception {
        MissionId m = start(
                "{\"step\":\"approval\"}," + task("audit", "beforeAcceptance", "reviewer", true)
                        + ",{\"step\":\"accept\"}",
                "false");
        dev.floor42.domain.AgentId anyone = new dev.floor42.domain.AgentId("someone");
        List<String> offered = office.query(c ->
                dev.floor42.server.app.Briefs.offeredTools(c.missions().find(m).orElseThrow()));
        for (dev.floor42.domain.WorkRole role : List.of(
                dev.floor42.domain.WorkRole.DEVELOPER,
                dev.floor42.domain.WorkRole.REVIEWER,
                dev.floor42.domain.WorkRole.RESEARCHER)) {
            dev.floor42.domain.workflow.Workflow.Step.Task step = new dev.floor42.domain.workflow.Workflow.Step.Task(
                    "k",
                    "T",
                    "d",
                    "w",
                    "h",
                    dev.floor42.domain.workflow.Workflow.Step.Task.Slot.BEFORE_ACCEPTANCE,
                    role,
                    true);
            List<String> tools = office.query(c -> dev.floor42.server.app.StepTaskRunner.tools(
                    c.missions().find(m).orElseThrow(), null, anyone, step));
            if (role == dev.floor42.domain.WorkRole.DEVELOPER) {
                assertThat(tools).isSubsetOf(offered);
            } else if (role == dev.floor42.domain.WorkRole.REVIEWER) {
                assertThat(tools).isSubsetOf(offered).isSubsetOf(dev.floor42.server.app.ReviewRunner.REVIEW_TOOLS);
                assertThat(tools).doesNotContain("Write", "Edit", "Bash");
            } else {
                assertThat(tools).as("the web is closed by the table").isEmpty();
            }
        }
    }

    /** The templates of tasks are offered now that the writer is open. */
    @Test
    void theTemplatesOfTasksAreOffered() {
        List<String> keys = api().get("/api/workflows").path("templates.key");
        assertThat(keys).contains("research", "review-only");
        Integer writes = api().get("/api/workflows").path("writesSchema");
        assertThat(writes).isEqualTo(4);
    }
}
