package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.DARIUS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.RosterEvent.AgentTrustChanged;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Probation (ADR-0082): narrows by itself on an incident, widens only on the director's proven click. */
class ProbationTest {

    final Fixture f = new Fixture(Origin.REAL);
    final MissionId m = f.runningMission();
    final TicketId t = f.firstTicket(m);

    Agent chris() {
        return f.company.staff().find(CHRIS).orElseThrow();
    }

    /** An EXECUTE call nobody authorized that Claude Code ran anyway: an incident. */
    List<EventEnvelope> incident(String id) {
        f.record(f.company
                .missions()
                .addPermission(f.director(), m, new PermissionRule(null, ActionCategory.EXECUTE, null, Decision.ASK)));
        f.record(f.company
                .actions()
                .request(f.agent(CHRIS), new ActionId(id), m, t, CHRIS, ActionCategory.EXECUTE, "Bash", "rm -rf x"));
        return f.record(f.company.actions().recordOutcome(f.system(), new ActionId(id), true, "done"));
    }

    void acceptPatch(int n) {
        f.record(f.company
                .workshop()
                .prepareWorkspace(f.system(), m, CHRIS, "C:/w/M-1-chris", "floor42/m-1-chris", "abc123"));
        for (int i = 0; i < n; i++) {
            f.record(f.company.workshop().acceptPatch(f.director(), m, CHRIS, "c" + i, "msg", List.of("a.txt")));
        }
    }

    @Test
    void anIncidentLowersAtOnce() {
        List<EventEnvelope> events = incident("a1");

        assertThat(events)
                .extracting(EventEnvelope::type)
                .containsExactly("ActionExecuted", "IncidentRaised", "MissionSuspended", "AgentTrustChanged");
        EventEnvelope changed = events.getLast();
        assertThat(changed.actor()).isEqualTo(Actor.system());
        assertThat(changed.event())
                .isEqualTo(new AgentTrustChanged(
                        CHRIS,
                        TrustLevel.STANDARD,
                        TrustLevel.PROBATION,
                        TrustCause.INCIDENT,
                        events.get(1).eventId()));
        assertThat(chris().trust()).isEqualTo(TrustLevel.PROBATION);
        assertThat(chris().trustCause()).isEqualTo(TrustCause.INCIDENT);
    }

    @Test
    void aSecondIncidentGoesToShadowAndAThirdChangesNothing() {
        incident("a1");
        f.record(f.company.missions().resume(f.director(), m));
        incident2("a2");
        assertThat(chris().trust()).isEqualTo(TrustLevel.SHADOW);

        f.record(f.company.missions().resume(f.director(), m));
        List<EventEnvelope> third = incident2("a3");

        assertThat(third).extracting(EventEnvelope::type).doesNotContain("AgentTrustChanged");
        assertThat(chris().trust()).isEqualTo(TrustLevel.SHADOW);
    }

    /** The table already asks for it: only the second request of a mission needs no new rule. */
    private List<EventEnvelope> incident2(String id) {
        f.record(f.company
                .actions()
                .request(f.agent(CHRIS), new ActionId(id), m, t, CHRIS, ActionCategory.EXECUTE, "Bash", "rm -rf y"));
        return f.record(f.company.actions().recordOutcome(f.system(), new ActionId(id), true, "done"));
    }

    @Test
    void aRiskAtAlertAndAQuarantineEachLowerByOne() {
        f.record(f.company.sessions().start(f.system(), "s1", t, CHRIS, "claude-sonnet-5-5", null));
        List<EventEnvelope> notice = f.record(f.company
                .sessions()
                .riskRaised(
                        f.system(),
                        "s1",
                        RiskLevel.NOTICE,
                        List.of(RiskReason.POLICY_DENIED),
                        java.time.Instant.EPOCH,
                        List.of()));
        assertThat(notice).extracting(EventEnvelope::type).doesNotContain("AgentTrustChanged");

        List<EventEnvelope> alert = f.record(f.company
                .sessions()
                .riskRaised(
                        f.system(),
                        "s1",
                        RiskLevel.ALERT,
                        List.of(RiskReason.BURST),
                        java.time.Instant.EPOCH,
                        List.of()));
        assertThat(((AgentTrustChanged) alert.getLast().event()).cause()).isEqualTo(TrustCause.RISK);
        assertThat(chris().trust()).isEqualTo(TrustLevel.PROBATION);

        List<EventEnvelope> quarantine =
                f.record(f.company.sessions().quarantined(f.director(), "s1", true, true, true));
        assertThat(((AgentTrustChanged) quarantine.getLast().event()).cause()).isEqualTo(TrustCause.QUARANTINE);
        assertThat(quarantine.getLast().actor()).isEqualTo(Actor.system());
        assertThat(chris().trust()).isEqualTo(TrustLevel.SHADOW);
    }

    @Test
    void aSimulatedIncidentLowersNothing() {
        Fixture sim = new Fixture();
        MissionId sm = sim.runningMission();
        TicketId st = sim.firstTicket(sm);
        sim.record(sim.company
                .missions()
                .addPermission(
                        sim.director(), sm, new PermissionRule(null, ActionCategory.EXECUTE, null, Decision.ASK)));
        sim.record(sim.company
                .actions()
                .request(sim.agent(CHRIS), new ActionId("a1"), sm, st, CHRIS, ActionCategory.EXECUTE, "Bash", "x"));
        sim.record(sim.company.actions().recordOutcome(sim.system(), new ActionId("a1"), true, "done"));

        assertThat(sim.company.actions().incidents()).hasSize(1);
        assertThat(sim.company.staff().find(CHRIS).orElseThrow().trust()).isEqualTo(TrustLevel.STANDARD);
    }

