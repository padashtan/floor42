package dev.floor42.server.app;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.event.CommitteeEvent.CommitteeArbitrated;
import dev.floor42.domain.event.CommitteeEvent.CommitteeClarified;
import dev.floor42.domain.event.CommitteeEvent.CommitteeContributed;
import dev.floor42.domain.event.CommitteeEvent.CommitteeSentBack;
import dev.floor42.domain.event.DeskEvent.QuestionAnswered;
import dev.floor42.domain.event.DeskEvent.QuestionWithdrawn;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.BudgetSet;
import dev.floor42.domain.event.MissionEvent.MissionArchived;
import dev.floor42.domain.event.MissionEvent.MissionCancelled;
import dev.floor42.domain.event.MissionEvent.MissionCompleted;
import dev.floor42.domain.event.MissionEvent.MissionPlanApproved;
import dev.floor42.domain.event.MissionEvent.MissionResumed;
import dev.floor42.domain.event.RosterEvent.AgentAssignmentsResumed;
import dev.floor42.domain.event.RosterEvent.AgentDismissed;
import dev.floor42.domain.event.RosterEvent.AgentHired;
import dev.floor42.domain.event.TicketEvent.DirectiveGiven;
import dev.floor42.domain.event.TicketEvent.TicketApproved;
import dev.floor42.domain.event.TicketEvent.TicketCancelled;
import dev.floor42.domain.event.TicketEvent.TicketCompleted;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.event.WorkbenchEvent.PatchAccepted;
import dev.floor42.domain.event.WorkbenchEvent.PatchRejected;
import dev.floor42.domain.workflow.MissionInterpreter;
import dev.floor42.domain.workflow.WorkflowInterpreter.AfterRuling;
import dev.floor42.server.iam.AgentClients;
import dev.floor42.server.iam.Delegations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * What Floor42 does when a real event is stored: a table from the event's class to its reactions, run in the order they
 * are registered below. Reactions run in the office's subscriber, under its monitor: each only reads and forks onto
 * {@link MissionScheduler}, never works on the caller's thread.
 */
@ApplicationScoped
public class EventReactions {

    private final OfficeService office;
    private final MissionScheduler scheduler;
    private final Dispatcher dispatcher;
    private final DirectorAnswers answers;
    private final NoteArchive notes;
    private final MissionInterpreter workflow;
    private final AgentClients identities;
    private final Delegations delegations;
    private final RetroDelegations retroDelegations;
    private final Retrospectives retros;
    private final Map<Class<? extends DomainEvent>, List<BiConsumer<EventEnvelope, Batch>>> table = new HashMap<>();

    /** One reaction to an event of type E, with the batch of events it came in. */
    @FunctionalInterface
    interface Reaction<E extends DomainEvent> {
        void react(EventEnvelope envelope, E event, Batch batch);
    }

    /** What the reactions share across one command's events. */
    static final class Batch {
        private final Set<MissionId> queued = new HashSet<>();

        /** True the first time in this batch: approving several tickets at once starts one thread. */
        boolean firstFor(MissionId m) {
            return queued.add(m);
        }
    }

    @Inject
    EventReactions(
            OfficeService office,
            MissionScheduler scheduler,
            Dispatcher dispatcher,
            DirectorAnswers answers,
            NoteArchive notes,
            MissionInterpreter workflow,
            AgentClients identities,
            Delegations delegations,
            RetroDelegations retroDelegations,
            Retrospectives retros) {
        this.office = office;
        this.scheduler = scheduler;
        this.dispatcher = dispatcher;
        this.answers = answers;
        this.notes = notes;
        this.workflow = workflow;
        this.identities = identities;
        this.delegations = delegations;
        this.retroDelegations = retroDelegations;
        this.retros = retros;
        registerWorkResumes();
        registerQueueChanges();
        registerDirectorDecisions();
        registerIdentities();
    }

    /** Subscribes the table to the office's stored events. */
    void listen() {
        office.subscribe(this::react);
    }

    void react(List<EventEnvelope> events) {
        Batch batch = new Batch();
        for (EventEnvelope e : events) {
            if (e.origin() != Origin.REAL) continue;
            for (BiConsumer<EventEnvelope, Batch> r :
                    table.getOrDefault(e.event().getClass(), List.of())) {
                r.accept(e, batch);
            }
        }
    }

    private <E extends DomainEvent> void on(Class<E> type, Reaction<E> reaction) {
        table.computeIfAbsent(type, k -> new ArrayList<>())
                .add((e, batch) -> reaction.react(e, type.cast(e.event()), batch));
    }

    private void produce(String name, MissionId m) {
        scheduler.fork(name + m, () -> dispatcher.produce(m));
    }

