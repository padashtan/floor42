package dev.floor42.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent;
import dev.floor42.domain.event.TicketEvent;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.server.api.Scorecard.ScoreLine;
import dev.floor42.server.app.Briefs;
import dev.floor42.server.journal.EventCodec;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The cost line of a mission whose sessions were journaled before caps and bounds existed (rows before 1.9.0), and the
 * governance line and retrospective facts of a mission whose broker refusals were journaled as failed tool calls.
 */
class ScorecardCostTest {

    static final AgentId CHRIS = new AgentId("chris");
    static final AgentId GALEN = new AgentId("galen");

    final Company company = new Company();
    final EventCodec codec = new EventCodec();
    Instant now = Instant.parse("2026-10-20T09:00:00Z");

    CommandContext ctx(Actor actor) {
        now = now.plusSeconds(1);
        return new CommandContext(actor, Origin.REAL, now, null);
    }

    MissionId missionInProgress() {
        for (AgentId id : List.of(GALEN, CHRIS))
            company.staff()
                    .hire(
                            ctx(Actor.director()),
                            id,
                            id.value(),
                            "role",
                            "anthropic",
                            "claude-sonnet-5-5",
                            "medium",
                            List.of());
        MissionId m = ((MissionEvent.MissionCreated) company.missions()
                        .create(
                                ctx(Actor.director()),
                                "Fix the build",
                                "Make mvn test pass",
                                "C:/work/repo",
                                Workflow.BUILT_IN)
                        .getFirst()
                        .event())
                .mission();
        TicketId t = ((TicketEvent.TicketCreated) company.tickets()
                        .create(ctx(Actor.director()), m, "Deliver", "", Priority.NORMAL, List.of(), List.of("done"))
                        .getFirst()
                        .event())
                .ticket();
        company.tickets().assign(ctx(Actor.director()), t, CHRIS);
        company.missions().submitPlan(ctx(Actor.agent(GALEN)), m);
        company.verification().setBudget(ctx(Actor.director()), m, null);
        company.missions().approvePlan(ctx(Actor.director()), m);
        company.tickets().start(ctx(Actor.agent(CHRIS)), t);
        return m;
    }

    /** A row as 1.8.0 wrote it: no capUsd, no costUsdBound. */
    void oldRow(MissionId m, String type, String payload) {
        now = now.plusSeconds(60);
        company.apply(new EventEnvelope(
                "old-" + type, now, Origin.REAL, Actor.system(), m, null, null, codec.decode(type, payload)));
    }

    @Test
    void aSessionEndedWithoutACostInAnOldJournalCountsNothingAndSaysNoCostReported() {
        MissionId m = missionInProgress();
        String ticket = company.tickets().of(m).getFirst().id().value();
        oldRow(
                m,
                "AgentSessionStarted",
                "{\"session\":\"M-1:s1\",\"mission\":\"" + m.value() + "\",\"ticket\":\"" + ticket
                        + "\",\"agent\":\"chris\",\"model\":\"claude-sonnet-5-5\",\"runtimeVersion\":\"2.1.304\"}");
        oldRow(
                m,
                "AgentSessionEnded",
                "{\"session\":\"M-1:s1\",\"failed\":true,\"outcome\":\"interrupted\",\"turns\":null,"
                        + "\"inputTokensProvided\":null,\"outputTokensProvided\":null,\"costUsdEstimated\":null}");

        var session = company.sessions().find("M-1:s1").orElseThrow();
        assertThat(session.costUsdBound()).isNull();
        assertThat(session.capUsd()).isNull();
        assertThat(company.verification().spentUsdBound(m)).isZero();
        assertThat(company.verification().sessionsBounded(m)).isZero();
        Mission mission = company.missions().find(m).orElseThrow();
        ScoreLine cost = Scorecard.of(company, mission).stream()
                .filter(l -> l.label().equals("Cost"))
                .findFirst()
                .orElseThrow();
        assertThat(cost.value()).isEqualTo("no cost reported");
        assertThat(cost.code()).isEqualTo("cost.unreported");
        assertThat(MissionViews.MissionView.of(company, mission).spend().sessionsBounded())
                .isZero();
        assertThat(MissionViews.MissionView.of(company, mission).spend().spentUsdBound())
                .as("no bound rather than 0, which would read as a real figure")
                .isNull();
    }

