package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.TicketEvent.DirectiveGiven;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.server.app.BoundedProcess;
import dev.floor42.server.app.LiveMissions;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The director's objections at acceptance (ADR-0060) through the API, with the fake Claude Code: an objection reopens
 * the producer's ticket in its own worktree, the review runs again, the proof of the delivery asks again, and the
 * acceptance goes through; new work is a ticket of the mission; a refusal records nothing.
 */
@QuarkusTest
@TestProfile(ObjectionsApiTest.ProofOn.class)
class ObjectionsApiTest {

    /** The acceptance's proof question is on, as in production. */
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
    Journal journal;

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

    MissionId delivered() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body(
                        "{\"title\":\"Objections\",\"objective\":\"Write notes [fake:touch=notes.md] [fake:prove]\","
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
        allowUntil(
                m,
                () -> status(first(m)) == TicketStatus.DONE
                        && office.query(c -> c.sessions().of(m).stream().noneMatch(s -> s.isOpen())));
        return m;
    }

    /** Ticket ids count across missions: the mission's first ticket is not always T-1. */
    String first(MissionId m) {
        return office.query(c -> c.tickets().of(m).getFirst().id().value());
    }

    TicketStatus status(String ticket) {
        return office.query(
                c -> c.tickets().find(new TicketId(ticket)).orElseThrow().status());
    }

    void allowUntil(MissionId m, BooleanSupplier done) throws Exception {
        await("the work", Duration.ofSeconds(60), () -> {
            Question q = open(m);
            if (q != null) api().body("{\"answer\":\"ALLOW\"}").post("/api/questions/" + q.id().value() + "/answer");
            return done.getAsBoolean();
        });
    }

    io.restassured.response.Response object(MissionId m, String body) {
        return api().body(body).post("/api/missions/" + m.value() + "/objections");
    }

