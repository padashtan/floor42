package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.ActionId;
import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Criticality;
import dev.floor42.domain.Decision;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.PermissionRule;
import dev.floor42.domain.Priority;
import dev.floor42.domain.QuestionId;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.DeskEvent.ApprovalForgotten;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Lifting a remembered refusal while answering an agent's question (askAgain) through the API: offered in the view,
 * the director's own scope only (as /forget), one exact refusal of the asking agent on that mission, journaled as the
 * director's.
 */
@QuarkusTest
class AskAgainApiTest {

    @Inject
    SessionToken token;

    @Inject
    OfficeService office;

    @Inject
    Journal journal;

    /** A simulated mission whose developer was refused a write, remembered: its ids. */
    record Setup(MissionId mission, TicketId ticket, AgentId developer, AgentId other, String denial) {}

    CommandContext ctx(Actor actor) {
        return office.context(actor, Origin.SIMULATED);
    }

    Setup refusedWrite() {
        String n = UUID.randomUUID().toString().substring(0, 8);
        AgentId dev = new AgentId("lift-dev-" + n);
        AgentId other = new AgentId("lift-other-" + n);
        for (AgentId a : List.of(dev, other))
            office.execute(c -> c.staff()
                    .hire(
                            ctx(Actor.director()),
                            a,
                            a.value(),
                            "role",
                            "anthropic",
                            "claude-sonnet-5-5",
                            "medium",
                            List.of()));
        MissionId m = ((MissionCreated) office.execute(
                                c -> c.missions().create(ctx(Actor.director()), "Lift " + n, "A report"))
                        .getFirst()
                        .event())
                .mission();
        TicketId t = ((TicketCreated) office.execute(c -> c.tickets()
                                .create(
                                        ctx(Actor.director()),
                                        m,
                                        "Deliver",
                                        "",
                                        Priority.NORMAL,
                                        List.of(),
                                        List.of("done")))
                        .getFirst()
                        .event())
                .ticket();
        office.execute(c -> c.tickets().assign(ctx(Actor.director()), t, dev));
        office.execute(c -> c.missions().submitPlan(ctx(Actor.agent(other)), m));
        office.execute(c -> c.missions().approvePlan(ctx(Actor.director()), m));
        office.execute(c -> c.tickets().start(ctx(Actor.agent(dev)), t));
        office.execute(c -> c.missions()
                .addPermission(
                        ctx(Actor.director()), m, new PermissionRule(null, ActionCategory.WRITE, null, Decision.ASK)));
        office.execute(c ->
                c.missions().setToolPermission(ctx(Actor.director()), m, "Write", ActionCategory.WRITE, Decision.ASK));
        office.execute(c -> c.actions()
                .request(
                        ctx(Actor.agent(dev)),
                        new ActionId("w-" + n),
                        m,
                        t,
                        dev,
                        ActionCategory.WRITE,
                        "Write",
                        "notes.txt"));
        String denial = open(m, QuestionKind.ACTION_APPROVAL);
        answer(token.value(), denial, "DENY", null).statusCode(200);
        return new Setup(m, t, dev, other, denial);
    }

    String asks(Setup s, AgentId agent, MissionId mission) {
        office.execute(c -> c.desk()
                .ask(
                        ctx(Actor.agent(agent)),
                        mission,
                        "My write was refused. What now?",
                        "",
                        List.of("Retry", "Give up"),
                        null,
                        Criticality.MEDIUM,
                        false,
                        mission.equals(s.mission()) ? s.ticket() : null));
        return open(mission, QuestionKind.OPEN);
    }

