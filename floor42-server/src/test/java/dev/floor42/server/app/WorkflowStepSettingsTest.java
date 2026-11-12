package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Company;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.WorkflowEvent.CompanyWorkflowSaved;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.Workflow.Step;
import dev.floor42.domain.workflow.WorkflowDocument;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the server reads of a pinned workflow (ADR-0050): the plan step's limit reaches the planner's brief and nothing
 * else changes; a company workflow this build cannot read is refused before any mission is created; the planner's
 * proposal line is read once, by Floor42.
 */
class WorkflowStepSettingsTest {

    private static final AgentId GALEN = new AgentId("galen");
    private final Company c = new Company();

    private CommandContext director() {
        return new CommandContext(Actor.director(), Origin.REAL, Instant.parse("2026-11-01T10:00:00Z"), null);
    }

    private Mission mission(Workflow w) {
        if (w == null) {
            // No command of this build creates an unpinned REAL mission: the row is one an older Floor42 wrote.
            MissionId old = new MissionId("M-" + (c.missions().all().size() + 1));
            c.apply(new EventEnvelope(
                    "old-" + old,
                    Instant.parse("2026-11-01T10:00:00Z"),
                    Origin.REAL,
                    Actor.director(),
                    old,
                    null,
                    null,
                    new MissionCreated(old, "Plan it", "Describe", "C:/repo")));
            return c.missions().find(old).orElseThrow();
        }
        List<EventEnvelope> events = c.missions().create(director(), "Plan it", "Describe", "C:/repo", w);
        MissionId m = ((MissionCreated) events.getFirst().event()).mission();
        return c.missions().find(m).orElseThrow();
    }

    @Test
    void thePlanStepsLimitReachesThePlannersBriefAndOnlyIt() {
        c.staff().hire(director(), GALEN, "Galen", "Planner", "anthropic", "claude-sonnet-5-5", "low", List.of());
        var planner = c.staff().find(GALEN).orElseThrow();
        Workflow two = new Workflow(
                "Two",
                List.of(
                        new Step.Plan(2),
                        new Step.Approval(),
                        new Step.Produce(),
                        new Step.Review(),
                        new Step.Accept()));

        String pinned = PlannerBrief.of(c, mission(two), planner);
        String unpinned = PlannerBrief.of(c, mission(null), planner);
        String builtIn = PlannerBrief.of(c, mission(Workflow.BUILT_IN), planner);

        assertThat(pinned).contains("at most 2 tickets");
        assertThat(unpinned).contains("at most 8 tickets");
        assertThat(builtIn.replace("M-3", "M-2")).isEqualTo(unpinned);
        assertThat(pinned.replace("at most 2 tickets", "at most 8 tickets").replace("M-1", "M-2"))
                .isEqualTo(unpinned);
    }

    /** A workflow saved by a later Floor42 (a schema past {@link WorkflowDocument#READS}) is listed but cannot be picked: nothing is created. */
    @Test
    void aCompanyWorkflowThisBuildCannotReadIsRefusedBeforeAnythingIsRecorded() {
        c.apply(new EventEnvelope(
                "w2",
                Instant.now(),
                Origin.REAL,
                Actor.director(),
                null,
                null,
                null,
                new CompanyWorkflowSaved("Later", "{\"of\":\"later\"}", WorkflowDocument.READS + 1)));

        assertThatThrownBy(() -> MissionFactory.chosen(c, "Later"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("WORKFLOW_UNREADABLE"))
                .hasMessageContaining("workflow schema " + (WorkflowDocument.READS + 1));
        assertThat(MissionFactory.chosen(c, null)).isEqualTo(Workflow.BUILT_IN);
        assertThat(MissionFactory.chosen(c, "Built-In")).isEqualTo(Workflow.BUILT_IN);
        assertThatThrownBy(() -> MissionFactory.chosen(c, "Nobody"))
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("UNKNOWN_WORKFLOW"));
    }

    @Test
    void thePlannersLineIsReadOnceAndBuiltByFloor42() {
        assertThat(WorkflowProposals.read("TICKET: x")).isInstanceOf(WorkflowProposals.Proposal.None.class);
        assertThat(WorkflowProposals.read("WORKFLOW: Quick: plan, approval, produce, review, accept"))
                .isEqualTo(new WorkflowProposals.Proposal.Proposed(new Workflow(
                        "Quick",
                        List.of(
                                new Step.Plan(),
                                new Step.Approval(),
                                new Step.Produce(),
                                new Step.Review(),
                                new Step.Accept()))));
        assertThat(WorkflowProposals.read(
                        "WORKFLOW: A: approval, committee, accept\nworkflow: B: approval, committee, accept"))
                .isEqualTo(new WorkflowProposals.Proposal.Refused("more than one WORKFLOW line"));
        // A name the director could not give is refused here, before the domain sees it.
        assertThat(WorkflowProposals.read("WORKFLOW: built-in: approval, committee, accept"))
                .isEqualTo(new WorkflowProposals.Proposal.Refused("\"built-in\" is Floor42's own workflow"));
        assertThat(WorkflowProposals.read("WORKFLOW: approval, accept"))
                .isInstanceOf(WorkflowProposals.Proposal.Refused.class);
        assertThat(WorkflowProposals.read("WORKFLOW: Fast: approval, produce, accept"))
                .isEqualTo(new WorkflowProposals.Proposal.Refused(
                        "Produced work is reviewed (the director's acceptance in its place is not built yet)"));
        assertThat(WorkflowProposals.read("WORKFLOW: X: approval, deploy, accept"))
                .isEqualTo(new WorkflowProposals.Proposal.Refused("unknown step \"deploy\""));
        assertThat(WorkflowProposals.read("WORKFLOW: X: " + "approval, ".repeat(40) + "accept"))
                .isInstanceOf(WorkflowProposals.Proposal.Refused.class);
    }
}
