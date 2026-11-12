package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireDirector;
import static dev.floor42.domain.Rules.requireOpen;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.WorkflowEvent;
import dev.floor42.domain.event.WorkflowEvent.CompanyWorkflowRemoved;
import dev.floor42.domain.event.WorkflowEvent.CompanyWorkflowSaved;
import dev.floor42.domain.event.WorkflowEvent.NodePlanned;
import dev.floor42.domain.event.WorkflowEvent.WorkflowPinned;
import dev.floor42.domain.event.WorkflowEvent.WorkflowProposalClosed;
import dev.floor42.domain.event.WorkflowEvent.WorkflowProposed;
import dev.floor42.domain.event.WorkflowEvent.WorkflowStepTicketOpened;
import dev.floor42.domain.workflow.CompanyWorkflow;
import dev.floor42.domain.workflow.FollowedVersion;
import dev.floor42.domain.workflow.MissionWorkflow;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.WorkflowDocument;
import dev.floor42.domain.workflow.WorkflowProposal;
import dev.floor42.domain.workflow.WorkflowVersion;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Workflows (ADR-0050): what each mission pinned, the company's list the director writes, the planner's proposals.
 * Documents are read once, when applied; no other area reads a pinned workflow while applying an event.
 */
final class WorkflowBook {

    private final CompanyView view;
    private final Map<MissionId, MissionWorkflow> pins = new HashMap<>();
    /** By mission, the ticket each task of its workflow opened (ADR-0061). */
    private final Map<MissionId, Map<String, TicketId>> links = new HashMap<>();

    /** What the approval settled for each node of a graph mission (ADR-0062). */
    private final Map<TicketId, NodePlan> nodes = new HashMap<>();

    private final Map<String, CompanyWorkflow> companyWorkflows = new LinkedHashMap<>();
    private final Map<Integer, WorkflowProposal> proposals = new LinkedHashMap<>();
    /** Versions and followed versions, derived from the saves and pins as they are applied (ADR-0052). */
    private final WorkflowHistory history = new WorkflowHistory();

    WorkflowBook(CompanyView view) {
        this.view = view;
    }