    /** One broker call as the journal of mission M-21 holds it: requested, authorized as a Floor42 tool, then failed. */
    void brokerCall(MissionId m, String id, String error) {
        String ticket = company.tickets().of(m).getFirst().id().value();
        oldRow(
                m,
                "ActionRequested",
                "{\"action\":\"" + id + "\",\"mission\":\"" + m.value() + "\",\"ticket\":\"" + ticket
                        + "\",\"agent\":\"chris\",\"category\":\"FLOOR42\","
                        + "\"tool\":\"mcp__floor42__call_resource\",\"summary\":\"demo GET /x\"}");
        oldRow(m, "ActionAuthorized", "{\"action\":\"" + id + "\",\"basis\":\"Floor42 tools are always available\"}");
        oldRow(m, "ActionFailed", "{\"action\":\"" + id + "\",\"error\":\"" + error + "\"}");
    }

    String governance(Mission mission) {
        return Scorecard.of(company, mission).stream()
                .filter(l -> l.label().equals("Governance"))
                .findFirst()
                .orElseThrow()
                .value();
    }

    @Test
    void theThreeBrokerRefusalsOfM21AreCountedRefusedByTheRetroAndTheReport() {
        MissionId m = missionInProgress();
        brokerCall(m, "a1", "PATH_NOT_ALLOWED: The address is not allowed for demo");
        brokerCall(m, "a2", "METHOD_NOT_ALLOWED: The address is not allowed for demo");
        brokerCall(m, "a3", "RESOURCE_NOT_GRANTED: This agent or mission may not use demo-admin");
        Mission mission = company.missions().find(m).orElseThrow();

        assertThat(Briefs.retroFacts(company, mission)).contains("Tool requests: 3, 3 refused.");
        assertThat(governance(mission)).isEqualTo("3 tool calls · 0 asked you · 3 refused");
    }

    @Test
    void anUnreachableResourceOrAServerErrorStaysAFailure() {
        MissionId m = missionInProgress();
        brokerCall(m, "a1", "RESOURCE_UNREACHABLE: demo did not answer in time");
        brokerCall(m, "a2", "Resource demo GET /x -> 503 (exchanged token t1 for demo)");
        Mission mission = company.missions().find(m).orElseThrow();

        assertThat(Briefs.retroFacts(company, mission)).contains("Tool requests: 2, 0 refused.");
        assertThat(governance(mission)).isEqualTo("2 tool calls · 0 asked you · 0 refused");
    }

    @Test
    void identityRefusalsByFloor42AreRefusedAndIdentityProviderFailuresAreNot() {
        MissionId m = missionInProgress();
        brokerCall(m, "a1", "IDENTITY_REFUSED: The mission is completed: no resource for its agents");
        brokerCall(m, "a2", "IDENTITY_REFUSED: The mission's delegation is revoked");
        // S3.1: Keycloak stopped mid-session; the provider's silence is a failure, not Floor42's decision.
        brokerCall(m, "a3", "IDENTITY_UNAVAILABLE: Keycloak gave no token for demo");
        brokerCall(m, "a4", "IDENTITY_UNAVAILABLE: Keycloak gave an unreadable token");
        brokerCall(m, "a5", "IDENTITY_UNAVAILABLE: Keycloak gave a token that is not for demo");
        brokerCall(m, "a6", "IDENTITY_UNAVAILABLE: Keycloak gave an expired token");
        brokerCall(m, "a7", "IDENTITY_UNAVAILABLE: The agent has no Keycloak secret");
        Mission mission = company.missions().find(m).orElseThrow();

        assertThat(governance(mission)).isEqualTo("7 tool calls · 0 asked you · 2 refused");
        assertThat(Briefs.retroFacts(company, mission)).contains("Tool requests: 7, 2 refused.");
    }

    @Test
    void theClassifierReadsTheCodeNotTheMessage() {
        MissionId m = missionInProgress();
        brokerCall(m, "a1", "IDENTITY_REFUSED: reworded, still Floor42's decision");
        brokerCall(m, "a2", "IDENTITY_UNAVAILABLE: The mission is completed (a reworded provider failure)");
        brokerCall(
                m, "a3", "IDENTITY_UNAVAILABLE: The mission is completed: no resource for its agents"); // 1.17 journal
        Mission mission = company.missions().find(m).orElseThrow();

        assertThat(Briefs.retroFacts(company, mission)).contains("Tool requests: 3, 2 refused.");
    }
}