    JsonPath proof(MissionId m) {
        return api().get("/api/missions/" + m.value() + "/proof")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    List<EventEnvelope> events(MissionId m) {
        return journal.readAll().stream()
                .filter(e -> e.mission() != null && e.mission().equals(m))
                .toList();
    }

    @Test
    void anObjectionSendsTheProducerBackAndTheProofAndTheAcceptanceAskAgain() throws Exception {
        shell.useForTests((dir, command) -> new BoundedProcess.Ran(0, false, 1, "proof-ok\n"), true);
        MissionId m = delivered();
        api().post("/api/missions/" + m.value() + "/proof/run").then().statusCode(200);
        await("the proof's run", Duration.ofSeconds(30), () -> !proof(m).getBoolean("running"));
        String before = api().get("/api/missions/" + m.value() + "/proof/acceptance")
                .jsonPath()
                .getString("token");
        int eventsBefore = events(m).size();

        // A request that is wrong records nothing.
        object(m, "{\"objections\":[]}").then().statusCode(400).body("code", equalTo("INVALID_ARGUMENT"));
        object(m, "{\"objections\":[{\"ticket\":\"" + first(m) + "\",\"text\":\" \"}]}")
                .then()
                .statusCode(400);
        object(m, "{\"objections\":[{\"ticket\":\"T-99999\",\"text\":\"x\"}]}")
                .then()
                .statusCode(404);
        assertThat(events(m)).hasSize(eventsBefore);

        object(m, "{\"objections\":[{\"ticket\":\"" + first(m) + "\",\"text\":\"Also cover the expiry\"}]}")
                .then()
                .statusCode(200)
                .body("status", equalTo("IN_PROGRESS"));

        // Not at acceptance any more while the ticket is being reworked.
        object(m, "{\"objections\":[{\"ticket\":\"" + first(m) + "\",\"text\":\"again\"}]}")
                .then()
                .statusCode(409)
                .body("code", equalTo("NOT_AT_ACCEPTANCE"));
        allowUntil(m, () -> status(first(m)) == TicketStatus.DONE);

        List<EventEnvelope> round = events(m).stream()
                .filter(e -> "objections-1".equals(e.causationId()))
                .toList();
        assertThat(round.stream().map(EventEnvelope::event))
                .anySatisfy(e -> assertThat(e)
                        .isEqualTo(new DirectiveGiven(m, new TicketId(first(m)), "Also cover the expiry")));
        assertThat(api().get("/api/missions/" + m.value() + "/report")
                        .jsonPath()
                        .getList("objections.number"))
                .containsExactly(1);
        assertThat(api().get("/api/missions/" + m.value() + "/report")
                        .jsonPath()
                        .getList("objections[0].targeted"))
                .containsExactly(first(m));

        // The fake producer wrote its change during the rework ([fake:touch], the session's id in the file): the code
        // changed since the run, the proof counts for nothing and the acceptance asks again.
        assertThat(proof(m).getBoolean("worktrees[0].lastRun.stale")).isTrue();
        String ask = api().get("/api/missions/" + m.value() + "/proof/acceptance")
                .jsonPath()
                .getString("token");
        assertThat(ask).isNotEqualTo(before);
        api().body("{\"reason\":\"Accepted\",\"proofAcknowledged\":\"" + before + "\"}")
                .post("/api/missions/" + m.value() + "/complete")
                .then()
                .statusCode(409)
                .body("code", equalTo("UNPROVEN_DELIVERY"));
        String uncommitted = api().get("/api/missions/" + m.value() + "/uncommitted")
                .jsonPath()
                .getString("token");
        api().body("{\"reason\":\"Accepted\",\"proofAcknowledged\":\"" + ask + "\",\"acknowledged\":\"" + uncommitted
                        + "\"}")
                .post("/api/missions/" + m.value() + "/complete")
                .then()
                .statusCode(200)
                .body("status", equalTo("COMPLETED"));
        object(m, "{\"objections\":[{\"ticket\":\"" + first(m) + "\",\"text\":\"too late\"}]}")
                .then()
                .statusCode(409)
                .body("code", equalTo("NOT_AT_ACCEPTANCE"));
    }

    @Test
    void newWorkIsATicketOfTheMissionUnderItsOwnTable() throws Exception {
        MissionId m = delivered();

        object(m, "{\"objections\":[{\"text\":\"Add a French README\"}]}")
                .then()
                .log()
                .ifValidationFails()
                .statusCode(200);

        List<EventEnvelope> round = events(m).stream()
                .filter(e -> "objections-1".equals(e.causationId()))
                .toList();
        assertThat(round).hasSize(1);
        TicketCreated created = (TicketCreated) round.getFirst().event();
        assertThat(created.acceptanceCriteria())
                .containsExactly("Done as the director's objection asks: Add a French README");
        assertThat(round.getFirst().actor().kind()).isEqualTo(dev.floor42.domain.Actor.Kind.DIRECTOR);
        assertThat(api().get("/api/missions/" + m.value() + "/report")
                        .jsonPath()
                        .getList("objections[0].created"))
                .containsExactly(created.ticket().value());
        object(m, "{\"objections\":[{\"text\":\"Another\"}]}")
                .then()
                .statusCode(409)
                .body("code", equalTo("NOT_AT_ACCEPTANCE"));
        api().body("{\"reason\":\"stop\"}")
                .post("/api/missions/" + m.value() + "/cancel")
                .then()
                .statusCode(200);
    }

    @Inject
    LiveMissions live;

    @Test
    void newWorkWaitsBehindTheDeveloperWhoseChangesAreHeldAndStartsOnceTheDirectorAcceptsThem() throws Exception {
        MissionId m = delivered();
        String producer =
                api().get("/api/missions/" + m.value() + "/held").jsonPath().getString("[0].agent");
        assertThat(producer)
                .as("the finished ticket's changes hold its developer")
                .isNotNull();

        object(m, "{\"objections\":[{\"text\":\"Add a French README\"}]}")
                .then()
                .statusCode(200);
        TicketId added = office.query(c -> c.tickets().of(m).getLast().id());
        live.nudge(m);
        Thread.sleep(3_000);
        TicketStatus waiting = status(added.value());
        Boolean started = office.query(
                c -> c.sessions().of(m).stream().anyMatch(s -> s.ticket().equals(added)));
        assertThat(waiting).isEqualTo(TicketStatus.TODO);
        assertThat(started).isFalse();
        var stall = live.stall(m, java.time.Instant.now().plusSeconds(60));
        assertThat(stall.reason()).isEqualTo("HELD_PATCH");
        assertThat(stall.ticket()).isEqualTo(added.value());

        api().body("{\"message\":\"Notes of the first ticket\"}")
                .post("/api/missions/" + m.value() + "/workshop/" + producer + "/accept")
                .then()
                .statusCode(200);
        allowUntil(m, () -> status(added.value()) == TicketStatus.DONE);
    }

    @Test
    void anObjectionIsRefusedWhileTheProofRuns() throws Exception {
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
        MissionId m = delivered();
        api().post("/api/missions/" + m.value() + "/proof/run")
                .then()
                .log()
                .ifValidationFails()
                .statusCode(200);
        assertThat(inside.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        object(m, "{\"objections\":[{\"ticket\":\"" + first(m) + "\",\"text\":\"x\"}]}")
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
}