    String open(MissionId m, QuestionKind kind) {
        return office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN && q.kind() == kind)
                .map(q -> q.id().value())
                .reduce((a, b) -> b)
                .orElseThrow());
    }

    ValidatableResponse answer(String bearer, String question, String answer, String askAgain) {
        String body = askAgain == null
                ? "{\"answer\":\"" + answer + "\"}"
                : "{\"answer\":\"" + answer + "\",\"askAgain\":\"" + askAgain + "\"}";
        return given().header("Authorization", "Bearer " + bearer)
                .contentType("application/json")
                .body(body)
                .post("/api/questions/" + question + "/answer")
                .then();
    }

    String scriptToken(String scope) {
        return given().header("Authorization", "Bearer " + token.value())
                .contentType("application/json")
                .body("{\"scope\":\"" + scope + "\",\"days\":1,\"label\":\"lift\"}")
                .post("/api/auth/tokens")
                .then()
                .statusCode(201)
                .extract()
                .path("token");
    }

    QuestionStatus status(String question) {
        return office.query(
                c -> c.desk().find(new QuestionId(question)).orElseThrow().status());
    }

    boolean remembered(String denial) {
        return office.query(c -> c.desk().remembered(new QuestionId(denial)));
    }

    @Test
    void theViewOffersTheRefusalAndTheDirectorLiftsItAsTheDirector() {
        Setup s = refusedWrite();
        String q = asks(s, s.developer(), s.mission());
        given().header("Authorization", "Bearer " + token.value())
                .get("/api/questions")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + q + "' }.askAgain.question", hasItem(s.denial()))
                .body("find { it.id == '" + q + "' }.askAgain[0].tool", equalTo("Write"))
                .body("find { it.id == '" + q + "' }.askAgain[0].summary", equalTo("notes.txt"));

        answer(token.value(), q, "Retry", s.denial()).statusCode(200);

        assertThat(remembered(s.denial())).isFalse();
        EventEnvelope lift = journal.readAll().stream()
                .filter(e -> e.event() instanceof ApprovalForgotten f
                        && f.question().value().equals(s.denial()))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(lift.actor()).isEqualTo(Actor.director());
        assertThat(lift.origin()).isEqualTo(Origin.SIMULATED);
    }

    @Test
    void aMissionsWriteTokenMayAnswerButNotLift() {
        Setup s = refusedWrite();
        String q = asks(s, s.developer(), s.mission());
        String narrow = scriptToken("missions:write");

        answer(narrow, q, "Retry", s.denial()).statusCode(403).body("code", equalTo("SCOPE"));
        assertThat(remembered(s.denial())).isTrue();
        assertThat(status(q)).as("nothing recorded").isEqualTo(QuestionStatus.OPEN);
        answer(narrow, q, "Retry", null).statusCode(200);
        assertThat(remembered(s.denial())).as("an answer alone lifts nothing").isTrue();
    }

    @Test
    void askingBackNeedsTheDirectorsOwnScopeAndIsRefusedOnARehearsal() {
        Setup s = refusedWrite();
        String q = asks(s, s.developer(), s.mission());
        String body = "{\"text\":\"Why?\"}";
        given().header("Authorization", "Bearer " + scriptToken("missions:write"))
                .contentType("application/json")
                .body(body)
                .post("/api/questions/" + q + "/ask-back")
                .then()
                .statusCode(403);
        given().header("Authorization", "Bearer " + token.value())
                .contentType("application/json")
                .body(body)
                .post("/api/questions/" + q + "/ask-back")
                .then()
                .statusCode(409)
                .body("code", equalTo("ASK_BACK_NOT_ALLOWED"));
        assertThat(status(q)).isEqualTo(QuestionStatus.OPEN);
    }

    @Test
    void onlyARememberedRefusalOfTheAskingAgentOnItsMissionIsLifted() {
        Setup s = refusedWrite();
        // Another agent's question.
        String others = asks(s, s.other(), s.mission());
        answer(token.value(), others, "Retry", s.denial()).statusCode(409).body("code", equalTo("NOT_REMEMBERED"));
        // A question on another mission.
        Setup elsewhere = refusedWrite();
        String away = asks(elsewhere, elsewhere.developer(), elsewhere.mission());
        answer(token.value(), away, "Retry", s.denial()).statusCode(409).body("code", equalTo("NOT_REMEMBERED"));
        // Already lifted.
        String first = asks(s, s.developer(), s.mission());
        answer(token.value(), first, "Retry", s.denial()).statusCode(200);
        String second = asks(s, s.developer(), s.mission());
        answer(token.value(), second, "Retry", s.denial()).statusCode(409).body("code", equalTo("NOT_REMEMBERED"));
        assertThat(status(second)).isEqualTo(QuestionStatus.OPEN);
        assertThat(remembered(elsewhere.denial())).isTrue();
    }
}
