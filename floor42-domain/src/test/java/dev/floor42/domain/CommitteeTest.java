package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.DEHREN;
import static dev.floor42.domain.Fixture.GEOFFREY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.workflow.Workflow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The architecture committee (spec section 10): independent analyses, debate, record, director's ruling. */
@ExtendWith(GoldenFixtureExtension.class)
class CommitteeTest {

    final Fixture f = new Fixture(Origin.REAL);
    final MissionId m = decisionMission();
    final TicketId t = f.firstTicket(m);

    MissionId decisionMission() {
        MissionId id = ((MissionCreated) f.record(f.company
                                .missions()
                                .create(
                                        f.director(),
                                        "Gateway?",
                                        "Should we put a gateway in front of the APIs?",
                                        "C:/repo",
                                        Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        TicketId ticket = f.ticket(id, "Decide: Gateway?");
        f.record(f.company.tickets().assign(f.director(), ticket, GEOFFREY));
        f.record(f.company
                .committees()
                .convene(
                        f.director(),
                        id,
                        ticket,
                        "Should we put a gateway in front of the APIs?",
                        List.of(GEOFFREY, DEHREN, CHRIS),
                        List.of("Software architect", "Security specialist", "Senior developer")));
        f.record(f.company.missions().submitPlan(f.agent(GEOFFREY), id));
        f.record(f.company.verification().setBudget(f.director(), id, null));
        f.record(f.company.missions().approvePlan(f.director(), id));
        f.record(f.company.tickets().start(f.agent(GEOFFREY), ticket));
        return id;
    }

    Committee committee() {
        return f.company.committees().latest(m).orElseThrow();
    }

    void say(AgentId who, Committee.Kind kind, String proposal, String objection) {
        f.record(f.company
                .committees()
                .contribute(
                        f.agent(who),
                        new CommitteeRef(m, 1),
                        who,
                        kind,
                        "Analysis by " + who,
                        proposal,
                        "NEW",
                        objection));
    }

    void analysisRound() {
        say(GEOFFREY, Committee.Kind.ANALYSIS, "Gateway", null);
        say(DEHREN, Committee.Kind.ANALYSIS, "Gateway with mTLS", "Tokens are not validated per API");
        say(CHRIS, Committee.Kind.ANALYSIS, "Gateway", null);
    }

    @Test
    void membersSitAndCanUseToolsWhileDeliberating() {
        assertThat(f.company.queries().statusOf(DEHREN)).isEqualTo(AgentStatus.IN_COMMITTEE);
        f.allow(m, ActionCategory.READ, null, null);
        f.record(f.company
                .actions()
                .request(f.agent(DEHREN), new ActionId("a1"), m, t, DEHREN, ActionCategory.READ, "Read", "pom.xml"));

        assertThat(f.company.actions().find(new ActionId("a1")).orElseThrow().stage())
                .isEqualTo(ActionStage.AUTHORIZED);
    }

    @Test
    void theAnalysisRoundEndsWhenEveryMemberHasSpoken() {
        say(GEOFFREY, Committee.Kind.ANALYSIS, "Gateway", null);
        assertThat(committee().pending()).containsExactly(DEHREN, CHRIS);
        assertThatThrownBy(() -> f.company
                        .committees()
                        .contribute(
                                f.agent(GEOFFREY),
                                new CommitteeRef(m, 1),
                                GEOFFREY,
                                Committee.Kind.ANALYSIS,
                                "again",
                                null,
                                null,
                                null))
                .hasMessageContaining("owes no contribution");
        assertThatThrownBy(() -> f.company
                        .committees()
                        .contribute(
                                f.agent(CHRIS),
                                new CommitteeRef(m, 1),
                                DEHREN,
                                Committee.Kind.ANALYSIS,
                                "for them",
                                null,
                                null,
                                null))
                .hasMessageContaining("made by its member");

        say(DEHREN, Committee.Kind.ANALYSIS, "Gateway", null);
        say(CHRIS, Committee.Kind.ANALYSIS, "Gateway", null);

        assertThat(committee().stage()).isEqualTo(Committee.Stage.DEBATE);
        assertThat(committee().round()).isEqualTo(2);
        assertThatThrownBy(() -> f.company
                        .committees()
                        .contribute(
                                f.agent(GEOFFREY),
                                new CommitteeRef(m, 1),
                                GEOFFREY,
                                Committee.Kind.ANALYSIS,
                                "late",
                                null,
                                null,
                                null))
                .hasMessageContaining("Expected a REBUTTAL");
    }

    @Test
    void aStandingCriticalObjectionCannotBeOutvoted() {
        analysisRound();
        say(GEOFFREY, Committee.Kind.REBUTTAL, "Gateway", null);
        say(DEHREN, Committee.Kind.REBUTTAL, "Gateway with mTLS", "Tokens are still not validated per API");
        say(CHRIS, Committee.Kind.REBUTTAL, "Gateway", null);
        assertThat(committee().stage()).isEqualTo(Committee.Stage.SYNTHESIS);
        assertThat(committee().pending()).containsExactly(GEOFFREY);

        f.record(f.company
                .committees()
                .contribute(
                        f.agent(GEOFFREY),
                        new CommitteeRef(m, 1),
                        GEOFFREY,
                        Committee.Kind.SYNTHESIS,
                        "# Decision record",
                        "Adopt a gateway",
                        null,
                        null));

        assertThat(committee().stage()).isEqualTo(Committee.Stage.AWAITING_DIRECTOR);
        Question q = f.company.desk().open().getFirst();
        assertThat(q.kind()).isEqualTo(QuestionKind.COMMITTEE_ARBITRATION);
        assertThat(q.options())
                .containsExactly(
                        "Adopt despite the critical objection",
                        "Adopt and implement (writes ask you first) despite the critical objection",
                        "Reject");
        assertThat(q.context()).contains("dehren (Security specialist): Tokens are still not validated per API");
        assertThat(f.company.queries().statusOf(DEHREN)).isNotEqualTo(AgentStatus.IN_COMMITTEE);

        f.record(f.company.desk().answer(f.director(), q.id(), "Adopt despite the critical objection"));

        assertThat(committee().stage()).isEqualTo(Committee.Stage.ADOPTED);
        assertThat(f.company.tickets().find(t).orElseThrow().status()).isEqualTo(TicketStatus.DONE);
        assertThat(f.journal.getLast().event()).isEqualTo(new dev.floor42.domain.event.TicketEvent.TicketCompleted(t));
        assertThat(f.journal).extracting(EventEnvelopeType::of).contains("CommitteeArbitrated");
    }

    @Test
    void aWithdrawnObjectionNoLongerStands() {
        analysisRound();
        assertThat(committee().standingCriticalObjections()).hasSize(1);

        say(GEOFFREY, Committee.Kind.REBUTTAL, "Gateway with mTLS", null);
        say(DEHREN, Committee.Kind.REBUTTAL, "Gateway with mTLS", null);

        assertThat(committee().standingCriticalObjections()).isEmpty();
    }

    @Test
    void theDirectorSendsTheDecisionBackWithAConstraint() {
        analysisRound();
        say(GEOFFREY, Committee.Kind.REBUTTAL, "Gateway", null);
        say(DEHREN, Committee.Kind.REBUTTAL, "Gateway", null);
        say(CHRIS, Committee.Kind.REBUTTAL, "Gateway", null);
        f.record(f.company
                .committees()
                .contribute(
                        f.agent(GEOFFREY),
                        new CommitteeRef(m, 1),
                        GEOFFREY,
                        Committee.Kind.SYNTHESIS,
                        "# Record",
                        "Adopt a gateway",
                        null,
                        null));
        Question q = f.company.desk().open().getFirst();
        assertThat(q.options()).containsExactly("Adopt", "Adopt and implement (writes ask you first)", "Reject");

        f.record(f.company.committees().sendBack(f.director(), new CommitteeRef(m, 1), "No new paid product"));

        assertThat(f.company.desk().find(q.id()).orElseThrow().status()).isEqualTo(QuestionStatus.WITHDRAWN);
        assertThat(committee().stage()).isEqualTo(Committee.Stage.DEBATE);
        assertThat(committee().round()).isEqualTo(3);
        assertThat(committee().constraints()).containsExactly("No new paid product");
        assertThat(committee().pending()).containsExactly(GEOFFREY, DEHREN, CHRIS);
    }

    @Test
    void rejectingCancelsTheDecisionTicket() {
        analysisRound();
        for (AgentId a : List.of(GEOFFREY, DEHREN, CHRIS)) say(a, Committee.Kind.REBUTTAL, "Gateway", null);
        f.record(f.company
                .committees()
                .contribute(
                        f.agent(GEOFFREY),
                        new CommitteeRef(m, 1),
                        GEOFFREY,
                        Committee.Kind.SYNTHESIS,
                        "# Record",
                        "x",
                        null,
                        null));

        f.record(f.company
                .desk()
                .answer(f.director(), f.company.desk().open().getFirst().id(), "Reject"));

        assertThat(committee().stage()).isEqualTo(Committee.Stage.REJECTED);
        assertThat(f.company.tickets().find(t).orElseThrow().status()).isEqualTo(TicketStatus.CANCELLED);
    }

    @Test
    void aCommitteeIsPartOfThePlanAndNeedsTwoMembers() {
        MissionId other = ((MissionCreated)
                        f.record(f.company.missions().create(f.director(), "x", "y", "C:/repo", Workflow.BUILT_IN))
                                .getFirst()
                                .event())
                .mission();
        TicketId ticket = f.ticket(other, "Decide");
        f.record(f.company.tickets().assign(f.director(), ticket, GEOFFREY));

        assertThatThrownBy(() -> f.company
                        .committees()
                        .convene(f.director(), other, ticket, "x", List.of(GEOFFREY), List.of("Software architect")))
                .hasMessageContaining("at least two");
        assertThatThrownBy(() -> f.company
                        .committees()
                        .convene(f.director(), other, ticket, "x", List.of(DEHREN, GEOFFREY), List.of("a", "b")))
                .hasMessageContaining("chair");
        assertThatThrownBy(() -> f.company
                        .committees()
                        .convene(f.director(), m, t, "x", List.of(GEOFFREY, DEHREN), List.of("a", "b")))
                .hasMessageContaining("part of the plan");
    }

    @Test
    void anObjectionToARuledDecisionWithoutImplementationIsRefusedBeforeAnyEvent() {
        analysisRound();
        for (AgentId a : List.of(GEOFFREY, DEHREN, CHRIS)) say(a, Committee.Kind.REBUTTAL, "Gateway", null);
        say(GEOFFREY, Committee.Kind.SYNTHESIS, "Adopt a gateway", null);
        Question q = f.company.desk().open().getFirst();
        f.record(f.company.desk().answer(f.director(), q.id(), q.options().getFirst()));
        int before = f.journal.size();
        long version = f.company.version();

        assertThatThrownBy(() -> f.company
                        .missions()
                        .object(f.director(), m, List.of(new Objection(null, "first"), new Objection(t, "second"))))
                .extracting("code")
                .isEqualTo("DECIDED_TICKET");

        assertThat(f.journal).hasSize(before);
        assertThat(f.company.version()).isEqualTo(version);
        assertThat(f.company.tickets().of(m)).hasSize(1);
    }

    @Test
    void theCommitteeSurvivesAReplay() {
        analysisRound();

        Committee replayed = Company.replay(f.journal).committees().latest(m).orElseThrow();

        assertThat(replayed.stage()).isEqualTo(Committee.Stage.DEBATE);
        assertThat(replayed.contributions()).hasSize(3);
        assertThat(replayed.roleOf(DEHREN)).isEqualTo("Security specialist");
        assertThat(replayed.chair()).isEqualTo(GEOFFREY);
    }

    /** An idea committee: Geoffrey moderates, Dehren and Chris debate, Chris's seat is the director's double. */
    MissionId ideaMission(int rounds) {
        MissionId id = ((MissionCreated) f.record(f.company
                                .missions()
                                .create(
                                        f.director(),
                                        "Idea",
                                        "A marketplace for second-hand tools?",
                                        "C:/repo",
                                        Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        TicketId ticket = f.ticket(id, "Decide: Idea");
        f.record(f.company.tickets().assign(f.director(), ticket, GEOFFREY));
        f.record(f.company
                .committees()
                .convene(
                        f.director(),
                        id,
                        ticket,
                        "A marketplace for second-hand tools?",
                        List.of(GEOFFREY, DEHREN, CHRIS),
                        List.of("Moderator", "Lawyer", "Your double"),
                        new Committee.Format("idea", true, rounds, CHRIS)));
        f.record(f.company.missions().submitPlan(f.agent(GEOFFREY), id));
        f.record(f.company.verification().setBudget(f.director(), id, null));
        f.record(f.company.missions().approvePlan(f.director(), id));
        f.record(f.company.tickets().start(f.agent(GEOFFREY), ticket));
        return id;
    }

    void speak(MissionId id, AgentId who, Committee.Kind kind, String position, String objection) {
        f.record(f.company
                .committees()
                .contribute(
                        f.agent(who),
                        new CommitteeRef(id, 1),
                        who,
                        kind,
                        "By " + who,
                        "P-" + who,
                        position,
                        objection));
    }

    @Test
    void aModeratorNeitherAnalysesNorDebatesAndTheDebateGoesOnUntilAgreement() {
        MissionId idea = ideaMission(3);
        Committee c = f.company.committees().latest(idea).orElseThrow();
        assertThat(c.pending()).containsExactly(DEHREN, CHRIS);

        speak(idea, DEHREN, Committee.Kind.ANALYSIS, "NEW", null);
        speak(idea, CHRIS, Committee.Kind.ANALYSIS, "NEW", null);
        speak(idea, DEHREN, Committee.Kind.REBUTTAL, "MAINTAINED", null);
        speak(idea, CHRIS, Committee.Kind.REBUTTAL, "MAINTAINED", null);
        // Two proposals still stand: another round, not the record.
        assertThat(c.stage()).isEqualTo(Committee.Stage.DEBATE);
        assertThat(c.round()).isEqualTo(3);

        speak(idea, DEHREN, Committee.Kind.REBUTTAL, "MAINTAINED", null);
        speak(idea, CHRIS, Committee.Kind.REBUTTAL, "ADOPTED the proposal of dehren", null);

        assertThat(c.agreement()).contains(DEHREN);
        assertThat(c.stage()).isEqualTo(Committee.Stage.SYNTHESIS);
        assertThat(c.pending()).containsExactly(GEOFFREY);
        f.record(f.company
                .committees()
                .contribute(
                        f.agent(GEOFFREY),
                        new CommitteeRef(idea, 1),
                        GEOFFREY,
                        Committee.Kind.SYNTHESIS,
                        "# Record",
                        "Go",
                        null,
                        null));
        assertThat(f.company.desk().open().getFirst().context()).contains("agree on dehren's proposal");
    }

    @Test
    void withoutAgreementTheRecordComesAfterTheLastRoundAndSaysSo() {
        MissionId idea = ideaMission(2);
        Committee c = f.company.committees().latest(idea).orElseThrow();
        speak(idea, DEHREN, Committee.Kind.ANALYSIS, "NEW", null);
        speak(idea, CHRIS, Committee.Kind.ANALYSIS, "NEW", null);
        for (int round = 0; round < 2; round++) {
            speak(idea, DEHREN, Committee.Kind.REBUTTAL, "ADOPTED chris", "Liability for defective tools");
            speak(idea, CHRIS, Committee.Kind.REBUTTAL, "MAINTAINED", null);
        }
        // Everyone stands behind Chris, but a critical objection stands: that is not agreement.
        assertThat(c.agreement()).isEmpty();
        assertThat(c.stage()).isEqualTo(Committee.Stage.SYNTHESIS);
        f.record(f.company
                .committees()
                .contribute(
                        f.agent(GEOFFREY),
                        new CommitteeRef(idea, 1),
                        GEOFFREY,
                        Committee.Kind.SYNTHESIS,
                        "# Record",
                        "Go",
                        null,
                        null));
        assertThat(f.company.desk().open().getFirst().context())
                .contains("did not all agree after 2 debate round(s)")
                .contains("Liability for defective tools");
    }

    @Test
    void theDirectorSpeaksInPersonInTheSeatOfTheirDouble() {
        MissionId idea = ideaMission(1);
        Committee c = f.company.committees().latest(idea).orElseThrow();
        assertThatThrownBy(() -> f.company
                        .committees()
                        .contributeAsDirector(f.agent(DEHREN), new CommitteeRef(idea, 1), "me", null, null, null))
                .isInstanceOf(DomainException.class);

        f.record(f.company
                .committees()
                .contributeAsDirector(
                        f.director(), new CommitteeRef(idea, 1), "My own analysis", "Rent, not sell", "NEW", null));
        // The double does not speak this round: its seat is taken.
        assertThat(c.pending()).containsExactly(DEHREN);
        speak(idea, DEHREN, Committee.Kind.ANALYSIS, "NEW", null);
        assertThat(c.stage()).isEqualTo(Committee.Stage.DEBATE);

        speak(idea, CHRIS, Committee.Kind.REBUTTAL, "MAINTAINED", null);
        f.record(f.company
                .committees()
                .contributeAsDirector(
                        f.director(), new CommitteeRef(idea, 1), "Changed my mind", null, "ADOPTED dehren", null));
        speak(idea, DEHREN, Committee.Kind.REBUTTAL, "MAINTAINED", null);

        Committee.Contribution mine = c.latestPositions().stream()
                .filter(x -> x.member().equals(CHRIS))
                .findFirst()
                .orElseThrow();
        assertThat(mine.byDirector()).isTrue();
        assertThat(mine.text()).isEqualTo("Changed my mind");
        assertThat(c.agreement()).contains(DEHREN);
        assertThat(Company.replay(f.journal)
                        .committees()
                        .latest(idea)
                        .orElseThrow()
                        .latestPositions())
                .anyMatch(Committee.Contribution::byDirector);
    }

    @Test
    void theDirectorsSeatIsAMembersNotTheModerators() {
        MissionId other = ((MissionCreated)
                        f.record(f.company.missions().create(f.director(), "x", "y", "C:/repo", Workflow.BUILT_IN))
                                .getFirst()
                                .event())
                .mission();
        TicketId ticket = f.ticket(other, "Decide");
        f.record(f.company.tickets().assign(f.director(), ticket, GEOFFREY));
        assertThatThrownBy(() -> f.company
                        .committees()
                        .convene(
                                f.director(),
                                other,
                                ticket,
                                "x",
                                List.of(GEOFFREY, DEHREN, CHRIS),
                                List.of("a", "b", "c"),
                                new Committee.Format("idea", true, 3, GEOFFREY)))
                .hasMessageContaining("not the chair's");
        assertThatThrownBy(() -> f.company
                        .committees()
                        .convene(
                                f.director(),
                                other,
                                ticket,
                                "x",
                                List.of(GEOFFREY, DEHREN),
                                List.of("a", "b"),
                                new Committee.Format("idea", true, 3, null)))
                .hasMessageContaining("besides the moderator");
    }

    /** The simple name of an event's type, for readable assertions on the journal. */
    static final class EventEnvelopeType {
        static String of(dev.floor42.domain.event.EventEnvelope e) {
            return e.event().getClass().getSimpleName();
        }
    }
}
