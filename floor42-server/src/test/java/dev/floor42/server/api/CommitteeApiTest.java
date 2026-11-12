package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Committee;
import dev.floor42.domain.Decision;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.app.Briefs;
import dev.floor42.server.app.OfficeService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** An architecture committee end to end with the fake Claude Code: analyses, debate, record, ruling, implementation. */
@QuarkusTest
class CommitteeApiTest extends ApiTestBase {

    @Inject
    OfficeService office;

    @Inject
    dev.floor42.server.app.LiveMissions live;

    /** The dispatcher's reading of the mission an hour from now: a wait for the director is never a stall. */
    dev.floor42.server.app.Stall stall(MissionId m) {
        return live.stall(m, java.time.Instant.now().plus(Duration.ofHours(1)));
    }

    Committee committee(MissionId m) {
        return office.query(c -> c.committees().latest(m).orElseThrow());
    }

    Question open(MissionId m) {
        return office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN)
                .findFirst()
                .orElse(null));
    }

    @Test
    void theBusinessAnalystTakesTheMembersQuestionsToTheDirectorBeforeTheRuling() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Gateway\",\"objective\":\"Should the APIs sit behind a gateway?"
                        + " [fake:clarify]\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\","
                        + "\"committee\":[\"security\",\"developer\"]}")
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
                "the business analyst's questions",
                Duration.ofSeconds(60),
                () -> committee(m).stage() == Committee.Stage.AWAITING_ANSWERS);

        assertThat(committee(m).analyst()).isEqualTo(new AgentId("linnea"));
        assertThat(committee(m).clarificationNote()).contains("merged");
        List<Question> asked = office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN)
                .toList());
        assertThat(asked).extracting(Question::kind).containsOnly(QuestionKind.CLARIFICATION);
        assertThat(asked)
                .extracting(Question::subject)
                .containsExactly("Who calls the APIs?", "How much may a gateway cost each month?");
        assertThat(asked.get(0).options()).containsExactly("Only our own apps", "Outside partners too");
        assertThat(asked.get(0).recommendation()).isEqualTo("Outside partners too");
        assertThat(asked.get(1).options()).as("a free answer").isEmpty();
        await("the stall reads the questions", Duration.ofSeconds(10), () -> "QUESTION".equals(stall(m).reason()));
        assertThat(stall(m).state()).isEqualTo("WAITING");

        api().body("{\"answer\":\"Outside partners too\"}")
                .post("/api/questions/" + asked.get(0).id().value() + "/answer")
                .then()
                .statusCode(200);
        assertThat(committee(m).stage()).as("one answer is still missing").isEqualTo(Committee.Stage.AWAITING_ANSWERS);
        api().body("{\"answer\":\"No more than 100 EUR\"}")
                .post("/api/questions/" + asked.get(1).id().value() + "/answer")
                .then()
                .statusCode(200);

        // The answers bind one more round, then a new record goes to the ruling.
        assertThat(committee(m).constraints())
                .containsExactly(
                        "The director answered \"Who calls the APIs?\": Outside partners too",
                        "The director answered \"How much may a gateway cost each month?\": No more than 100 EUR");
        await(
                "the record after the answers",
                Duration.ofSeconds(60),
                () -> committee(m).stage() == Committee.Stage.AWAITING_DIRECTOR);
        assertThat(committee(m).clarifications()).isEqualTo(1);
        assertThat(open(m).kind()).isEqualTo(QuestionKind.COMMITTEE_ARBITRATION);
        // The decision record awaits the ruling: a wait for the director, not a stall.
        await(
                "the stall reads the ruling",
                Duration.ofSeconds(10),
                () -> "COMMITTEE_DIRECTOR".equals(stall(m).reason()));
        assertThat(stall(m).state()).isEqualTo("WAITING");
        api().body("{\"reason\":\"test done\"}").post("/api/missions/" + id + "/cancel");
    }

    @Test
    void theCommitteeDeliberatesAndTheDirectorRules() throws Exception {
        api().get("/api/committee/roles")
                .then()
                .statusCode(200)
                .body("key", hasItems("architect", "security", "developer", "operations", "integration"));

        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Gateway\",\"objective\":\"Should the APIs sit behind a gateway?\","
                        + "\"repository\":\"" + repo.toString().replace("\\", "\\\\") + "\","
                        + "\"committee\":[\"security\",\"developer\"]}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .body("status", equalTo("AWAITING_APPROVAL"))
                .body("committee.stage", equalTo("ANALYSIS"))
                .body("committee.members.agent", hasItems("geoffrey", "nora", "chris"))
                .body("committee.members[0].chair", equalTo(true))
                .body("tickets[0].title", equalTo("Decide: Gateway"))
                .extract()
                .path("id");
        MissionId m = new MissionId(id);

        api().body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(200);
        await(
                "the decision record",
                Duration.ofSeconds(60),
                () -> committee(m).stage() == Committee.Stage.AWAITING_DIRECTOR);

        Committee c = committee(m);
        assertThat(c.contributions())
                .extracting(Committee.Contribution::kind)
                .containsExactly(
                        Committee.Kind.ANALYSIS,
                        Committee.Kind.ANALYSIS,
                        Committee.Kind.ANALYSIS,
                        Committee.Kind.REBUTTAL,
                        Committee.Kind.REBUTTAL,
                        Committee.Kind.REBUTTAL,
                        Committee.Kind.SYNTHESIS);
        assertThat(c.contributions().getFirst().proposal()).isEqualTo("Put a gateway in front of the APIs");
        assertThat(c.standingCriticalObjections()).singleElement().satisfies(o -> {
            assertThat(o.member()).isEqualTo(new AgentId("nora"));
            assertThat(o.criticalObjection()).isEqualTo("APIs must validate tokens themselves");
        });
        assertThat(c.synthesis().proposal()).isEqualTo("Put a gateway in front of the APIs");
        int sessions = office.query(cc -> cc.sessions().of(m).size());
        assertThat(sessions).isEqualTo(7);
        Question ruling = open(m);
        assertThat(ruling.kind()).isEqualTo(QuestionKind.COMMITTEE_ARBITRATION);
        assertThat(ruling.options()).first().asString().endsWith("despite the critical objection");

        // Sent back with a constraint: one more debate round, where every member reads the constraint.
        api().body("{\"constraint\":\"No new paid product\"}")
                .post("/api/missions/" + id + "/committees/1/send-back")
                .then()
                .statusCode(200)
                .body("committee.constraints[0]", equalTo("No new paid product"))
                .body("committee.number", equalTo(1))
                .body("committees.number", equalTo(List.of(1)));
        // A committee the mission never convened is named, and refused.
        api().body("{\"constraint\":\"x\"}")
                .post("/api/missions/" + id + "/committees/2/send-back")
                .then()
                .statusCode(409)
                .body("code", equalTo("NO_COMMITTEE"));
        await(
                "the second record",
                Duration.ofSeconds(60),
                () -> committee(m).stage() == Committee.Stage.AWAITING_DIRECTOR
                        && committee(m).round() == 3);
        int afterSecondRound = office.query(cc -> cc.sessions().of(m).size());
        assertThat(afterSecondRound).isEqualTo(11);
        String debateBrief = office.query(cc -> Briefs.committeeMember(
                cc,
                cc.missions().find(m).orElseThrow(),
                committee(m),
                cc.staff().find(new AgentId("chris")).orElseThrow()));
        assertThat(debateBrief).contains("No new paid product");

        // The director's default table at the adoption is what the implementation ticket gets (a snapshot).
        api().body("{\"decision\":\"ASK\"}")
                .put("/api/permissions/defaults/WebSearch")
                .then()
                .statusCode(200);
        try {
            // Adopted over the standing objection, and implemented: the producer's brief carries the record.
            api().body("{\"answer\":\"Adopt and implement (writes ask you first) despite the critical objection\"}")
                    .post("/api/questions/" + open(m).id().value() + "/answer")
                    .then()
                    .statusCode(200);
            await(
                    "the implementation ticket, assigned",
                    Duration.ofSeconds(20),
                    () -> office.query(cc -> cc.tickets().of(m).size() == 2
                            && cc.tickets().of(m).get(1).assignee() != null));
        } finally {
            api().body("{\"decision\":null}")
                    .put("/api/permissions/defaults/WebSearch")
                    .then()
                    .statusCode(200);
        }
        List<Ticket> tickets = office.query(cc -> cc.tickets().of(m));
        assertThat(tickets.get(0).status()).isEqualTo(TicketStatus.DONE);
        assertThat(tickets.get(1).title()).isEqualTo("Implement: Put a gateway in front of the APIs");
        assertThat(tickets.get(1).assignee()).isEqualTo(new AgentId("chris"));
        assertThat(tickets.get(1).status())
                .as("the implementation waits for the director's approval")
                .isEqualTo(TicketStatus.PROPOSED);
        String producerBrief = office.query(cc -> Briefs.producer(
                cc,
                cc.missions().find(m).orElseThrow(),
                cc.tickets().of(m).get(1),
                cc.staff().find(new AgentId("chris")).orElseThrow()));
        assertThat(producerBrief).contains("Decision adopted by the director").contains("# Decision record");
        assertThat(producerBrief).contains("- Write: asks the director the first time");
        // D15: writing asks on the implementation ticket only; the rest of the mission keeps the read-only table.
        TicketId implementation = tickets.get(1).id();
        TicketId decisionTicket = tickets.get(0).id();
        AgentId chris = new AgentId("chris");
        List<Decision> writes = office.query(cc -> {
            var policy = cc.missions().find(m).orElseThrow().policy();
            return List.of(
                    policy.evaluate(chris, implementation, ActionCategory.WRITE, "Write", null)
                            .decision(),
                    policy.evaluate(chris, implementation, ActionCategory.WRITE, "Edit", null)
                            .decision(),
                    policy.evaluate(chris, decisionTicket, ActionCategory.WRITE, "Write", null)
                            .decision(),
                    policy.evaluate(chris, null, ActionCategory.WRITE, "Write", null)
                            .decision(),
                    policy.evaluate(new AgentId("dehren"), implementation, ActionCategory.WRITE, "Write", null)
                            .decision());
        });
        assertThat(writes)
                .as("implementation Write, implementation Edit, decision ticket, mission-wide, reviewer on it")
                .containsExactly(Decision.ASK, Decision.ASK, Decision.DENY, Decision.DENY, Decision.DENY);
        List<String> offeredOnIt =
                office.query(cc -> Briefs.offeredTools(cc.missions().find(m).orElseThrow(), implementation));
        List<String> offeredElsewhere =
                office.query(cc -> Briefs.offeredTools(cc.missions().find(m).orElseThrow()));
        assertThat(offeredOnIt).contains("Write", "Edit");
        assertThat(offeredElsewhere).doesNotContain("Write", "Edit");
        api().get("/api/missions/" + id)
                .then()
                .statusCode(200)
                .body("permissions", org.hamcrest.Matchers.hasItem("ASK Write for any agent on " + implementation));
        Decision search = office.query(cc -> cc.missions()
                .find(m)
                .orElseThrow()
                .policy()
                .evaluate(chris, implementation, ActionCategory.NETWORK, "WebSearch", null)
                .decision());
        assertThat(search)
                .as("the director's default at the adoption, on the ticket only")
                .isEqualTo(Decision.ASK);
        List<dev.floor42.domain.Actor.Kind> recordedBy =
                office.query(cc -> cc.missions().find(m).orElseThrow().policy().rules().stream()
                        .filter(r -> implementation.equals(r.ticket()))
                        .map(r -> cc.missions()
                                .find(m)
                                .orElseThrow()
                                .policy()
                                .author(r)
                                .orElseThrow()
                                .kind())
                        .toList());
        assertThat(recordedBy)
                .as("Floor42 records the ticket's lines, not the director")
                .containsOnly(dev.floor42.domain.Actor.Kind.SYSTEM);
        // The panel shows the ticket's lines beyond the committee's table.
        api().get("/api/missions/" + id + "/tools")
                .then()
                .statusCode(200)
                .body("find { it.tool == 'Write' }.lineBy", equalTo("COMMITTEE_TABLE"))
                .body("find { it.tool == 'Write' }.rules.code", hasItems("ruleTicket"))
                .body("find { it.tool == 'Write' }.rules.by", hasItems("FLOOR42"));

        // Seen live (Q-73): the director allows Write for the mission; the developer then writes without a question.
        api().body("{\"decision\":\"ALLOW\"}")
                .put("/api/missions/" + id + "/tools/Write")
                .then()
                .statusCode(200)
                .body("lineBy", equalTo("DIRECTOR"));
        List<Decision> afterTheClick = office.query(cc -> {
            var policy = cc.missions().find(m).orElseThrow().policy();
            return List.of(
                    policy.evaluate(chris, implementation, ActionCategory.WRITE, "Write", null)
                            .decision(),
                    policy.evaluate(chris, implementation, ActionCategory.WRITE, "Edit", null)
                            .decision(),
                    policy.evaluate(new AgentId("dehren"), implementation, ActionCategory.WRITE, "Write", null)
                            .decision());
        });
        assertThat(afterTheClick)
                .as("implementation Write, implementation Edit (untouched), reviewer on it")
                .containsExactly(Decision.ALLOW, Decision.ASK, Decision.DENY);

        // Seen live: a directive given to the decision, once ruled, goes to the implementation.
        TicketId decision = tickets.get(0).id();
        office.execute(cc -> cc.workshop()
                .giveDirective(
                        office.context(dev.floor42.domain.Actor.director(), dev.floor42.domain.Origin.REAL),
                        decision,
                        "Generate the PDFs"));
        TicketStatus decided =
                office.query(cc -> cc.tickets().find(decision).orElseThrow().status());
        assertThat(decided).isEqualTo(TicketStatus.DONE);
        List<String> directives = office.query(cc -> cc.tickets().of(m).get(1).directives());
        assertThat(directives).containsExactly("Generate the PDFs");

        api().body("{\"reason\":\"test done\"}")
                .post("/api/missions/" + id + "/cancel")
                .then()
                .statusCode(200);
    }
}
