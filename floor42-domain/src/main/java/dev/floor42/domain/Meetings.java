package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.blankToNull;
import static dev.floor42.domain.Rules.requireDirector;
import static dev.floor42.domain.Rules.requireOpen;
import static dev.floor42.domain.Rules.requireText;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MeetingEvent;
import dev.floor42.domain.event.MeetingEvent.MeetingCalled;
import dev.floor42.domain.event.MeetingEvent.MeetingClosed;
import dev.floor42.domain.event.MeetingEvent.MeetingNoteGiven;
import dev.floor42.domain.event.MeetingRoomEvent;
import dev.floor42.domain.event.MeetingRoomEvent.MeetingPlaced;
import dev.floor42.domain.event.MeetingRoomEvent.MeetingTurnTaken;
import dev.floor42.domain.event.MeetingRoomEvent.MeetingViewsAsked;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The director's meetings at the table (ADR-0047): called about one mission with the agents the director names, notes
 * said to one attendee or to all, closed by the director. An agent attends one meeting at a time.
 */
final class Meetings {

    static final int MAX_ATTENDEES = 12;
    static final int MAX_AGENDA = 500;
    static final int MAX_NOTE = 2_000;

    /** How a note becomes directives: the workshop's own rules, checked before anything is recorded. */
    interface Directives {
        /** The ticket the directive would go to, or the workshop's refusal. Records nothing. */
        TicketId check(CommandContext ctx, TicketId ticket);

        List<EventEnvelope> give(CommandContext ctx, TicketId ticket, String directive);
    }

    private final CompanyView view;
    private final MeetingRounds rounds;
    private final Map<String, Meeting> meetings = new LinkedHashMap<>();

    Meetings(CompanyView view) {
        this.view = view;
        this.rounds = new MeetingRounds(view);
    }

    List<Meeting> all() {
        return List.copyOf(meetings.values());
    }

    Optional<Meeting> meeting(String id) {
        return Optional.ofNullable(meetings.get(id));
    }

    List<Meeting> of(MissionId mission) {
        return meetings.values().stream()
                .filter(m -> m.mission().equals(mission))
                .toList();
    }

    Optional<Meeting> openOn(MissionId mission) {
        return of(mission).stream().filter(Meeting::open).findFirst();
    }

    /**
     * The open meeting this agent sits in, if any. A meeting whose mission has ended since holds nobody: the director
     * may still close it, but it keeps no agent from working elsewhere.
     */
    Optional<Meeting> attending(AgentId agent) {
        return meetings.values().stream()
                .filter(m -> m.open() && m.attends(agent) && !missionEnded(m))
                .findFirst();
    }

    private boolean missionEnded(Meeting m) {
        return view.mission(m.mission()).map(x -> x.status().isTerminal()).orElse(true);
    }

    /** What the director said to this agent, or to all, at the meetings of this mission it attended; oldest first. */
    List<MeetingNote> notesFor(MissionId mission, AgentId agent) {
        return of(mission).stream()
                .filter(m -> m.attends(agent))
                .flatMap(m -> m.notesFor(agent).stream())
                .toList();
    }

    List<EventEnvelope> call(
            CommandContext ctx, MissionId mission, List<AgentId> attendees, String agenda, MeetingPlace place) {
        requireDirector(ctx);
        Mission m = view.missionFor(ctx, mission);
        requireOpen(m);
        // A rehearsal is simulated: its facts never hold real agents (ADR-0047).
        require(
                m.origin() == Origin.REAL,
                Refusal.SIMULATED_MISSION,
                mission + " is simulated: a meeting holds real agents, it is called about a real mission only");
        require(
                openOn(mission).isEmpty(),
                Refusal.MEETING_OPEN,
                "A meeting about " + mission + " is already open: close it first");
        List<AgentId> who = attendees(attendees);
        String about = blankToNull(agenda);
        require(
                about == null || about.length() <= MAX_AGENDA,
                Refusal.INVALID_ARGUMENT,
                "An agenda is at most " + MAX_AGENDA + " characters");
        String id = "MT-" + (Math.max(highestMeeting, meetings.size()) + 1);
        Recorder r = view.record(ctx, mission).emit(new MeetingCalled(id, mission, who, about));
        // The meeting table is what every meeting meant before 1.9.0: another table is a fact of its own (ADR-0049).
        if (place == MeetingPlace.CONFERENCE) r.emit(new MeetingPlaced(id, place));
        return r.events();
    }

    private List<AgentId> attendees(List<AgentId> attendees) {
        require(attendees != null && !attendees.isEmpty(), "MISSING_ATTENDEES", "Name at least one attendee");
        List<AgentId> who = attendees.stream().distinct().toList();
        require(
                who.size() <= MAX_ATTENDEES,
                Refusal.INVALID_ARGUMENT,
                "At most " + MAX_ATTENDEES + " agents sit at the table");
        who.forEach(this::checkAttendee);
        return who;
    }

