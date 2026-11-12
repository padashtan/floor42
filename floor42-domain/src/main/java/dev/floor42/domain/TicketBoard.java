package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireDirector;
import static dev.floor42.domain.Rules.requireText;
import static dev.floor42.domain.Rules.requireTransition;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MeetingRoomEvent.MeetingViewsAsked;
import dev.floor42.domain.event.RetroEvent.RetroOpened;
import dev.floor42.domain.event.StaffingEvent.StaffingAdvised;
import dev.floor42.domain.event.TicketEvent;
import dev.floor42.domain.event.TicketEvent.ConsultationAnswered;
import dev.floor42.domain.event.TicketEvent.ConsultationRequested;
import dev.floor42.domain.event.TicketEvent.DirectiveGiven;
import dev.floor42.domain.event.TicketEvent.ReviewItemsRecorded;
import dev.floor42.domain.event.TicketEvent.TicketApproved;
import dev.floor42.domain.event.TicketEvent.TicketAssigned;
import dev.floor42.domain.event.TicketEvent.TicketCancelled;
import dev.floor42.domain.event.TicketEvent.TicketCompleted;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.event.TicketEvent.TicketEdited;
import dev.floor42.domain.event.TicketEvent.TicketReleased;
import dev.floor42.domain.event.TicketEvent.TicketReopened;
import dev.floor42.domain.event.TicketEvent.TicketReviewed;
import dev.floor42.domain.event.TicketEvent.TicketStarted;
import dev.floor42.domain.event.TicketEvent.TicketSubmittedForReview;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tickets of every mission and their life: created, proposed and approved, assigned, worked, reviewed, done. The
 * review itself (consultations, verdicts) is {@link TicketReviews}'s; its events are applied here.
 */
final class TicketBoard {

    private final CompanyView view;
    private final Map<TicketId, Ticket> tickets = new LinkedHashMap<>();
    /** Derived while applying, never written (ADR-0052): submissions counted, each review tagged with its own. */
    private final Map<TicketId, ReviewRounds> reviewRounds = new LinkedHashMap<>();

    private final ObjectionRounds objectionRounds = new ObjectionRounds();

    TicketBoard(CompanyView view) {
        this.view = view;
    }

    ObjectionRounds objectionRounds() {
        return objectionRounds;
    }

    ReviewRounds reviewRounds(TicketId id) {
        return reviewRounds.getOrDefault(id, ReviewRounds.NONE);
    }

    /** The mission's tickets; a meeting's own ticket is not one of them (ADR-0049), though found by its id. */
    List<Ticket> tickets(MissionId mission) {
        return tickets.values().stream()
                .filter(t -> t.mission().equals(mission) && t.kind != TicketKind.MEETING)
                .toList();
    }

    /** Every ticket but the meetings' own (ADR-0049). */
    Collection<Ticket> ticketsView() {
        return tickets.values().stream()
                .filter(t -> t.kind != TicketKind.MEETING)
                .toList();
    }

    Optional<Ticket> ticket(TicketId id) {
        return Optional.ofNullable(tickets.get(id));
    }

    /** The tickets of the T-n sequence: a meeting's own ticket is named after its meeting (ADR-0049). */
    int count() {
        return ticketsView().size();
    }

    Ticket existing(TicketId id) {
        Ticket t = tickets.get(id);
        require(t != null, Refusal.UNKNOWN_TICKET, "No ticket " + id);
        return t;
    }

    /** A ticket a ticket command may act on: never a meeting's own (ADR-0049), whose life is the meeting's. */
    private Ticket work(TicketId id) {
        return workTicket(id);
    }

    Ticket workTicket(TicketId id) {
        Ticket t = existing(id);
        Rules.requireWorkTicket(t);
        return t;
    }