    private void registerWorkResumes() {
        on(MissionPlanApproved.class, (e, approved, b) -> produce("produce-", approved.mission()));
        // The director resumed a mission suspended after an incident: work goes on from the journal.
        on(MissionResumed.class, (e, resumed, b) -> produce("resumed-", resumed.mission()));
        // The committee meets again after the director sends its decision back with a constraint.
        on(CommitteeSentBack.class, (e, back, b) -> produce("committee-", back.mission()));
        // The director answered the business analyst: the committee debates once more with the answers.
        on(CommitteeClarified.class, (e, clarified, b) -> {
            if (!clarified.answers().isEmpty()) produce("committee-", clarified.mission());
        });
        // The director spoke in person in their double's seat: it may complete the round.
        on(CommitteeContributed.class, (e, said, b) -> {
            if (e.actor().isDirector()) produce("committee-", said.mission());
        });
        // The director accepted or rejected a developer's changes: its worktree is clean again, it may take a ticket.
        on(PatchAccepted.class, (e, accepted, b) -> produce("workshop-", e.mission()));
        on(PatchRejected.class, (e, rejected, b) -> produce("workshop-", e.mission()));
        // A note made into a ticket is closed into the archive once that ticket is done, with its commit if any.
        on(TicketCompleted.class, (e, done, b) -> scheduler.fork("notes", notes::closeImplemented));
        on(PatchAccepted.class, (e, accepted, b) -> scheduler.fork("notes", notes::closeImplemented));
    }

    private void registerQueueChanges() {
        // A ticket the director wrote (from a note, for instance) needs no approval: it joins the queue at once.
        on(TicketCreated.class, (e, created, b) -> {
            if (e.actor().isDirector() && b.firstFor(e.mission())) produce("director-ticket-", e.mission());
        });
        // Approved tickets join the queue; approving several tickets at once starts one thread.
        on(TicketApproved.class, (e, approved, b) -> {
            if (b.firstFor(e.mission())) produce("approved-", e.mission());
        });
    }

    private void registerDirectorDecisions() {
        // The workflow says what follows the ruling (ADR-0046): the implementation, or nothing more.
        on(CommitteeArbitrated.class, (e, ruling, b) -> {
            switch (office.query(c -> workflow.afterRuling(c, ruling))) {
                case AfterRuling.Implement i -> answers.adoptedForImplementation(i.committee());
                case AfterRuling.Decided d -> {}
            }
        });
        on(QuestionAnswered.class, (e, answered, b) -> answers.answered(e.mission(), answered));
        on(QuestionWithdrawn.class, (e, withdrawn, b) -> unblocked(e));
        on(TicketCancelled.class, (e, cancelled, b) -> unblocked(e));
        on(AgentAssignmentsResumed.class, (e, resumed, b) -> unblocked(e));
        on(BudgetSet.class, (e, budget, b) -> {
            if (e.actor().isDirector()) answers.raisedBudget(budget.mission());
        });
        // A directive from the workshop sends the producer back to work, then to a new independent review.
        on(
                DirectiveGiven.class,
                (e, directive, b) -> scheduler.fork(
                        "directive-" + directive.ticket(), () -> dispatcher.produce(directive.mission())));
    }

    private void registerIdentities() {
        // IAM mode (a no-op while it is off): a hired agent gets its Keycloak client; a dismissed one is cut off here
        // at once, and Keycloak is told afterwards.
        on(
                AgentHired.class,
                (e, hired, b) -> scheduler.fork("iam-hired-" + hired.agent(), () -> identities.hired(hired.agent())));
        on(AgentDismissed.class, (e, gone, b) -> {
            identities.revokeLocally(gone.agent());
            scheduler.fork("iam-dismissed-" + gone.agent(), () -> identities.dismissed(gone.agent()));
        });
        // A delegation never outlives its mission: cut off here at once, Keycloak told afterwards.
        on(MissionCompleted.class, (e, done, b) -> {
            // A retrospective due still needs it, for its own sessions only; Retrospectives ends it afterwards.
            if (retros.holdsRetro(e.origin(), done.mission())) retroDelegations.holdForRetro(done.mission());
            else endDelegation(done.mission());
        });
        on(MissionCancelled.class, (e, cancelled, b) -> endDelegation(cancelled.mission()));
        on(MissionArchived.class, (e, archived, b) -> endDelegation(archived.mission()));
    }

    private void endDelegation(MissionId m) {
        delegations.revokeLocally(m);
        scheduler.fork("iam-delegation-" + m, () -> delegations.revokeRemote(m));
    }

    /** Something that held work back is gone: its mission looks again, or every mission when it was an agent's. */
    private void unblocked(EventEnvelope e) {
        if (e.mission() != null) produce("unblocked-", e.mission());
        else scheduler.fork("staff", dispatcher::staffChanged);
    }
}
