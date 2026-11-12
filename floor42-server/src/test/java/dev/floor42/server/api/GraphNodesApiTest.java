package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.CheckRun;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.NodePlan;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketKind;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.event.WorkflowEvent.NodePlanned;
import dev.floor42.server.app.BoundedProcess;
import dev.floor42.server.app.ProofShell;
import dev.floor42.server.app.ProofSnapshot;
import dev.floor42.server.journal.Journal;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The graph mode's second increment (ADR-0062) through the API, with the fake Claude Code: what the approval settles
 * ({@code NodePlanned}), the node tests Floor42 runs in the sandbox (a fake shell stands for it) and what a failure
 * counts for, the graph's extension, and the narrow-scope refusal.
 */
@QuarkusTest
class GraphNodesApiTest extends DispatchFixture {

    @Inject
    ProofShell shell;

    @Inject
    dev.floor42.server.runtime.Sandbox sandbox;

    @Inject
    Journal journal;

    final List<String> ran = new CopyOnWriteArrayList<>();
    final List<Path> dirs = new CopyOnWriteArrayList<>();

    @AfterEach
    void restore() {
        shell.restore();
        sandbox("off");
    }

    void sandbox(String value) {
        // A fake shell stands for the sandbox: it works wherever the test runs, WSL or not (no real probe).
        // The setting first: switching it on measures again (SettingsResource), which would drop the assumption.
        api().body("{\"value\":\"" + value + "\"}").put("/api/settings/SHELL_SANDBOX");
        dev.floor42.server.runtime.SandboxSeams.assume(sandbox, "wsl".equals(value) ? true : null);
    }

    MissionId planned() throws Exception {
        MissionId m = new MissionId(api().body("{\"title\":\"Graph\",\"objective\":\"Describe this repository"
                        + " [fake:graph-plan]\",\"repository\":\""
                        + LiveMissionTest.gitRepository().toString().replace("\\", "\\\\")
                        + "\",\"plan\":true,\"mode\":\"graph\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id"));
        approve(m);
        try {
            await(
                    "the plan",
                    Duration.ofSeconds(60),
                    () -> office.query(c -> c.tickets().of(m).size() == 3));
        } catch (AssertionError e) {
            String state = office.query(c -> c.tickets().of(m).stream()
                            .map(t -> t.id() + " " + t.status())
                            .toList()
                    + " questions "
                    + c.desk().of(m).stream()
                            .map(q -> q.status() + ": " + q.id())
                            .toList()
                    + " sessions " + c.sessions().of(m).size());
            throw new AssertionError(e.getMessage() + ": " + state, e);
        }
        return m;
    }

    Ticket node(MissionId m, int n) {
        return office.query(c -> c.tickets().of(m).get(n));
    }

    void approveNodes(MissionId m, String body) {
        api().body(body)
                .post("/api/missions/" + m.value() + "/tickets/approve")
                .then()
                .statusCode(200);
    }

    List<CheckRun> nodeRuns(MissionId m, Ticket t) {
        return office.query(c -> c.verification().checkRuns(m).stream()
                .filter(r -> r.nodeTest().filter(t.id()::equals).isPresent())
                .toList());
    }