    /** Refused with its reason: an agent who left, one in a committee session, one already in a meeting. */
    private void checkAttendee(AgentId id) {
        Agent a = view.existingAgent(id);
        require(!a.dismissed(), Refusal.AGENT_DISMISSED, id + " has left the company");
        Optional<CommitteeRef> sitting = committeeOf(id);
        require(
                sitting.isEmpty(),
                Refusal.AGENT_IN_COMMITTEE,
                id + " sits on " + sitting.map(CommitteeRef::toString).orElse("") + ", in session: call it once that"
                        + " committee has delivered");
        require(
                attending(id).isEmpty(),
                Refusal.AGENT_IN_MEETING,
                id + " is already in meeting " + attending(id).map(Meeting::id).orElse(""));
        Optional<MissionId> retro = retroOf(id);
        require(
                retro.isEmpty(),
                Refusal.AGENT_IN_RETRO,
                id + " sits at the retrospective of "
                        + retro.map(MissionId::toString).orElse("")
                        + ": call it once that retrospective has concluded");
        Optional<TicketId> staffing = staffingOf(id);
        require(
                staffing.isEmpty(),
                Refusal.AGENT_IN_STAFFING,
                id + " is writing the staffing review "
                        + staffing.map(TicketId::toString).orElse("") + ": call it once that review is advised");
    }

    /** The staffing review this agent writes now (its ticket in progress, ADR-0022): it is never cut either. */
    private Optional<TicketId> staffingOf(AgentId id) {
        return view.tickets().stream()
                .filter(t -> id.equals(t.assignee())
                        && t.status() == TicketStatus.IN_PROGRESS
                        && view.isStaffingTicket(t.id())
                        && view.mission(t.mission())
                                .map(m ->
                                        m.origin() == Origin.REAL && !m.status().isTerminal())
                                .orElse(false))
                .map(Ticket::id)
                .findFirst();
    }

    /** The mission whose retrospective, still open, this agent takes part in: its turn is never cut. */
    private Optional<MissionId> retroOf(AgentId id) {
        return view.openRetros().stream()
                .filter(r -> !r.concluded() && (r.isParticipant(id) || id.equals(r.chair())))
                .map(Retro::mission)
                .findFirst();
    }

    /** The numbered committee in session (ADR-0046) this agent sits on, on a mission in progress. */
    private Optional<CommitteeRef> committeeOf(AgentId id) {
        return view.committees().stream()
                .filter(k -> k.deliberating()
                        && view.mission(k.mission())
                                .map(x -> x.status() == MissionStatus.IN_PROGRESS)
                                .orElse(false))
                .filter(k -> k.isMember(id) || id.equals(k.chair()) || id.equals(k.analyst()))
                .map(Committee::ref)
                .findFirst();
    }

    List<EventEnvelope> note(
            CommandContext ctx,
            String meetingId,
            AgentId attendee,
            String text,
            MeetingNoteUse use,
            Directives directives) {
        requireDirector(ctx);
        Meeting mt = existingOpen(meetingId);
        view.missionFor(ctx, mt.mission());
        require(
                attendee == null || mt.attends(attendee),
                Refusal.NOT_AN_ATTENDEE,
                attendee + " does not attend " + meetingId);
        requireText(text, "text");
        String said = text.strip();
        require(said.length() <= MAX_NOTE, Refusal.INVALID_ARGUMENT, "A note is at most " + MAX_NOTE + " characters");
        List<TicketId> tickets =
                use == MeetingNoteUse.ALSO_DIRECTIVE ? directiveTickets(ctx, mt, attendee, directives) : List.of();
        List<EventEnvelope> events = new ArrayList<>(view.record(ctx, mt.mission())
                .emit(new MeetingNoteGiven(meetingId, attendee, said))
                .events());
        for (TicketId t : tickets)
            events.addAll(directives.give(ctx, t, "From the director's meeting " + meetingId + ": " + said));
        return events;
    }

    /** The ticket each attendee concerned has under way on the mission, each checked by the workshop's rules. */
    private List<TicketId> directiveTickets(CommandContext ctx, Meeting mt, AgentId attendee, Directives directives) {
        List<TicketId> tickets = mt.attendees().stream()
                .filter(a -> attendee == null || attendee.equals(a))
                .map(a -> underWay(mt.mission(), a))
                .flatMap(Optional::stream)
                .map(t -> directives.check(ctx, t))
                .distinct()
                .toList();
        require(
                !tickets.isEmpty(),
                Refusal.NO_TICKET_UNDER_WAY,
                "No attendee concerned has a ticket under way on " + mt.mission() + ": keep it as a note");
        return tickets;
    }

