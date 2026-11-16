package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.workflow.CompanyWorkflow;
import dev.floor42.domain.workflow.MissionWorkflow;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.Workflow.Step;
import dev.floor42.domain.workflow.WorkflowProposal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The company's workflows, written by the director, proposed by the planner (ADR-0050). */
@ExtendWith(GoldenFixtureExtension.class)
class CompanyWorkflowsTest {

    final Fixture f = new Fixture(Origin.REAL);

    private static Workflow named(String name, Step... steps) {
        return new Workflow(name, List.of(steps));
    }

    private static Workflow light(String name) {
        return named(
                name, new Step.Plan(2), new Step.Approval(), new Step.Produce(), new Step.Review(), new Step.Accept());
    }

    private static Workflow decide(String name) {
        return named(name, new Step.Approval(), new Step.Committee(), new Step.Accept());
    }

    @Test
    void theDirectorSavesAWorkflowAndReplacesItOnlyWhenAsked() {
        f.record(f.company.workflows().save(f.director(), light("Light"), false, null));

        assertThatThrownBy(() -> f.company.workflows().save(f.director(), decide("light"), false, null))
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("WORKFLOW_EXISTS"));
        f.record(f.company.workflows().save(f.director(), decide("LIGHT"), true, null));

        assertThat(f.company.workflows().companyWorkflows()).hasSize(1);
        CompanyWorkflow w = f.company.workflows().named("light").orElseThrow();
        assertThat(w.name()).isEqualTo("LIGHT");
        assertThat(w.workflow()).contains(decide("LIGHT"));
    }

    @Test
    void onlyTheDirectorWritesTheCompanysWorkflows() {
        assertThatThrownBy(() -> f.company.workflows().save(f.agent(Fixture.GEOFFREY), light("Light"), false, null))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> f.company.workflows().remove(f.agent(Fixture.GEOFFREY), "Light"))
                .isInstanceOf(DomainException.class);
    }

    /** NFKC and case folding: a lookalike of an existing name, or of the built-in one, is the same name. */
    @Test
    void namesAreLatinAndComparedAfterNormalisation() {
        assertThat(CompanyWorkflow.nameRefusal("built-in")).isPresent();
        assertThat(CompanyWorkflow.nameRefusal("\uff22uilt-in")).isPresent(); // fullwidth B
        assertThat(CompanyWorkflow.nameRefusal("BUILT-IN ")).isPresent();
        assertThat(CompanyWorkflow.nameRefusal("\u0432uilt"))
                .hasValueSatisfying(r -> assertThat(r).contains("letters")); // Cyrillic
        assertThat(CompanyWorkflow.nameRefusal("x".repeat(61))).isPresent();
        assertThat(CompanyWorkflow.nameRefusal("Revue l\u00e9g\u00e8re 2")).isEmpty();
        assertThat(CompanyWorkflow.key("\uff2c\uff49\uff47\uff48\uff54")).isEqualTo("light");
        assertThatThrownBy(() -> f.company.workflows().save(f.director(), light("built-in"), true, null))
                .hasMessageContaining("Floor42's own workflow");
    }

    @Test
    void removingOrEditingACompanyWorkflowNeverChangesAPinnedMission() {
        f.record(f.company.workflows().save(f.director(), light("Light"), false, null));
        Workflow chosen =
                f.company.workflows().named("Light").orElseThrow().workflow().orElseThrow();
        MissionId m = ((MissionCreated) f.record(f.company.missions().create(f.director(), "t", "o", "C:/repo", chosen))
                        .getFirst()
                        .event())
                .mission();

        f.record(f.company.workflows().save(f.director(), decide("Light"), true, null));
        assertThat(f.company.workflows().of(m)).isEqualTo(new MissionWorkflow.Pinned(light("Light")));
        f.record(f.company.workflows().remove(f.director(), "Light"));
        assertThat(f.company.workflows().companyWorkflows()).isEmpty();
        assertThat(f.company.workflows().of(m)).isEqualTo(new MissionWorkflow.Pinned(light("Light")));
        assertThatThrownBy(() -> f.company.workflows().remove(f.director(), "Light"))
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("UNKNOWN_WORKFLOW"));
    }

    @Test
    void thePlannerProposesAndTheDirectorAdoptsOrDismisses() {
        MissionId m = mission();
        assertThatThrownBy(() -> f.company.workflows().propose(f.director(), m, light("Mine")))
                .hasMessageContaining("Only the planner");
        // Another agent of the mission is not its planner.
        assertThatThrownBy(() -> f.company.workflows().propose(f.agent(Fixture.CHRIS), m, light("Mine")))
                .hasMessageContaining("Only the planner of " + m);
        f.record(f.company.workflows().propose(f.agent(Fixture.GEOFFREY), m, light("Quick")));
        f.record(f.company.workflows().propose(f.agent(Fixture.GEOFFREY), m, decide("Talk")));

        List<WorkflowProposal> proposals = f.company.workflows().proposals();
        assertThat(proposals).extracting(WorkflowProposal::number).containsExactly(1, 2);
        assertThat(proposals.getFirst().by()).isEqualTo("geoffrey");
        assertThat(proposals.getFirst().workflow()).contains(light("Quick"));

        // Adopted under the director's own name, edited: saved and closed in one command.
        f.record(f.company.workflows().save(f.director(), decide("Quick edited"), false, 1));
        f.record(f.company.workflows().dismissProposal(f.director(), 2));
        assertThat(f.company.workflows().proposals())
                .extracting(WorkflowProposal::status)
                .containsExactly(WorkflowProposal.Status.ADOPTED, WorkflowProposal.Status.DISMISSED);
        assertThat(f.company.workflows().named("Quick edited")).isPresent();
        assertThatThrownBy(() -> f.company.workflows().dismissProposal(f.director(), 2))
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("PROPOSAL_CLOSED"));
        assertThatThrownBy(() -> f.company.workflows().save(f.director(), light("Other"), false, 9))
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("UNKNOWN_PROPOSAL"));
        // A proposal never changes the mission it was made on.
        assertThat(f.company.workflows().of(m)).isEqualTo(new MissionWorkflow.Pinned(Workflow.BUILT_IN));
    }

    @Test
    void cancellingAMissionDismissesItsOpenProposals() {
        MissionId m = mission();
        f.record(f.company.workflows().propose(f.agent(Fixture.GEOFFREY), m, light("Quick")));

        var batch = f.record(f.company.missions().cancel(f.director(), m, "stop"));

        assertThat(batch.stream().map(e -> e.event().getClass().getSimpleName()))
                .contains("WorkflowProposalClosed");
        assertThat(f.company.workflows().proposals())
                .extracting(WorkflowProposal::status)
                .containsExactly(WorkflowProposal.Status.DISMISSED);
        assertThatThrownBy(() -> f.company.workflows().save(f.director(), light("Other"), false, 1))
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("PROPOSAL_CLOSED"));

        // A journal written before the rule holds the cancellation alone: the proposal still reads as closed.
        Company older = Company.replay(f.journal.stream()
                .filter(e -> !(e.event() instanceof dev.floor42.domain.event.WorkflowEvent.WorkflowProposalClosed))
                .toList());
        assertThat(older.workflows().proposals())
                .extracting(WorkflowProposal::status)
                .containsExactly(WorkflowProposal.Status.DISMISSED);
        assertThatThrownBy(() -> older.workflows().dismissProposal(f.director(), 1))
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("MISSION_CLOSED"));
    }

    @Test
    void aProposalWithANameTheDirectorCouldNotGiveIsRefused() {
        MissionId m = mission();
        assertThatThrownBy(() -> f.company.workflows().propose(f.agent(Fixture.GEOFFREY), m, light("built-in")))
                .hasMessageContaining("Floor42's own workflow");
    }

    /** A mission whose planning ticket is Geoffrey's: he is its planner. */
    private MissionId mission() {
        MissionId m = ((MissionCreated)
                        f.record(f.company.missions().create(f.director(), "t", "o", "C:/repo", Workflow.BUILT_IN))
                                .getFirst()
                                .event())
                .mission();
        TicketId plan = ((dev.floor42.domain.event.TicketEvent.TicketCreated) f.record(f.company
                                .tickets()
                                .create(
                                        f.director(),
                                        m,
                                        "Plan: t",
                                        "o",
                                        Priority.NORMAL,
                                        List.of(),
                                        List.of("c"),
                                        TicketKind.PLANNING))
                        .getFirst()
                        .event())
                .ticket();
        f.record(f.company.tickets().assign(f.director(), plan, Fixture.GEOFFREY));
        return m;
    }
}
