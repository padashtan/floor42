package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.event.DeliveryEvent.ProofRan;
import dev.floor42.domain.event.DeliveryEvent.UnprovenDeliveryAccepted;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.server.app.AgentReservations;
import dev.floor42.server.app.BoundedProcess;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.ProofShell;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * "Prove the delivery" (ADR-0058) through the API, with the fake Claude Code leaving a PROVE.md. Floor42's steps run
 * on a throwaway copy, never in the worktree, and only while the producer is idle; the run is journaled and bound to
 * the recipe and the code; the director's reports, verdict and an acceptance without proof are journaled; a failure
 * goes back to the producer's own ticket; without a working sandbox nothing runs.
 */
@QuarkusTest
@TestProfile(ProofApiTest.ProofOn.class)
class ProofApiTest {

    /** The acceptance's proof question is off in other tests, like retrospectives. */
    public static class ProofOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floor42.proof.required", "true");
        }
    }

    @Inject
    SessionToken token;

    @Inject
    OfficeService office;

    @Inject
    ProofShell shell;

    @Inject
    AgentReservations reservations;

    @Inject
    Journal journal;

    final List<String> ran = new CopyOnWriteArrayList<>();
    final List<Path> dirs = new CopyOnWriteArrayList<>();

    @AfterEach
    void restore() {
        shell.restore();
    }

    RequestSpecification api() {
        return given().header("Authorization", "Bearer " + token.value()).contentType("application/json");
    }

    Question open(MissionId m) {
        return office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN)
                .findFirst()
                .orElse(null));
    }

    MissionId delivered(String objective) throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Proof\",\"objective\":\"" + objective + "\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
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
        allowUntil(m, () -> office.query(c -> c.tickets().of(m).getFirst().status()) == TicketStatus.DONE);
        return m;
    }

    void allowUntil(MissionId m, BooleanSupplier done) throws Exception {
        await("the work", Duration.ofSeconds(60), () -> {
            Question q = open(m);
            if (q != null) api().body("{\"answer\":\"ALLOW\"}").post("/api/questions/" + q.id().value() + "/answer");
            return done.getAsBoolean();
        });
    }

    String path(MissionId m, String rest) {
        return "/api/missions/" + m.value() + "/proof" + rest;
    }

    JsonPath proof(MissionId m) {
        return api().get(path(m, "")).then().statusCode(200).extract().jsonPath();
    }

    JsonPath ranToTheEnd(MissionId m) throws Exception {
        api().post(path(m, "/run")).then().statusCode(200);
        await("the proof's run", Duration.ofSeconds(30), () -> !proof(m).getBoolean("running"));
        return proof(m);
    }

    /** A step that answers, and that tries to change the work it runs on: a new file and an edit of a tracked one. */
    ProofShell.Runner answering(int exit, String output) {
        return (dir, command) -> {
            ran.add(command);
            dirs.add(dir);
            try {
                Files.writeString(dir.resolve("left-by-the-proof.txt"), "x");
                Files.writeString(dir.resolve("README.md"), "rewritten by a step");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return new BoundedProcess.Ran(exit, false, 3, output);
        };
    }

    io.restassured.response.Response complete(MissionId m, String proofToken) {
        String ack = proofToken == null ? "" : ",\"proofAcknowledged\":\"" + proofToken + "\"";
        return api().body("{\"reason\":\"Accepted\"" + ack + "}").post("/api/missions/" + m.value() + "/complete");
    }

    io.restassured.response.Response witness(String agent, String target, MissionId m) {
        return api().body("{\"agent\":\"" + agent + "\",\"step\":2,\"works\":false,\"seen\":\"curl answers 503\","
                        + "\"target\":\"" + target + "\"}")
                .post(path(m, "/witness"));
    }

    io.restassured.response.Response verdict(String targets, MissionId m) {
        return api().body("{\"verdict\":\"NOT_PROVEN\",\"note\":\"the service does not answer\",\"targets\":\""
                        + targets + "\"}")
                .post(path(m, "/verdict"));
    }

    List<EventEnvelope> events(MissionId m) {
        return journal.readAll().stream()
                .filter(e -> e.mission() != null && e.mission().equals(m))
                .toList();
    }

    @Test
    void floor42RunsOnACopyBoundToTheCodeAndTheDirectorDecides() throws Exception {
        shell.useForTests(answering(0, "proof-ok\n"), true);
        MissionId m = delivered("Write notes [fake:touch=notes.md] [fake:prove]");
        JsonPath before = proof(m);
        assertThat(before.getString("status")).isEqualTo("NOT_RUN");
        assertThat(before.getList("worktrees[0].steps.runner")).containsExactly("FLOOR42", "DIRECTOR");
        String agent = before.getString("worktrees[0].agent");
        Path worktree = Path.of(before.getString("worktrees[0].path"));
        String readme = Files.readString(worktree.resolve("README.md"));

        // Not while the producer is at work (B4): the run is refused and nothing starts.
        var held = reservations.reserve(new AgentId(agent)).orElseThrow();
        api().post(path(m, "/run")).then().statusCode(409).body("code", equalTo("WORKTREE_BUSY"));
        reservations.release(held);
        assertThat(ran).isEmpty();

        // Unproven: the acceptance is refused until the director acknowledges what they saw.
        complete(m, null).then().statusCode(409).body("code", equalTo("UNPROVEN_DELIVERY"));

        JsonPath passed = ranToTheEnd(m);
        assertThat(passed.getString("status")).isEqualTo("PASSED");
        assertThat(ran).containsExactly("echo proof-ok");
        // On a copy Floor42 owns, deleted afterwards; the worktree is as it was (B3).
        assertThat(dirs.getFirst()).isNotEqualTo(worktree);
        assertThat(dirs.getFirst()).doesNotExist();
        assertThat(worktree.resolve("left-by-the-proof.txt")).doesNotExist();
        assertThat(Files.readString(worktree.resolve("README.md"))).isEqualTo(readme);
        assertThat(passed.getList("worktrees[0].lastRun.steps.outcome")).containsExactly("PASSED", "NOT_RUN");
        EventEnvelope recorded = events(m).stream()
                .filter(e -> e.event() instanceof ProofRan)
                .findFirst()
                .orElseThrow();
        assertThat(recorded.actor().kind()).isEqualTo(Actor.Kind.SYSTEM);
        assertThat(recorded.origin()).isEqualTo(Origin.REAL);

        // The director read the page (T1); the code changes (T2) before they report: refused, nothing recorded.
        JsonPath t1 = proof(m);
        Files.writeString(worktree.resolve("between.txt"), "changed while the director looked");
        witness(agent, t1.getString("worktrees[0].target"), m)
                .then()
                .statusCode(409)
                .body("code", equalTo("PROOF_STALE"));
        verdict(t1.getString("targets"), m).then().statusCode(409).body("code", equalTo("PROOF_STALE"));
        assertThat(proof(m).getList("worktrees[0].witnesses")).isEmpty();
        Files.delete(worktree.resolve("between.txt"));

        // The director's step does not work: said on what they read, judged, sent back to the producer's own ticket.
        JsonPath read = proof(m);
        witness(agent, read.getString("worktrees[0].target"), m).then().statusCode(200);
        verdict(read.getString("targets"), m).then().statusCode(200);
        assertThat(api().get(path(m, "/acceptance")).jsonPath().getString("state"))
                .isEqualTo("NOT_PROVEN");
        String ticket = api().body("{\"agent\":\"" + agent + "\"}")
                .post(path(m, "/send-back"))
                .then()
                .statusCode(200)
                .extract()
                .path("ticket");
        TicketId sent = new TicketId(ticket);
        List<String> ids = office.query(
                c -> c.tickets().of(m).stream().map(t -> t.id().value()).toList());
        assertThat(ids).containsExactly(ticket);
        String directive = office.query(
                c -> c.tickets().find(sent).orElseThrow().directives().getLast());
        assertThat(directive).contains("Step 2 \"The service answers\"", "Does not work. Seen: curl answers 503");
        allowUntil(
                m, () -> office.query(c -> c.tickets().find(sent).orElseThrow().status()) == TicketStatus.DONE);

        // The code changed since the run (B5): it proves nothing now, and the acceptance asks again.
        Files.writeString(worktree.resolve("later.txt"), "a change after the run");
        JsonPath stale = proof(m);
        assertThat(stale.getBoolean("worktrees[0].lastRun.stale")).isTrue();
        assertThat(stale.getString("status")).isEqualTo("NOT_RUN");
        JsonPath ask = api().get(path(m, "/acceptance")).jsonPath();
        assertThat(ask.getString("state")).isEqualTo("NOT_RUN");
        complete(m, "0000000000000000").then().statusCode(409).body("code", equalTo("UNPROVEN_DELIVERY"));
        // Uncommitted work is asked about too (ADR-0055): this test acknowledges it, the proof is what it checks.
        String uncommitted = api().get("/api/missions/" + m.value() + "/uncommitted")
                .jsonPath()
                .getString("token");
        api().body("{\"reason\":\"Accepted\",\"proofAcknowledged\":\"" + ask.getString("token")
                        + "\",\"acknowledged\":\"" + uncommitted + "\"}")
                .post("/api/missions/" + m.value() + "/complete")
                .then()
                .statusCode(200)
                .body("status", equalTo("COMPLETED"));
        assertThat(events(m).stream().map(EventEnvelope::event))
                .anySatisfy(e ->
                        assertThat(e).isEqualTo(new UnprovenDeliveryAccepted(m, "NOT_RUN", ask.getString("token"))));
    }

    @Test
    void whileTheProofRunsTheMissionNeitherClosesNorIsCancelled() throws Exception {
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        shell.useForTests(
                (dir, command) -> {
                    inside.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new BoundedProcess.Ran(0, false, 1, "proof-ok");
                },
                true);
        MissionId m = delivered("Write notes [fake:touch=notes.md] [fake:prove]");
        api().post(path(m, "/run")).then().statusCode(200);
        assertThat(inside.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        complete(m, "0000000000000000").then().statusCode(409).body("code", equalTo("PROOF_RUNNING"));
        api().body("{\"reason\":\"stop\"}")
                .post("/api/missions/" + m.value() + "/cancel")
                .then()
                .statusCode(409)
                .body("code", equalTo("PROOF_RUNNING"));
        release.countDown();
        await("the proof's run", Duration.ofSeconds(30), () -> !proof(m).getBoolean("running"));
        api().body("{\"reason\":\"stop\"}")
                .post("/api/missions/" + m.value() + "/cancel")
                .then()
                .statusCode(200);
    }

    @Test
    void withoutAWorkingSandboxNothingRunsAndEveryStepIsTheDirectors() throws Exception {
        shell.useForTests(answering(0, "proof-ok"), false);
        MissionId m = delivered("Write notes [fake:touch=notes.md] [fake:prove]");
        api().post(path(m, "/run")).then().statusCode(409).body("code", equalTo("SANDBOX_UNAVAILABLE"));
        JsonPath after = proof(m);
        assertThat(ran).isEmpty();
        assertThat(after.getBoolean("sandboxWorks")).isFalse();
        assertThat(after.getList("worktrees[0].steps.reason"))
                .containsExactly("SANDBOX_UNAVAILABLE", "SANDBOX_UNAVAILABLE");
        assertThat(after.getString("worktrees[0].lastRun")).isNull();
    }

    @Test
    void noRecipeIsSaidAndThereIsNothingToSendBackWithoutWords() throws Exception {
        shell.useForTests(answering(0, ""), true);
        MissionId m = delivered("Write notes [fake:touch=notes.md]");
        JsonPath p = proof(m);
        assertThat(p.getString("status")).isEqualTo("NO_RECIPE");
        assertThat(p.getString("worktrees[0].state")).isEqualTo("MISSING");
        api().post(path(m, "/run")).then().statusCode(409).body("code", equalTo("NOTHING_TO_RUN"));
        api().body("{\"agent\":\"" + p.getString("worktrees[0].agent") + "\"}")
                .post(path(m, "/send-back"))
                .then()
                .statusCode(409)
                .body("code", equalTo("NOTHING_TO_SEND_BACK"));
        assertThat(ran).isEmpty();
    }

    @Test
    void aRecipeWhoseOnlyStepHasAProblemRunsNothingAndRecordsNoRun() throws Exception {
        shell.useForTests(answering(0, "proof-ok"), true);
        MissionId m = delivered("Write notes [fake:touch=notes.md] [fake:prove-bad]");
        api().post(path(m, "/run")).then().statusCode(409).body("code", equalTo("NOTHING_TO_RUN"));
        assertThat(ran).isEmpty();
        assertThat(proof(m).getString("worktrees[0].lastRun")).isNull();
    }

    @Test
    void aBadStepIsNeverRunWhileTheGoodOnesAre() throws Exception {
        shell.useForTests(answering(0, "proof-ok"), true);
        MissionId m = delivered("Write notes [fake:touch=notes.md] [fake:prove-mixed]");
        JsonPath after = ranToTheEnd(m);
        assertThat(after.getList("worktrees[0].lastRun.steps.outcome")).containsExactly("PASSED", "NOT_RUN");
        assertThat(after.getList("worktrees[0].lastRun.steps.failure")).containsExactly(null, "NOT_RUNNABLE");
        assertThat(ran).hasSize(1);
    }
}
