package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.DARIUS;
import static dev.floor42.domain.Fixture.GALEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.ActionEvent;
import dev.floor42.domain.event.EventEnvelope;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Propose, decide, execute (spec section 7): enforced by the domain, whatever the agent says. */
@ExtendWith(GoldenFixtureExtension.class)
class ActionGovernanceTest {

    final Fixture f = new Fixture();
    final MissionId m = f.runningMission();
    final TicketId t = f.firstTicket(m);

    List<EventEnvelope> request(String id, ActionCategory category, String tool, String summary) {
        return f.record(
                f.company.actions().request(f.agent(CHRIS), new ActionId(id), m, t, CHRIS, category, tool, summary));
    }

    Action action(String id) {
        return f.company.actions().find(new ActionId(id)).orElseThrow();
    }

    @Test
    void anAllowedActionIsAuthorizedByOfficeNotByTheAgent() {
        f.allow(m, ActionCategory.EXECUTE, "git status", null);

        List<EventEnvelope> events = request("a1", ActionCategory.EXECUTE, "Bash", "git status");

        assertThat(action("a1").stage()).isEqualTo(ActionStage.AUTHORIZED);
        assertThat(events).extracting(e -> e.actor().kind()).containsExactly(Actor.Kind.AGENT, Actor.Kind.SYSTEM);
        assertThat(events.getLast().authorizationRef()).isEqualTo("policy:" + m);
    }

    @Test
    void nothingIsAllowedByDefault() {
        request("a1", ActionCategory.WRITE, "Write", "notes.txt");

        assertThat(action("a1").stage()).isEqualTo(ActionStage.DENIED);
        assertThat(action("a1").decisionBasis()).contains("default deny");
    }

    @Test
    void aCallLeavingTheWorkspaceIsDeniedWhateverTheTableSays() {
        f.allow(m, ActionCategory.READ, null, null);

        f.record(f.company
                .actions()
                .request(
                        f.agent(CHRIS),
                        new ActionId("a1"),
                        m,
                        t,
                        CHRIS,
                        ActionCategory.READ,
                        "Read",
                        "C:/Users/director/.ssh/id_ed25519",
                        "outside the agent's worktree"));

        assertThat(action("a1").stage()).isEqualTo(ActionStage.DENIED);
        assertThat(action("a1").denialReason()).isEqualTo(DenialReason.OUTSIDE_WORKSPACE);
        assertThat(action("a1").decisionBasis()).isEqualTo("outside the agent's worktree");
        assertThat(f.company.desk().of(m)).isEmpty();
    }

    @Test
    void commandPrefixesMatchOnWordBoundaries() {
        f.allow(m, ActionCategory.EXECUTE, "git diff", null);

        request("a1", ActionCategory.EXECUTE, "Bash", "git diff --stat");
        request("a2", ActionCategory.EXECUTE, "Bash", "git diff-index HEAD");

        assertThat(action("a1").stage()).isEqualTo(ActionStage.AUTHORIZED);
        assertThat(action("a2").stage()).isEqualTo(ActionStage.DENIED);
    }

    @Test
    void theMostSpecificRuleWinsAndTiesAreSettledByTheStricterRule() {
        f.allow(m, ActionCategory.EXECUTE, null, null);
        f.record(f.company
                .missions()
                .addPermission(
                        f.director(), m, new PermissionRule(CHRIS, ActionCategory.EXECUTE, "docker", Decision.DENY)));
        f.record(f.company
                .missions()
                .addPermission(f.director(), m, new PermissionRule(null, ActionCategory.READ, null, Decision.ALLOW)));
        f.record(f.company
                .missions()
                .addPermission(f.director(), m, new PermissionRule(null, ActionCategory.READ, null, Decision.DENY)));

        request("a1", ActionCategory.EXECUTE, "Bash", "mvn test");
        request("a2", ActionCategory.EXECUTE, "Bash", "docker restart gateway");
        request("a3", ActionCategory.READ, "Read", "README.md");

        assertThat(action("a1").stage()).isEqualTo(ActionStage.AUTHORIZED);
        assertThat(action("a2").stage()).isEqualTo(ActionStage.DENIED);
        assertThat(action("a3").stage()).isEqualTo(ActionStage.DENIED);
    }