    Question rejectedTimes(MissionId m) {
        return office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN
                        && q.phrase() != null
                        && q.phrase().code().equals("rejectedTimes"))
                .findFirst()
                .orElse(null));
    }

    ProofShell.Runner answering(int exit, String output) {
        return (dir, command) -> {
            ran.add(command);
            dirs.add(dir);
            return new BoundedProcess.Ran(exit, false, 4, output);
        };
    }

    @Test
    void theApprovalPlansEachNodeAndTheDirectorsRiskIsFinal() throws Exception {
        MissionId m = planned();
        Ticket a = node(m, 1);
        Ticket b = node(m, 2);
        assertThat(a.description()).contains("Risk: low").contains("Tier: light");
        api().body("{\"tickets\":[\"" + a.id().value() + "\"],\"risks\":{\"" + a.id().value() + "\":\"medium\"}}")
                .post("/api/missions/" + m.value() + "/tickets/approve")
                .then()
                .statusCode(400);

        approveNodes(
                m,
                "{\"tickets\":[\"" + a.id().value() + "\",\"" + b.id().value() + "\"],\"risks\":{\"" + b.id().value()
                        + "\":\"low\"}}");

        NodePlan first = office.query(c -> c.workflows().nodeOf(a.id()).orElseThrow());
        NodePlan second = office.query(c -> c.workflows().nodeOf(b.id()).orElseThrow());
        assertThat(first).isEqualTo(new NodePlan(NodePlan.Risk.LOW, NodePlan.Tier.LIGHT, List.of("cat SUMMARY.md")));
        assertThat(second.risk()).as("the planner said high, the director low").isEqualTo(NodePlan.Risk.LOW);
        assertThat(second.tier()).isEqualTo(NodePlan.Tier.STANDARD);
        String description =
                office.query(c -> c.tickets().find(a.id()).orElseThrow().description());
        assertThat(description)
                .as("the notes left the description")
                .doesNotContain("Risk:")
                .doesNotContain("Tier:");
        assertThat(journal.readAll().stream()
                        .map(e -> e.event())
                        .filter(NodePlanned.class::isInstance)
                        .map(e -> ((NodePlanned) e).ticket())
                        .filter(t -> t.equals(a.id()) || t.equals(b.id())))
                .containsExactlyInAnyOrder(a.id(), b.id());
        JsonPath view = api().get("/api/missions/" + m.value())
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(view.getString("tickets.find { it.id == '" + b.id().value() + "' }.node.risk"))
                .isEqualTo("low");
        assertThat(view.getList("tickets.find { it.id == '" + a.id().value() + "' }.node.tests"))
                .containsExactly("cat SUMMARY.md");
        cancel(m);
    }

    @Test
    void aFailingNodeTestIsARejectionAndTheSecondAsksTheDirector() throws Exception {
        shell.useForTests(answering(1, "boom"), true);
        sandbox("wsl");
        MissionId m = planned();
        Ticket a = node(m, 1);
        approveNodes(m, "{\"tickets\":[\"" + a.id().value() + "\"]}");

        await("the node is rejected twice by its test", Duration.ofSeconds(90), () -> {
            allowActions(m);
            return rejectedTimes(m) != null;
        });

        assertThat(rejectedTimes(m).phrase().params()).containsEntry("times", "2");
        List<CheckRun> runs = nodeRuns(m, a);
        assertThat(runs).hasSize(2).allMatch(CheckRun::failed);
        assertThat(runs.getFirst().command()).isEqualTo("cat SUMMARY.md");
        assertThat(runs.getFirst().trigger()).isEqualTo("node test " + a.id().value());
        Ticket after = node(m, 1);
        assertThat(after.reviews()).as("it never reached a reviewer").isEmpty();
        assertThat(after.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(dirs)
                .as("on a copy, deleted after")
                .allMatch(d -> !d.toFile().exists());
        cancel(m);
    }

    @Test
    void aPassingNodeTestOpensTheReviewAndTheCopyIsGone() throws Exception {
        shell.useForTests(answering(0, "ok"), true);
        sandbox("wsl");
        MissionId m = planned();
        Ticket a = node(m, 1);
        approveNodes(m, "{\"tickets\":[\"" + a.id().value() + "\"]}");

        await("the node is done", Duration.ofSeconds(90), () -> {
            allowActions(m);
            return node(m, 1).status() == TicketStatus.DONE;
        });

        assertThat(ran).containsExactly("cat SUMMARY.md");
        assertThat(nodeRuns(m, a))
                .singleElement()
                .satisfies(r -> assertThat(r.passed()).isTrue());
        assertThat(node(m, 1).reviews()).isNotEmpty();
        assertThat(dirs.getFirst()).doesNotExist();
        cancel(m);
    }

    @Test
    void withoutTheSandboxNothingRunsAndTheJournalSaysSo() throws Exception {
        shell.useForTests(answering(1, "must never run"), false);
        sandbox("off");
        MissionId m = planned();
        Ticket a = node(m, 1);
        approveNodes(m, "{\"tickets\":[\"" + a.id().value() + "\"]}");

        await("the node is done", Duration.ofSeconds(90), () -> {
            allowActions(m);
            return node(m, 1).status() == TicketStatus.DONE;
        });

        assertThat(ran).as("never outside the sandbox").isEmpty();
        assertThat(nodeRuns(m, a)).singleElement().satisfies(r -> {
            assertThat(r.exitCode()).isNull();
            assertThat(r.failed()).isFalse();
            assertThat(r.output()).contains("did not run").contains("sandbox");
        });
        cancel(m);
    }

    @Test
    void aWorktreeTooLargeToCopyAsksTheDirectorAndNeverReachesReview() throws Exception {
        shell.useForTests(answering(0, "must never run"), true);
        sandbox("wsl");
        QuarkusMock.installMockForType(
                new ProofSnapshot() {
                    @Override
                    public Copy copy(Path worktree, String name) {
                        throw new DomainException("WORKTREE_TOO_LARGE", "More than 5 bytes to copy");
                    }
                },
                ProofSnapshot.class);
        MissionId m = planned();
        Ticket a = node(m, 1);
        approveNodes(m, "{\"tickets\":[\"" + a.id().value() + "\"]}");

        await("the question about the node's tests", Duration.ofSeconds(90), () -> {
            allowActions(m);
            return office.query(c -> c.desk().of(m).stream()
                    .anyMatch(q -> q.status() == QuestionStatus.OPEN
                            && q.phrase() != null
                            && q.phrase().code().equals("nodeTestNotRun")));
        });

        assertThat(ran).isEmpty();
        assertThat(nodeRuns(m, a)).singleElement().satisfies(r -> {
            assertThat(r.failed()).isFalse();
            assertThat(r.output()).contains("did not run").contains("More than 5 bytes to copy");
        });
        Ticket after = node(m, 1);
        assertThat(after.reviews()).isEmpty();
        assertThat(after.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        cancel(m);
    }

    @Test
    void theGraphIsExtendedByOnePlannerSessionAndNothingDoneIsTouched() throws Exception {
        MissionId m = planned();
        Ticket a = node(m, 1);
        api().body("{\"goal\":\"Add the release notes\"}")
                .post("/api/missions/" + m.value() + "/graph/extend")
                .then()
                .statusCode(200);

        await(
                "the extension's nodes",
                Duration.ofSeconds(60),
                () -> proposals(m).size() == 2);

        List<Ticket> added = proposals(m);
        assertThat(added.get(0).dependsOn()).as("after an existing node").containsExactly(a.id());
        assertThat(added.get(1).dependsOn()).containsExactly(added.get(0).id());
        TicketStatus status =
                office.query(c -> c.tickets().find(a.id()).orElseThrow().status());
        assertThat(status).as("what was there is as it was").isEqualTo(TicketStatus.PROPOSED);
        assertThat(added.get(0).description()).contains("Risk: low").contains("Tier: light");
        long extensions = office.query(c -> c.tickets().of(m).stream()
                .filter(t -> t.kind() == TicketKind.PLANNING)
                .map(Ticket::title)
                .filter(t -> t.startsWith("Extend the graph"))
                .count());
        assertThat(extensions).isEqualTo(1);
        approveNodes(m, "{\"tickets\":[\"" + added.get(0).id().value() + "\"]}");
        cancel(m);
    }

    /** The nodes the extension proposed: the proposed ones created after the extension's own planning ticket. */
    private List<Ticket> proposals(MissionId m) {
        return office.query(c -> {
            int own = c.tickets().of(m).stream()
                    .filter(t -> t.kind() == TicketKind.PLANNING && t.title().startsWith("Extend the graph"))
                    .mapToInt(t -> Integer.parseInt(t.id().value().substring(2)))
                    .findFirst()
                    .orElse(Integer.MAX_VALUE);
            return c.tickets().of(m).stream()
                    .filter(t -> t.kind() == TicketKind.WORK
                            && t.status() == TicketStatus.PROPOSED
                            && Integer.parseInt(t.id().value().substring(2)) > own)
                    .toList();
        });
    }

    @Test
    void aLoopMissionHasNoGraphToExtendAndANarrowTokenCannotAsk() throws Exception {
        MissionId loop = new MissionId(api().body("{\"title\":\"Loop\",\"objective\":\"x\",\"repository\":\""
                        + LiveMissionTest.gitRepository().toString().replace("\\", "\\\\") + "\",\"plan\":true}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id"));
        api().body("{}")
                .post("/api/missions/" + loop.value() + "/graph/extend")
                .then()
                .statusCode(409)
                .body("code", equalTo("NOT_A_GRAPH_MISSION"));
        String narrow = api().body("{\"scope\":\"missions:write\",\"days\":1,\"label\":\"script\"}")
                .post("/api/auth/tokens")
                .then()
                .statusCode(201)
                .extract()
                .path("token");
        given().header("Authorization", "Bearer " + narrow)
                .contentType("application/json")
                .body("{}")
                .post("/api/missions/" + loop.value() + "/graph/extend")
                .then()
                .statusCode(403)
                .body("code", equalTo("SCOPE"));
        cancel(loop);
    }

    @Test
    void aDoneNodeIsToRecheckOnceItsDependencyIsReopened() throws Exception {
        MissionId m = planned();
        Ticket a = node(m, 1);
        Ticket b = node(m, 2);
        approveNodes(m, "{\"tickets\":[\"" + a.id().value() + "\",\"" + b.id().value() + "\"]}");
        await("both nodes are done", Duration.ofSeconds(120), () -> {
            allowActions(m);
            return node(m, 1).status() == TicketStatus.DONE && node(m, 2).status() == TicketStatus.DONE;
        });
        TicketId first = a.id();
        office.execute(c -> c.workshop()
                .giveDirective(
                        office.context(dev.floor42.domain.Actor.director(), dev.floor42.domain.Origin.REAL),
                        first,
                        "the interface changed"));

        JsonPath view = api().get("/api/missions/" + m.value())
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(view.getBoolean("tickets.find { it.id == '" + b.id().value() + "' }.toRecheck"))
                .isTrue();
        assertThat(view.getBoolean("tickets.find { it.id == '" + first.value() + "' }.toRecheck"))
                .isFalse();
        assertThat(view.getString("tickets.find { it.id == '" + b.id().value() + "' }.status"))
                .as("never reopened by itself")
                .isEqualTo("DONE");
        cancel(m);
    }

    @Test
    void aLightNodeGoesToTheCheaperDeveloperWhenOneIsFree() throws Exception {
        String cheap = api().body("{\"name\":\"Kai\",\"role\":\"Java developer\",\"workRole\":\"DEVELOPER\","
                        + "\"model\":\"claude-haiku-5-5\",\"effort\":\"low\"}")
                .post("/api/agents")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        try {
            MissionId m = planned();
            Ticket light = node(m, 1);
            assertThat(light.description()).contains("Tier: light");
            approveNodes(m, "{\"tickets\":[\"" + light.id().value() + "\"]}");

            await("the light node is taken by the cheaper developer", Duration.ofSeconds(60), () -> {
                allowActions(m);
                return cheap.equals(String.valueOf(node(m, 1).assignee()));
            });
            cancel(m);
        } finally {
            dismissWhenFree(cheap);
        }
    }
}