    List<EventEnvelope> create(
            CommandContext ctx,
            MissionId id,
            String title,
            String description,
            Priority priority,
            List<TicketId> dependsOn,
            List<String> acceptanceCriteria,
            TicketKind kind) {
        Mission m = view.missionFor(ctx, id);
        require(
                m.status == MissionStatus.PLANNING || m.status == MissionStatus.IN_PROGRESS,
                Refusal.PLAN_LOCKED,
                "Tickets can be added while planning or in progress, not when " + m.status);
        requireText(title, "title");
        dependsOn.forEach(dep -> requireTicketOf(id, dep));
        return view.record(ctx, id)
                .emit(new TicketCreated(
                        nextId(),
                        id,
                        title.strip(),
                        description == null ? "" : description,
                        priority == null ? Priority.NORMAL : priority,
                        dependsOn,
                        acceptanceCriteria,
                        kind == null ? TicketKind.WORK : kind))
                .events();
    }

    private void requireTicketOf(MissionId id, TicketId dep) {
        Ticket t = tickets.get(dep);
        require(
                t != null && t.mission().equals(id) && t.kind != TicketKind.MEETING,
                Refusal.UNKNOWN_DEPENDENCY,
                dep + " is not a ticket of " + id);
    }

    /** The highest T-n number ever created, read or not: a number is never given twice. */
    private transient int highestTicket; // transient: kept out of the golden field dumps, see reserve

    TicketId nextId() {
        return new TicketId("T-" + (Math.max(highestTicket, count()) + 1));
    }

    /** A ticket number held by a journal row this version could not read: no new ticket takes it. */
    void reserve(TicketId id) {
        String v = id.value();
        if (v.matches("T-\\d{1,9}")) highestTicket = Math.max(highestTicket, Integer.parseInt(v.substring(2)));
    }

    List<EventEnvelope> assign(CommandContext ctx, TicketId id, AgentId agent) {
        Ticket t = work(id);
        Mission m = view.missionFor(ctx, t.mission());
        require(!m.status.isTerminal(), Refusal.MISSION_CLOSED, m.id() + " is closed");
        require(
                t.status == TicketStatus.TODO || t.status == TicketStatus.PROPOSED,
                Refusal.INVALID_TRANSITION,
                id + " can only be assigned before work starts");
        require(!view.existingAgent(agent).suspended, Refusal.AGENT_SUSPENDED, agent + " takes no new assignments");
        require(!view.existingAgent(agent).dismissed, Refusal.AGENT_DISMISSED, agent + " has left the company");
        return view.record(ctx, t.mission()).emit(new TicketAssigned(id, agent)).events();
    }

    List<EventEnvelope> start(CommandContext ctx, TicketId id) {
        Ticket t = work(id);
        Mission m = view.missionFor(ctx, t.mission());
        require(
                t.assignee != null && ctx.actor().is(t.assignee),
                Refusal.NOT_ASSIGNEE,
                "Only the assignee starts " + id);
        require(
                m.status == MissionStatus.IN_PROGRESS,
                Refusal.MISSION_NOT_ACTIVE,
                "Work starts only once the plan is approved");
        requireTransition(t, TicketStatus.IN_PROGRESS);
        for (TicketId dep : t.dependsOn()) {
            require(tickets.get(dep).status == TicketStatus.DONE, Refusal.DEPENDENCY_OPEN, id + " waits for " + dep);
        }
        return view.record(ctx, t.mission()).emit(new TicketStarted(id)).events();
    }

    /** Every ticket is checked before the first event: a refusal half-way would leave events never journaled. */
    List<EventEnvelope> approve(CommandContext ctx, MissionId id, List<TicketId> selection) {
        return approve(ctx, id, selection, Map.of());
    }

    /** As above; in a graph mission (ADR-0062) each node is also planned, see {@link TicketApprovals}. */
    List<EventEnvelope> approve(
            CommandContext ctx, MissionId id, List<TicketId> selection, Map<TicketId, String> risks) {
        return new TicketApprovals(view, this).approve(ctx, id, selection, risks);
    }

    List<EventEnvelope> edit(
            CommandContext ctx, TicketId id, String title, String description, List<String> acceptanceCriteria) {
        requireDirector(ctx);
        Ticket t = work(id);
        view.missionFor(ctx, t.mission());
        require(
                t.status == TicketStatus.PROPOSED || t.status == TicketStatus.TODO,
                Refusal.WORK_STARTED,
                id + " can only be edited before work starts");
        requireText(title, "title");
        List<String> criteria = acceptanceCriteria == null
                ? List.of()
                : acceptanceCriteria.stream()
                        .map(String::strip)
                        .filter(c -> !c.isEmpty())
                        .toList();
        return view.record(ctx, t.mission())
                .emit(new TicketEdited(id, title.strip(), description == null ? "" : description.strip(), criteria))
                .events();
    }