    /** The key of the task this ticket was opened for, if any. */
    Optional<String> stepOf(MissionId mission, TicketId ticket) {
        return links.getOrDefault(mission, Map.of()).entrySet().stream()
                .filter(e -> e.getValue().equals(ticket))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** The tasks of the mission that already have a ticket, by key. */
    Map<String, TicketId> linked(MissionId mission) {
        return Map.copyOf(links.getOrDefault(mission, Map.of()));
    }

    /**
     * The node's risk, tier and tests: what the approval journaled, else (a node approved by a development build of the
     * first increment) what its description and criteria say, else none.
     */
    Optional<NodePlan> nodeOf(TicketId ticket) {
        NodePlan planned = nodes.get(ticket);
        if (planned != null) return Optional.of(planned);
        return view.ticket(ticket)
                .filter(t -> NodePlan.hasNotes(t.description()))
                .map(t -> NodePlan.proposed(t.description(), t.acceptanceCriteria()));
    }

    /** Whether the workflow's checks run on this ticket: always in the loop; in graph mode, on high-risk nodes only. */
    boolean checksRun(Ticket t) {
        return of(t.mission()).runnable().map(w -> !w.graph()).orElse(true)
                || nodeOf(t.id()).map(NodePlan::high).orElse(true);
    }

    MissionWorkflow of(MissionId mission) {
        return pins.getOrDefault(mission, new MissionWorkflow.Unpinned());
    }

    List<CompanyWorkflow> companyWorkflows() {
        return List.copyOf(companyWorkflows.values());
    }

    Optional<CompanyWorkflow> named(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(companyWorkflows.get(CompanyWorkflow.key(name)));
    }

    List<WorkflowVersion> versions(String name) {
        return history.versions(name);
    }

    Optional<FollowedVersion> followed(MissionId mission) {
        return history.followed(mission, of(mission));
    }

    /** An open proposal of a cancelled mission is dismissed with it, whatever the journal says (older journals too). */
    List<WorkflowProposal> proposals() {
        return proposals.values().stream()
                .map(p -> withMission(p) ? p.closed(false) : p)
                .toList();
    }

    private boolean withMission(WorkflowProposal p) {
        return p.status() == WorkflowProposal.Status.OPEN
                && view.mission(p.mission())
                        .map(m -> m.status() == MissionStatus.CANCELLED)
                        .orElse(false);
    }

    /**
     * The director saves a workflow under its name. An existing name is replaced only when asked; adopting a proposal
     * closes it in the same command.
     */
    List<EventEnvelope> save(CommandContext ctx, Workflow w, boolean replace, Integer adopting) {
        requireDirector(ctx);
        Optional<String> bad = CompanyWorkflow.nameRefusal(w.name());
        require(bad.isEmpty(), Refusal.INVALID_ARGUMENT, bad.orElse(""));
        require(
                replace || named(w.name()).isEmpty(),
                Refusal.WORKFLOW_EXISTS,
                "A workflow named \"" + w.name() + "\" exists: replace it, or choose another name");
        if (adopting != null) openProposal(adopting);
        // The name as listed is the name written in the document: one stripped value for both.
        Workflow named = w.renamed(w.name().strip());
        WorkflowDocument.Written doc = WorkflowDocument.forJournal(named);
        Recorder r = view.record(ctx, null);
        r.emit(new CompanyWorkflowSaved(named.name(), doc.document(), doc.schemaVersion()));
        if (adopting != null) r.emit(new WorkflowProposalClosed(adopting, true));
        return r.events();
    }

    List<EventEnvelope> remove(CommandContext ctx, String name) {
        requireDirector(ctx);
        CompanyWorkflow w = named(name)
                .orElseThrow(() -> new DomainException(Refusal.UNKNOWN_WORKFLOW, "No workflow named \"" + name + "\""));
        return view.record(ctx, null).emit(new CompanyWorkflowRemoved(w.name())).events();
    }

    /** The planner proposes a workflow, already built and checked: Floor42 writes its document, never the agent. */
    List<EventEnvelope> propose(CommandContext ctx, MissionId mission, Workflow w) {
        require(ctx.actor().kind() == Actor.Kind.AGENT, Refusal.FORBIDDEN, "Only the planner proposes a workflow");
        requireOpen(view.missionFor(ctx, mission));
        // The mission's planner: the agent its planning ticket is assigned to, never any agent at work on it.
        AgentId agent = Rules.agentOf(ctx);
        require(
                view.tickets(mission).stream()
                        .anyMatch(t -> t.kind() == TicketKind.PLANNING && agent.equals(t.assignee())),
                Refusal.FORBIDDEN,
                "Only the planner of " + mission + " proposes a workflow");
        Optional<String> bad = CompanyWorkflow.nameRefusal(w.name());
        require(bad.isEmpty(), Refusal.INVALID_ARGUMENT, bad.orElse(""));
        int number = proposals.size() + 1;
        WorkflowDocument.Written doc = WorkflowDocument.forJournal(w);
        return view.record(ctx, mission)
                .emit(new WorkflowProposed(mission, number, doc.document(), doc.schemaVersion()))
                .events();
    }

    List<EventEnvelope> dismiss(CommandContext ctx, int proposal) {
        requireDirector(ctx);
        openProposal(proposal);
        return view.record(ctx, null)
                .emit(new WorkflowProposalClosed(proposal, false))
                .events();
    }

    private void openProposal(int number) {
        WorkflowProposal p = proposals.get(number);
        require(p != null, Refusal.UNKNOWN_PROPOSAL, "No workflow proposal " + number);
        require(
                !withMission(p),
                Refusal.MISSION_CLOSED,
                p.mission() + " was cancelled: its proposal " + number + " is closed");
        require(
                p.status() == WorkflowProposal.Status.OPEN,
                Refusal.PROPOSAL_CLOSED,
                "Workflow proposal " + number + " is closed");
    }

    void apply(WorkflowEvent event, EventEnvelope envelope) {
        switch (event) {
            case WorkflowPinned e -> pin(e);
            case WorkflowStepTicketOpened e -> link(e);
            case NodePlanned e -> plan(e);
            case CompanyWorkflowSaved e -> {
                WorkflowDocument.Reading reading = WorkflowDocument.read(e.document(), e.schemaVersion());
                companyWorkflows.put(
                        CompanyWorkflow.key(e.name()), new CompanyWorkflow(e.name(), e.schemaVersion(), reading));
                history.saved(e.name(), e.schemaVersion(), envelope.occurredAt(), reading);
            }
            case CompanyWorkflowRemoved e -> {
                companyWorkflows.remove(CompanyWorkflow.key(e.name()));
                history.removed(e.name());
            }
            case WorkflowProposed e ->
                proposals.put(
                        e.proposal(),
                        new WorkflowProposal(
                                e.proposal(),
                                e.mission(),
                                envelope.actor().id(),
                                envelope.occurredAt(),
                                WorkflowDocument.read(e.document(), e.schemaVersion()),
                                WorkflowProposal.Status.OPEN));
            case WorkflowProposalClosed e -> proposals.computeIfPresent(e.proposal(), (k, p) -> p.closed(e.adopted()));
        }
    }

    /** A task's ticket, linked once: a second link of a key or a ticket, or one off its mission, is a contradiction. */
    private void link(WorkflowStepTicketOpened e) {
        Mission m = view.mission(e.mission())
                .orElseThrow(() -> new IllegalStateException("A task opened on unknown " + e.mission()));
        if (m.origin() != Origin.REAL) throw new IllegalStateException(e.mission() + " is simulated: no task opens");
        Ticket t = view.ticket(e.ticket())
                .orElseThrow(() -> new IllegalStateException("A task linked to unknown " + e.ticket()));
        if (!t.mission().equals(e.mission())) {
            throw new IllegalStateException(e.ticket() + " is not a ticket of " + e.mission());
        }
        Map<String, TicketId> of = links.computeIfAbsent(e.mission(), k -> new java.util.LinkedHashMap<>());
        if (of.containsKey(e.step()) || of.containsValue(e.ticket())) {
            throw new IllegalStateException(e.mission() + " links " + e.step() + " or " + e.ticket() + " twice");
        }
        of.put(e.step(), e.ticket());
    }

    /** A node planned once, for a ticket that exists, on a real mission pinned in graph mode: anything else is a contradiction. */
    private void plan(NodePlanned e) {
        Ticket t = view.ticket(e.ticket())
                .orElseThrow(() -> new IllegalStateException("A node planned for unknown " + e.ticket()));
        Mission m = view.mission(t.mission()).orElseThrow();
        if (m.origin() != Origin.REAL)
            throw new IllegalStateException(t.mission() + " is simulated: no node is planned");
        if (!of(t.mission()).runnable().map(Workflow::graph).orElse(false))
            throw new IllegalStateException(t.mission() + " is not pinned in graph mode: no node is planned");
        if (nodes.containsKey(e.ticket())) throw new IllegalStateException(e.ticket() + " is planned twice");
        NodePlan.Risk risk = NodePlan.Risk.valueOf(e.risk().toUpperCase(Locale.ROOT));
        NodePlan.Tier tier = NodePlan.Tier.valueOf(e.tier().toUpperCase(Locale.ROOT));
        nodes.put(e.ticket(), new NodePlan(risk, tier, e.tests()));
    }

    /** One pin per real mission, before its first ticket: anything else is a journal the code disagrees with. */
    private void pin(WorkflowPinned e) {
        Mission m = view.mission(e.mission())
                .orElseThrow(() -> new IllegalStateException("A workflow pinned on unknown " + e.mission()));
        if (m.origin() != Origin.REAL) throw new IllegalStateException(e.mission() + " is simulated: never pinned");
        if (pins.containsKey(e.mission())) throw new IllegalStateException(e.mission() + " is pinned twice");
        if (!view.tickets(e.mission()).isEmpty()) {
            throw new IllegalStateException(e.mission() + " is pinned after its first ticket");
        }
        pins.put(e.mission(), MissionWorkflow.of(e.document(), e.schemaVersion()));
        history.pinned(e.mission(), pins.get(e.mission()));
    }
}