    @Test
    void askTurnsTheRequestIntoABlockingQuestionForTheDirector() {
        f.record(f.company
                .missions()
                .addPermission(f.director(), m, new PermissionRule(null, ActionCategory.EXECUTE, null, Decision.ASK)));

        request("a1", ActionCategory.EXECUTE, "Bash", "docker restart gateway");

        Question q = f.company.desk().open().getFirst();
        assertThat(q.kind()).isEqualTo(QuestionKind.ACTION_APPROVAL);
        assertThat(q.action()).isEqualTo(new ActionId("a1"));
        assertThat(f.company.queries().statusOf(CHRIS)).isEqualTo(AgentStatus.WAITING_FOR_DIRECTOR);

        List<EventEnvelope> answer = f.record(f.company.desk().answer(f.director(), q.id(), "allow"));

        assertThat(action("a1").stage()).isEqualTo(ActionStage.AUTHORIZED);
        assertThat(answer.getLast().authorizationRef()).isEqualTo("question:" + q.id());
    }

    @Test
    void theDirectorCanRefuseAnAskedAction() {
        f.record(f.company
                .missions()
                .addPermission(f.director(), m, new PermissionRule(null, ActionCategory.EXECUTE, null, Decision.ASK)));
        request("a1", ActionCategory.EXECUTE, "Bash", "rm -rf build");

        f.record(f.company
                .desk()
                .answer(f.director(), f.company.desk().open().getFirst().id(), "DENY"));

        assertThat(action("a1").denialReason()).isEqualTo(DenialReason.DIRECTOR);
    }

    @Test
    void actionsAreDeniedWhileTheMissionIsSuspended() {
        f.allow(m, ActionCategory.EXECUTE, null, null);
        f.record(f.company.missions().suspend(f.director(), m, "waiting for the client"));

        request("a1", ActionCategory.EXECUTE, "Bash", "mvn test");

        assertThat(action("a1").denialReason()).isEqualTo(DenialReason.MISSION_NOT_ACTIVE);
    }

    @Test
    void anAgentCannotActOnATicketItDoesNotWorkOn() {
        f.allow(m, ActionCategory.READ, null, null);

        f.record(f.company
                .actions()
                .request(f.agent(GALEN), new ActionId("a1"), m, t, GALEN, ActionCategory.READ, "Read", "pom.xml"));

        assertThat(action("a1").stage()).isEqualTo(ActionStage.DENIED);
    }

    @Test
    void agentsCannotChangePermissionsNotEvenTheirOwn() {
        PermissionRule rule = new PermissionRule(CHRIS, ActionCategory.EXECUTE, null, Decision.ALLOW);

        assertThatThrownBy(() -> f.company.missions().addPermission(f.agent(CHRIS), m, rule))
                .extracting("code")
                .isEqualTo("DIRECTOR_ONLY");
    }

    @Test
    void officeToolsAreAvailableUnlessExplicitlyDenied() {
        request("a1", ActionCategory.FLOOR42, "mcp__floor42__ask_director", "Which gateway?");

        assertThat(action("a1").stage()).isEqualTo(ActionStage.AUTHORIZED);
    }

    @Test
    void anAuthorizedActionEndsExecutedOrFailed() {
        f.allow(m, ActionCategory.EXECUTE, null, null);
        request("a1", ActionCategory.EXECUTE, "Bash", "mvn test");
        request("a2", ActionCategory.EXECUTE, "Bash", "mvn verify");

        f.record(f.company.actions().recordOutcome(f.system(), new ActionId("a1"), true, "BUILD SUCCESS"));
        f.record(f.company.actions().recordOutcome(f.system(), new ActionId("a2"), false, "2 tests failed"));

        assertThat(action("a1").stage()).isEqualTo(ActionStage.EXECUTED);
        assertThat(action("a1").stage().hasRun()).isTrue();
        assertThat(action("a2").stage()).isEqualTo(ActionStage.FAILED);
    }

    @Test
    void anErrorWithoutAuthorizationIsABlockNotAFailure() {
        // Rule found in the runtime spike: with governance unreachable, the fail-closed hook blocks the call.
        f.record(f.company
                .missions()
                .addPermission(f.director(), m, new PermissionRule(null, ActionCategory.EXECUTE, null, Decision.ASK)));
        request("a1", ActionCategory.EXECUTE, "Bash", "git status");

        f.record(f.company.actions().recordOutcome(f.system(), new ActionId("a1"), false, "PreToolUse hook error"));

        assertThat(action("a1").stage()).isEqualTo(ActionStage.DENIED);
        assertThat(action("a1").denialReason()).isEqualTo(DenialReason.BLOCKED_UPSTREAM);
        assertThat(action("a1").stage().hasRun()).isFalse();
    }