    List<EventEnvelope> cancel(CommandContext ctx, TicketId id, String reason) {
        requireDirector(ctx);
        Ticket t = work(id);
        view.missionFor(ctx, t.mission());
        requireTransition(t, TicketStatus.CANCELLED);
        requireText(reason, "reason");
        return view.record(ctx, t.mission())
                .emit(new TicketCancelled(id, reason))
                .events();
    }

    /** A ticket in progress whose assignee has left goes back to the queue, for another developer to take. */
    List<EventEnvelope> release(CommandContext ctx, TicketId id) {
        require(
                ctx.actor().kind() == Actor.Kind.SYSTEM || ctx.actor().kind() == Actor.Kind.DIRECTOR,
                Refusal.FORBIDDEN,
                "Only Floor42 or the director gives a ticket back to the queue");
        Ticket t = work(id);
        require(t.status.canBeReleased(), Refusal.INVALID_TRANSITION, id + " is not in progress");
        require(
                t.assignee != null && view.existingAgent(t.assignee).dismissed,
                Refusal.AGENT_PRESENT,
                id + " is still in the hands of " + t.assignee);
        return view.record(ctx, t.mission())
                .emit(new TicketReleased(id, t.assignee))
                .events();
    }

    /** The only way this area's state changes. */
    void apply(TicketEvent event, EventEnvelope envelope) {
        Instant at = envelope.occurredAt();
        switch (event) {
            case TicketCreated e -> on(e, envelope);
            case TicketApproved e -> on(e);
            case TicketEdited e -> on(e);
            case TicketAssigned e -> on(e);
            case TicketStarted e -> on(e, at);
            case TicketSubmittedForReview e -> on(e);
            case TicketReviewed e -> on(e);
            case ReviewItemsRecorded e -> on(e);
            case ConsultationRequested e -> on(e);
            case ConsultationAnswered e -> on(e);
            case TicketCompleted e -> on(e, at);
            case TicketReleased e -> on(e);
            case TicketReopened e -> on(e);
            case TicketCancelled e -> on(e, at);
            case DirectiveGiven e -> on(e, envelope);
        }
    }

    void on(TicketCreated e, EventEnvelope envelope) {
        // Two creations of one id would merge two tickets: the replay stops instead.
        if (tickets.containsKey(e.ticket())) throw new IllegalStateException(e.ticket() + " is created twice");
        reserve(e.ticket());
        Ticket t = new Ticket(
                e.ticket(),
                e.mission(),
                e.title(),
                e.description(),
                e.priority(),
                e.dependsOn(),
                e.acceptanceCriteria());
        // Absent before 1.6.0: the rule of that time, on the title the ticket was created with.
        t.kind = e.kind() != null ? e.kind() : TicketKind.ofLegacyTitle(e.title());
        if (view.mission(e.mission()).orElseThrow().ticketValidation
                && !envelope.actor().isDirector()) {
            t.status = TicketStatus.PROPOSED;
        }
        t.createdAt = envelope.occurredAt();
        tickets.put(e.ticket(), t);
        objectionRounds.note(e.mission(), envelope.causationId(), e.ticket(), true);
    }

    void on(TicketApproved e) {
        tickets.get(e.ticket()).status = TicketStatus.TODO;
    }

    void on(TicketEdited e) {
        Ticket t = tickets.get(e.ticket());
        t.title = e.title();
        t.description = e.description();
        t.acceptanceCriteria = List.copyOf(e.acceptanceCriteria());
    }

    void on(TicketAssigned e) {
        tickets.get(e.ticket()).assignee = e.agent();
    }

    void on(TicketStarted e, Instant at) {
        Ticket t = tickets.get(e.ticket());
        t.status = TicketStatus.IN_PROGRESS;
        if (t.startedAt == null) t.startedAt = at;
    }

    void on(TicketSubmittedForReview e) {
        Ticket t = tickets.get(e.ticket());
        t.status = TicketStatus.IN_REVIEW;
        t.deliverable = e.deliverable();
        reviewRounds.put(e.ticket(), reviewRounds(e.ticket()).submitted());
    }