    @Test
    void fiveAcceptedPatchesProposeARaise() {
        f.record(f.company.staff().lowerTrust(f.director(), CHRIS, TrustLevel.PROBATION));
        assertThat(Probation.ACCEPTED_FOR_RAISE).isEqualTo(5);

        acceptPatch(Probation.ACCEPTED_FOR_RAISE - 1);
        assertThat(chris().trustRaiseProposed()).isFalse();
        f.record(f.company.workshop().acceptPatch(f.director(), m, CHRIS, "c9", "msg", List.of("a.txt")));

        assertThat(chris().trustRaiseProposed()).isTrue();
        assertThat(chris().trust()).isEqualTo(TrustLevel.PROBATION);
    }

    @Test
    void reviewsNeverRaiseWithoutTheDirector() {
        f.record(f.company.staff().lowerTrust(f.director(), CHRIS, TrustLevel.PROBATION));
        acceptPatch(Probation.ACCEPTED_FOR_RAISE);

        assertThat(chris().trust()).isEqualTo(TrustLevel.PROBATION);
        assertThatThrownBy(() -> f.company.staff().raiseTrust(f.director(), CHRIS, TrustLevel.STANDARD, false))
                .extracting("code")
                .isEqualTo("TRUST_RAISE_NEEDS_STEP_UP");
        assertThatThrownBy(() -> f.company.staff().raiseTrust(f.agent(CHRIS), CHRIS, TrustLevel.STANDARD, true))
                .extracting("code")
                .isEqualTo("DIRECTOR_ONLY");
        assertThat(chris().trust()).isEqualTo(TrustLevel.PROBATION);

        f.record(f.company.staff().raiseTrust(f.director(), CHRIS, TrustLevel.STANDARD, true));

        assertThat(chris().trust()).isEqualTo(TrustLevel.STANDARD);
        assertThat(chris().acceptedSinceTrustChange()).isZero();
        assertThat(chris().trustRaiseProposed()).isFalse();
    }

    @Test
    void anIncidentStartsTheCountAgain() {
        f.record(f.company.staff().lowerTrust(f.director(), CHRIS, TrustLevel.PROBATION));
        acceptPatch(3);
        assertThat(chris().acceptedSinceTrustChange()).isEqualTo(3);

        incident("a1");

        assertThat(chris().trust()).isEqualTo(TrustLevel.SHADOW);
        assertThat(chris().acceptedSinceTrustChange()).isZero();
    }

    @Test
    void anAgentPeerReviewDoesNotCount() {
        f.record(f.company.staff().lowerTrust(f.director(), CHRIS, TrustLevel.PROBATION));
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "Report"));
        f.record(f.company.tickets().review(f.agent(DARIUS), t, ReviewVerdict.VALIDATED, "fine"));

        assertThat(chris().acceptedSinceTrustChange()).isZero();
    }

    @Test
    void aHandChangeOnlyNarrowsAndARaiseOnlyWidens() {
        assertThatThrownBy(() -> f.company.staff().lowerTrust(f.director(), CHRIS, TrustLevel.STANDARD))
                .extracting("code")
                .isEqualTo("INVALID_TRANSITION");
        assertThatThrownBy(() -> f.company.staff().raiseTrust(f.director(), CHRIS, TrustLevel.STANDARD, true))
                .extracting("code")
                .isEqualTo("INVALID_TRANSITION");

        List<EventEnvelope> events = f.record(f.company.staff().lowerTrust(f.director(), CHRIS, TrustLevel.SHADOW));

        assertThat(events.getFirst().event())
                .isEqualTo(new AgentTrustChanged(
                        CHRIS, TrustLevel.STANDARD, TrustLevel.SHADOW, TrustCause.DIRECTOR, null));
    }

    @Test
    void staffAtUpgradeStartsStandard() {
        // A journal written before levels existed holds no AgentTrustChanged: replaying it leaves every agent STANDARD.
        List<EventEnvelope> old = f.journal.stream()
                .filter(e -> !(e.event() instanceof AgentTrustChanged))
                .toList();

        Company replayed = Company.replay(old);

        assertThat(replayed.staff().all()).isNotEmpty().allMatch(a -> a.trust() == TrustLevel.STANDARD);
    }

    @Test
    void aNewHireStartsOnProbation() {
        AgentId eve = new AgentId("eve");

        List<EventEnvelope> events = f.record(f.company
                .staff()
                .hire(f.director(), eve, "Eve", "Developer", WorkRole.DEVELOPER, "claude-sonnet-5-5", null));

        assertThat(events)
                .extracting(EventEnvelope::type)
                .containsExactly("AgentHired", "AgentTrustChanged", "AgentRoleAssigned");
        assertThat(events.get(1).event())
                .isEqualTo(new AgentTrustChanged(
                        eve,
                        TrustLevel.STANDARD,
                        TrustLevel.PROBATION,
                        TrustCause.HIRE,
                        events.getFirst().eventId()));
        assertThat(f.company.staff().find(eve).orElseThrow().trust()).isEqualTo(TrustLevel.PROBATION);
    }

    @Test
    void aSimulatedHireStaysStandard() {
        Fixture sim = new Fixture();

        assertThat(sim.company.staff().all()).allMatch(a -> a.trust() == TrustLevel.STANDARD);
    }
}
