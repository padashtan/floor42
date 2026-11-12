package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.workflow.CompanyWorkflow;
import dev.floor42.domain.workflow.FollowedVersion;
import dev.floor42.domain.workflow.MissionWorkflow;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.WorkflowProposal;
import dev.floor42.domain.workflow.WorkflowVersion;
import java.util.List;
import java.util.Optional;

/**
 * Workflows (ADR-0050), reached by {@link Company#workflows()}: what each mission follows, the company's list the
 * director writes, and the planner's proposals for it. A mission pins its workflow when it is created
 * ({@link MissionsFacade#create(CommandContext, String, String, String, Workflow)}).
 */
public final class WorkflowsFacade {

    private final WorkflowBook book;

    WorkflowsFacade(WorkflowBook book) {
        this.book = book;
    }

    /** The workflow the mission follows: its pin, or the built-in one for a mission never pinned. */
    public MissionWorkflow of(MissionId mission) {
        return book.of(mission);
    }

    /** The key of the task this ticket was opened for (ADR-0061); empty for every other ticket. */
    public Optional<String> stepOf(Ticket ticket) {
        return book.stepOf(ticket.mission(), ticket.id());
    }

    /** The node's risk, tier and tests in a graph mission (ADR-0062); empty for any other ticket. */
    public Optional<NodePlan> nodeOf(TicketId ticket) {
        return book.nodeOf(ticket);
    }

    /** Whether the workflow's checks run on this ticket: always in the loop, on high-risk nodes in graph mode. */
    public boolean checksRun(Ticket ticket) {
        return book.checksRun(ticket);
    }

    /** The tickets the mission's tasks opened, by task key. */
    public java.util.Map<String, TicketId> linked(MissionId mission) {
        return book.linked(mission);
    }

    /** The company's workflows, in the order they were first saved. */
    public List<CompanyWorkflow> companyWorkflows() {
        return book.companyWorkflows();
    }

    /** The company workflow of this name (compared by {@link CompanyWorkflow#key}). */
    public Optional<CompanyWorkflow> named(String name) {
        return book.named(name);
    }

    /** The saves of a company workflow since its latest removal, oldest first: its versions (ADR-0052). */
    public List<WorkflowVersion> versions(String name) {
        return book.versions(name);
    }

    /** The version of a company workflow the mission's pin is a copy of; empty when it is a copy of none. */
    public Optional<FollowedVersion> followed(MissionId mission) {
        return book.followed(mission);
    }

    public List<WorkflowProposal> proposals() {
        return book.proposals();
    }

    /**
     * The director saves a workflow; a workflow of the same name is replaced only with {@code replace}.
     *
     * @param adopting the planner's proposal this saves, closed in the same command; null for none
     */
    public List<EventEnvelope> save(CommandContext ctx, Workflow workflow, boolean replace, Integer adopting) {
        return book.save(ctx, workflow, replace, adopting);
    }

    /** Missions that pinned it keep their copy. */
    public List<EventEnvelope> remove(CommandContext ctx, String name) {
        return book.remove(ctx, name);
    }

    /** The mission's planner proposes a workflow for the company's list; it never changes the mission. */
    public List<EventEnvelope> propose(CommandContext ctx, MissionId mission, Workflow workflow) {
        return book.propose(ctx, mission, workflow);
    }

    public List<EventEnvelope> dismissProposal(CommandContext ctx, int proposal) {
        return book.dismiss(ctx, proposal);
    }
}