    void on(TicketReviewed e) {
        tickets.get(e.ticket()).reviews.add(new Ticket.Review(e.reviewer(), e.verdict(), e.findings()));
        reviewRounds.put(e.ticket(), reviewRounds(e.ticket()).reviewed());
    }

    /** Attached to the review it follows (ADR-0053). */
    void on(ReviewItemsRecorded e) {
        tickets.get(e.ticket()).attach(e.reviewer(), new ReviewItems(e.declared(), e.reservations(), e.notes()));
    }

    void on(ConsultationRequested e) {
        Ticket t = tickets.get(e.ticket());
        t.consultations.add(
                new Ticket.Consultation(t.reviews.size(), e.reviewer(), e.kind(), e.role(), e.question(), null, null));
    }

    void on(ConsultationAnswered e) {
        Ticket t = tickets.get(e.ticket());
        Ticket.Consultation c = t.consultations.getLast();
        t.consultations.set(
                t.consultations.size() - 1,
                new Ticket.Consultation(c.round(), c.reviewer(), c.kind(), c.role(), c.question(), e.answer(), e.by()));
    }

    void on(TicketCompleted e, Instant at) {
        done(tickets.get(e.ticket()), at);
    }

    void on(TicketReleased e) {
        Ticket t = tickets.get(e.ticket());
        t.status = TicketStatus.TODO;
        t.assignee = null;
    }

    /**
     * Before 1.4.0 a directive could reopen a decided committee's ticket, which nobody works on: the mission then stayed
     * in progress for good. The journal keeps the event; the decision stays done.
     */
    void on(TicketReopened e) {
        Ticket t = tickets.get(e.ticket());
        if (view.committees(t.mission()).stream().anyMatch(c -> c.ruledOn(t.id()))) return;
        t.status = TicketStatus.IN_PROGRESS;
        t.endedAt = null;
    }

    /** Graph mode (ADR-0062): a done node whose dependency was reopened since, see {@link Rechecks}. */
    boolean toRecheck(TicketId id) {
        return Rechecks.toRecheck(view, this, id);
    }

    void on(TicketCancelled e, Instant at) {
        Ticket t = tickets.get(e.ticket());
        t.status = TicketStatus.CANCELLED;
        t.endedAt = at;
    }

    /** The directive is the ticket's: every later brief restates it. Its trace is the workbench's. */
    void on(DirectiveGiven e, EventEnvelope envelope) {
        tickets.get(e.ticket()).directives.add(e.directive());
        objectionRounds.note(e.mission(), envelope.causationId(), e.ticket(), false);
    }

    /** The staffing review's ticket is done with the planner's advice as its deliverable. */
    void staffingAdvised(StaffingAdvised e, Instant at) {
        Ticket t = tickets.get(e.ticket());
        t.deliverable = e.advice();
        done(t, at);
    }

    /** A retrospective gets its own ticket, in progress and chaired, before the retrospective itself is recorded. */
    void retroOpened(RetroOpened e) {
        if (tickets.containsKey(e.ticket())) throw new IllegalStateException(e.ticket() + " is created twice");
        reserve(e.ticket());
        tickets.put(e.ticket(), Ticket.retrospective(e.ticket(), e.mission(), e.chair()));
    }

    /** A meeting's first ask opens its own ticket (ADR-0049): kind MEETING, never listed, never assigned. */
    void meetingAsked(MeetingViewsAsked e, MissionId mission, Instant at) {
        if (tickets.containsKey(e.ticket())) return;
        Ticket t = new Ticket(
                e.ticket(),
                mission,
                "Meeting " + e.meeting() + ": the attendees' views",
                "The turns of speech of the director's meeting " + e.meeting() + ".",
                Priority.NORMAL,
                List.of(),
                List.of());
        t.kind = TicketKind.MEETING;
        t.createdAt = at;
        tickets.put(e.ticket(), t);
    }

    void retroConcluded(Retro r) {
        tickets.get(r.ticket()).status = TicketStatus.DONE;
    }

    private static void done(Ticket t, Instant at) {
        t.status = TicketStatus.DONE;
        t.endedAt = at;
    }
}
