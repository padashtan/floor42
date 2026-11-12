package dev.floor42.domain;

import static dev.floor42.domain.Fixture.DARIUS;
import static dev.floor42.domain.Fixture.GALEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.Workflow.Step;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A decision workflow, one that produces nothing (ADR-0050): its ruling never offers "adopt and implement", since no
 * step would implement it; the built-in workflow still offers it.
 */
@ExtendWith(GoldenFixtureExtension.class)
class DecisionWorkflowTest {

    private static final Workflow DECIDE =
            new Workflow("Decide", List.of(new Step.Approval(), new Step.Committee(), new Step.Accept()));

    final Fixture f = new Fixture(Origin.REAL);

    private Question ruling(Workflow w) {
        MissionId m = ((MissionCreated)
                        f.record(f.company.missions().create(f.director(), "Gateway?", "Decide", "C:/repo", w))
                                .getFirst()
                                .event())
                .mission();
        TicketId ticket = f.ticket(m, "Decide: Gateway?");
        f.record(f.company.tickets().assign(f.director(), ticket, GALEN));
        f.record(f.company
                .committees()
                .convene(
                        f.director(), m, ticket, "Gateway?", List.of(GALEN, DARIUS), List.of("Architect", "Security")));
        f.record(f.company.missions().submitPlan(f.agent(GALEN), m));
        f.record(f.company.verification().setBudget(f.director(), m, null));
        f.record(f.company.missions().approvePlan(f.director(), m));
        f.record(f.company.tickets().start(f.agent(GALEN), ticket));
        for (Committee.Kind kind : List.of(Committee.Kind.ANALYSIS, Committee.Kind.REBUTTAL)) {
            say(m, GALEN, kind);
            say(m, DARIUS, kind);
        }
        say(m, GALEN, Committee.Kind.SYNTHESIS);
        return f.company.desk().open().stream()
                .filter(q -> q.kind() == QuestionKind.COMMITTEE_ARBITRATION)
                .findFirst()
                .orElseThrow();
    }

    private void say(MissionId m, AgentId who, Committee.Kind kind) {
        f.record(f.company
                .committees()
                .contribute(
                        f.agent(who), new CommitteeRef(m, 1), who, kind, "# " + kind, "Adopt a gateway", null, null));
    }

    @Test
    void aWorkflowThatProducesNothingNeverOffersToImplement() {
        Question q = ruling(DECIDE);

        assertThat(q.options()).containsExactly("Adopt", "Reject");
        assertThatThrownBy(() ->
                        f.company.desk().answer(f.director(), q.id(), "Adopt and implement (writes ask you first)"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void theBuiltInWorkflowStillOffersToImplement() {
        assertThat(ruling(Workflow.BUILT_IN).options())
                .containsExactly("Adopt", "Adopt and implement (writes ask you first)", "Reject");
    }
}