    /** The agent's ticket on the mission: in progress first, then in review, then to do. */
    private Optional<TicketId> underWay(MissionId mission, AgentId agent) {
        List<Ticket> mine = view.tickets(mission).stream()
                .filter(t -> agent.equals(t.assignee()))
                .toList();
        for (TicketStatus s : List.of(TicketStatus.IN_PROGRESS, TicketStatus.IN_REVIEW, TicketStatus.TODO)) {
            Optional<Ticket> t = mine.stream().filter(x -> x.status() == s).findFirst();
            if (t.isPresent()) return t.map(Ticket::id);
        }
        return Optional.empty();
    }

    List<EventEnvelope> close(CommandContext ctx, String meetingId) {
        requireDirector(ctx);
        Meeting mt = existingOpen(meetingId);
        view.missionFor(ctx, mt.mission());
        return view.record(ctx, mt.mission())
                .emit(new MeetingClosed(meetingId, mt.mission(), mt.attendees()))
                .events();
    }

    /** The director asks these attendees for their view, in this order, within a cap for the meeting (ADR-0049). */
    List<EventEnvelope> ask(
            CommandContext ctx, String meetingId, List<AgentId> speakers, double capUsd, String question) {
        return rounds.ask(ctx, existingOpen(meetingId), speakers, capUsd, question);
    }

    /** A turn of a round, recorded by Floor42's runtime; the meeting may have closed while it ran. */
    List<EventEnvelope> turn(CommandContext ctx, String meetingId, SpokenTurn turn) {
        Meeting mt = meetings.get(meetingId);
        require(mt != null, Refusal.UNKNOWN_MEETING, "No meeting " + meetingId);
        return rounds.turn(ctx, mt, turn);
    }

    /** What the meeting's turns cost so far, as Claude Code estimated them. */
    long spentMicroUsd(Meeting mt) {
        return rounds.spentMicroUsd(mt);
    }

    /** The open meeting whose ticket this is, the one kind of work on a meeting's ticket (ADR-0049). */
    Optional<Meeting> ofTicket(TicketId ticket) {
        return meetings.values().stream().filter(m -> ticket.equals(m.ticket())).findFirst();
    }

    private Meeting existingOpen(String id) {
        Meeting mt = meetings.get(id);
        require(mt != null, Refusal.UNKNOWN_MEETING, "No meeting " + id);
        require(mt.open(), Refusal.MEETING_CLOSED, id + " is closed");
        return mt;
    }

    /** The highest MT-n number ever called, read or not: a number is never given twice. */
    private transient int highestMeeting;

    /** A meeting number held by a journal row this version could not read: no new meeting takes it. */
    void reserve(String id) {
        if (id.matches("MT-\\d{1,9}")) highestMeeting = Math.max(highestMeeting, Integer.parseInt(id.substring(3)));
    }

    /** The only way this area's state changes. */
    void apply(MeetingEvent event, EventEnvelope envelope) {
        switch (event) {
            case MeetingCalled e -> {
                // Two meetings of one id would merge: the replay stops instead.
                if (meetings.containsKey(e.meeting()))
                    throw new IllegalStateException(e.meeting() + " is created twice");
                reserve(e.meeting());
                meetings.put(
                        e.meeting(),
                        new Meeting(e.meeting(), e.mission(), e.attendees(), e.agenda(), envelope.occurredAt()));
            }
            case MeetingNoteGiven e ->
                meetings.get(e.meeting())
                        .notes
                        .add(new MeetingNote(e.meeting(), e.attendee(), e.text(), envelope.occurredAt()));
            case MeetingClosed e -> meetings.get(e.meeting()).closedAt = envelope.occurredAt();
            case MeetingRoomEvent e -> apply(e, envelope);
        }
    }

    private void apply(MeetingRoomEvent event, EventEnvelope envelope) {
        switch (event) {
            case MeetingPlaced e -> meetings.get(e.meeting()).place = e.place();
            case MeetingViewsAsked e -> {
                Meeting mt = meetings.get(e.meeting());
                mt.ticket = e.ticket();
                mt.rounds.add(
                        new MeetingRound(e.round(), e.speakers(), e.capUsd(), e.question(), envelope.occurredAt()));
            }
            case MeetingTurnTaken e ->
                meetings.get(e.meeting())
                        .rounds
                        .get(e.round() - 1)
                        .turns
                        .put(
                                e.attendee(),
                                new MeetingTurn(
                                        e.attendee(),
                                        e.session(),
                                        e.answer(),
                                        e.why(),
                                        e.budgetUsd(),
                                        envelope.occurredAt()));
        }
    }
}