    @Test
    void aSuccessWithoutAuthorizationRaisesAnIncidentAndSuspendsTheMission() {
        f.record(f.company
                .missions()
                .addPermission(f.director(), m, new PermissionRule(null, ActionCategory.EXECUTE, null, Decision.ASK)));
        request("a1", ActionCategory.EXECUTE, "Bash", "docker restart gateway");

        List<EventEnvelope> events =
                f.record(f.company.actions().recordOutcome(f.system(), new ActionId("a1"), true, "gateway"));

        assertThat(events)
                .extracting(EventEnvelope::type)
                .containsExactly("ActionExecuted", "IncidentRaised", "MissionSuspended");
        assertThat(action("a1").authorizedByOffice()).isFalse();
        assertThat(f.company.actions().incidents())
                .singleElement()
                .extracting(Incident::criticality)
                .isEqualTo(Criticality.CRITICAL);
        assertThat(f.company.missions().find(m).orElseThrow().status()).isEqualTo(MissionStatus.SUSPENDED);
    }

    @Test
    void aCallRefusedByClaudeCodeBeforeTheHookIsRecordedAsBlocked() {
        // Seen live on M-7: Haiku called Bash, which the review session does not offer; no hook ran.
        List<EventEnvelope> events = f.record(f.company
                .actions()
                .recordUnseenCall(
                        f.system(),
                        new ActionId("a1"),
                        m,
                        t,
                        CHRIS,
                        ActionCategory.EXECUTE,
                        "Bash",
                        "git log",
                        false,
                        "No such tool available: Bash"));

        assertThat(events).extracting(EventEnvelope::type).containsExactly("ActionRequested", "ActionDenied");
        assertThat(action("a1").denialReason()).isEqualTo(DenialReason.BLOCKED_UPSTREAM);
        assertThat(f.company.missions().find(m).orElseThrow().status()).isEqualTo(MissionStatus.IN_PROGRESS);
    }

    @Test
    void anUnseenCallThatRanIsAnIncident() {
        List<EventEnvelope> events = f.record(f.company
                .actions()
                .recordUnseenCall(
                        f.system(), new ActionId("a1"), m, t, CHRIS, ActionCategory.EXECUTE, "Bash", "rm x", true, ""));

        assertThat(events)
                .extracting(EventEnvelope::type)
                .containsExactly("ActionRequested", "ActionExecuted", "IncidentRaised", "MissionSuspended");
    }

    @Test
    void theResultOfADeniedCallChangesNothing() {
        request("a1", ActionCategory.WRITE, "Write", "notes.txt");

        assertThat(f.company.actions().recordOutcome(f.system(), new ActionId("a1"), false, "denied by hook"))
                .isEmpty();
    }

    @Test
    void onlyTheRuntimeReportsOutcomes() {
        f.allow(m, ActionCategory.EXECUTE, null, null);
        request("a1", ActionCategory.EXECUTE, "Bash", "mvn test");

        assertThatThrownBy(
                        () -> f.company.actions().recordOutcome(f.agent(CHRIS), new ActionId("a1"), true, "all green"))
                .extracting("code")
                .isEqualTo("FORBIDDEN");
    }

    @Test
    void anAgentCannotConfirmItsOwnResult() {
        f.allow(m, ActionCategory.EXECUTE, null, null);
        request("a1", ActionCategory.EXECUTE, "Bash", "mvn test");
        f.record(f.company.actions().recordOutcome(f.system(), new ActionId("a1"), true, "BUILD SUCCESS"));

        assertThatThrownBy(() -> f.company.actions().confirm(f.agent(CHRIS), new ActionId("a1"), "I checked"))
                .extracting("code")
                .isEqualTo("SELF_CONFIRMATION");

        f.record(f.company.actions().confirm(f.agent(DARIUS), new ActionId("a1"), "re-ran: 48 tests green"));
        assertThat(action("a1").stage()).isEqualTo(ActionStage.CONFIRMED);
    }

    @Test
    void aRuntimeCannotRecordTheSameActionTwice() {
        request("a1", ActionCategory.READ, "Read", "pom.xml");

        assertThatThrownBy(() -> request("a1", ActionCategory.READ, "Read", "pom.xml"))
                .extracting("code")
                .isEqualTo("DUPLICATE_ACTION");
    }

    @Test
    void everyEventCarriesTheMissionOrigin() {
        f.allow(m, ActionCategory.READ, null, null);
        request("a1", ActionCategory.READ, "Read", "pom.xml");

        assertThat(f.journal).allMatch(e -> e.origin() == Origin.SIMULATED);
        assertThat(f.journal)
                .filteredOn(e -> e.event() instanceof ActionEvent.ActionRequested)
                .singleElement()
                .extracting(EventEnvelope::mission)
                .isEqualTo(m);
    }
}
