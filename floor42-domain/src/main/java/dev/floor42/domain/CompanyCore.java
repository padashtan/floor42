package dev.floor42.domain;

import dev.floor42.domain.event.*;
import dev.floor42.domain.event.ActionEvent.*;
import dev.floor42.domain.event.CommitteeEvent.*;
import dev.floor42.domain.event.CommitteeSeatEvent.*;
import dev.floor42.domain.event.DeliveryEvent.*;
import dev.floor42.domain.event.DeskEvent.*;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MeetingEvent.*;
import dev.floor42.domain.event.MeetingRoomEvent.MeetingViewsAsked;
import dev.floor42.domain.event.MissionEvent.*;
import dev.floor42.domain.event.NotebookEvent.*;
import dev.floor42.domain.event.PreferenceEvent.*;
import dev.floor42.domain.event.RecentProjectEvent.*;
import dev.floor42.domain.event.RetroEvent.*;
import dev.floor42.domain.event.RosterEvent.*;
import dev.floor42.domain.event.SessionEvent.*;
import dev.floor42.domain.event.StaffingEvent.*;
import dev.floor42.domain.event.TerminalEvent.*;
import dev.floor42.domain.event.TicketEvent.*;
import dev.floor42.domain.event.VerificationEvent.*;
import dev.floor42.domain.event.WorkbenchEvent.*;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The company's state: its areas and the one place where events reach them. {@link Company} holds one and reaches
 * each area through its facades; nothing else does.
 */
final class CompanyCore {

    /** When anything last happened to each mission: the time of its latest event. */
    private final Map<MissionId, Instant> lastActivity = new HashMap<>();

    private long version;

    final CompanyView view = new View();
    final Preferences preferences = new Preferences(view);
    final Notebook notebook = new Notebook(view);
    final Terminals terminals = new Terminals(view);
    final RecentProjects recentProjects = new RecentProjects(view);
    final Meetings meetings = new Meetings(view);
    final WorkflowBook workflowBook = new WorkflowBook(view);
    final Deliveries deliveries = new Deliveries(view);
    final Roster roster = new Roster(view);
    final MissionBook missionBook = new MissionBook(view);
    final TicketBoard ticketBoard = new TicketBoard(view);
    final TicketReviews ticketReviews = new TicketReviews(view);
    final TicketPlanning ticketPlanning = new TicketPlanning(view);
    final ActionLog actionLog = new ActionLog(view);
    final Desk desk = new Desk(view);
    final SessionLog sessionLog = new SessionLog(view);
    final Verification verification = new Verification(view);
    final Workbench workbench = new Workbench(view);
    final CommitteeHall committeeHall = new CommitteeHall(view);
    final CommitteeSeats committeeSeats = new CommitteeSeats(view);
    final StaffingBoard staffingBoard = new StaffingBoard(view);
    final Retrospectives retrospectives = new Retrospectives(view);
    final CompanyQueries queries = new CompanyQueries(view);
    final Answers answers = new Answers(desk, actionLog.approvals(), committeeHall, verification, ticketReviews);

    /** Number of events applied so far. */
    long version() {
        return version;
    }

    /** When anything last happened to the mission (its latest event); empty for an unknown mission. */
    Optional<Instant> lastActivity(MissionId mission) {
        return Optional.ofNullable(lastActivity.get(mission));
    }

    /**
     * The only place where state changes: each event goes to its area. An event that changes two areas reaches the
     * second one in a fixed step, in the order the company has always applied it.
     */
    void apply(EventEnvelope envelope) {
        // Putting a mission away or back is the director's filing, not activity on it (ADR-0054): the recent order
        // stays.
        boolean filing = envelope.event() instanceof MissionEvent.MissionArchived
                || envelope.event() instanceof MissionEvent.MissionUnarchived;
        if (envelope.mission() != null && !filing) {
            lastActivity.merge(envelope.mission(), envelope.occurredAt(), (a, b) -> a.isAfter(b) ? a : b);
        }
        DomainEvent event = envelope.event();
        firstStep(event, envelope);
        toArea(event, envelope);
        secondStep(event, envelope);
        version++;
    }

    /** The one area whose state the event changes first. */
    private void toArea(DomainEvent event, EventEnvelope envelope) {
        switch (event) {
            case RosterEvent e -> roster.apply(e, envelope);
            case MissionEvent e -> missionBook.apply(e, envelope);
            case TicketEvent e -> ticketBoard.apply(e, envelope);
            case ActionEvent e -> actionLog.apply(e, envelope);
            case DeskEvent e -> desk.apply(e, envelope);
            case SessionEvent e -> sessionLog.apply(e, envelope);
            case VerificationEvent e -> verification.apply(e, envelope);
            case WorkbenchEvent e -> workbench.apply(e, envelope);
            case CommitteeEvent e -> committeeHall.apply(e, envelope);
            case CommitteeSeatEvent e -> committeeSeats.apply(e, envelope);
            case StaffingEvent e -> staffingBoard.apply(e, envelope);
            case RetroEvent e -> retrospectives.apply(e, envelope);
            case DeliveryEvent e -> deliveries.apply(e, envelope);
            case PreferenceEvent e -> preferences.apply(e, envelope);
            case NotebookEvent e -> notebook.apply(e, envelope);
            case TerminalEvent e -> terminals.apply(e, envelope);
            case RecentProjectEvent e -> recentProjects.apply(e, envelope);
            case MeetingEvent e -> meetings.apply(e, envelope);
            case WorkflowEvent e -> workflowBook.apply(e, envelope);
        }
    }

    /** The ticket some events need before their own area applies them. */
    private void firstStep(DomainEvent event, EventEnvelope envelope) {
        // A retrospective's ticket exists before the retrospective itself.
        if (event instanceof RetroOpened e) ticketBoard.retroOpened(e);
        // A meeting's first ask opens its own ticket, before the meeting records the round (ADR-0049).
        if (event instanceof MeetingViewsAsked e)
            ticketBoard.meetingAsked(e, envelope.mission(), envelope.occurredAt());
    }

    private void secondStep(DomainEvent event, EventEnvelope envelope) {
        Instant at = envelope.occurredAt();
        if (event instanceof AgentSessionEnded e) {
            actionLog.orphanAfter(sessionLog.session(e.session()).orElseThrow());
        } else if (event instanceof QuestionAsked e) {
            staffingBoard.asked(e);
            committeeHall.asked(e);
        } else if (event instanceof StaffingAdvised e) {
            ticketBoard.staffingAdvised(e, at);
        } else if (event instanceof DirectiveGiven e) {
            workbench.on(e, envelope);
        } else if (event instanceof RetroConcluded e) {
            ticketBoard.retroConcluded(retrospectives.retro(e.mission()).orElseThrow());
        } else if (event instanceof MissionCancelled e) {
            // Older journals left the mission's open tickets open: they read cancelled with it (idempotent after).
            view.tickets(e.mission()).forEach(t -> t.cancelledWithMission(at));
        }
    }

    /** What each area of the company sees of the others. */
    private final class View implements CompanyView {

        @Override
        public Recorder record(CommandContext ctx, MissionId mission) {
            return new Recorder(ctx, mission, CompanyCore.this::apply);
        }

        @Override
        public dev.floor42.domain.workflow.MissionWorkflow workflowOf(MissionId mission) {
            return workflowBook.of(mission);
        }

        @Override
        public java.util.List<dev.floor42.domain.workflow.WorkflowProposal> proposals() {
            return workflowBook.proposals();
        }

        @Override
        public java.util.Map<String, TicketId> workflowLinks(MissionId mission) {
            return workflowBook.linked(mission);
        }

        @Override
        public Optional<NodePlan> nodeOf(TicketId ticket) {
            return workflowBook.nodeOf(ticket);
        }

        @Override
        public boolean checksRun(Ticket ticket) {
            return workflowBook.checksRun(ticket);
        }

        @Override
        public int nodeTestFailures(TicketId node) {
            return verification.nodeTestFailures(node);
        }

        @Override
        public Optional<String> workflowStepOf(Ticket ticket) {
            return workflowBook.stepOf(ticket.mission(), ticket.id());
        }

        @Override
        public Mission missionFor(CommandContext ctx, MissionId id) {
            return missionBook.missionFor(ctx, id);
        }

        @Override
        public Optional<Mission> mission(MissionId id) {
            return missionBook.mission(id);
        }

        @Override
        public Collection<Mission> missions() {
            return missionBook.missionsView();
        }

        @Override
        public Agent existingAgent(AgentId id) {
            return roster.existing(id);
        }

        @Override
        public Optional<Agent> agent(AgentId id) {
            return roster.agent(id);
        }

        @Override
        public Collection<Agent> agents() {
            return roster.agentsView();
        }

        @Override
        public Ticket existingTicket(TicketId id) {
            return ticketBoard.existing(id);
        }

        @Override
        public ReviewRounds reviewRounds(TicketId id) {
            return ticketBoard.reviewRounds(id);
        }

        @Override
        public Optional<Ticket> ticket(TicketId id) {
            return ticketBoard.ticket(id);
        }

        @Override
        public Collection<Ticket> tickets() {
            return ticketBoard.ticketsView();
        }

        @Override
        public List<Ticket> tickets(MissionId mission) {
            return ticketBoard.tickets(mission);
        }

        @Override
        public TicketId nextTicketId() {
            return ticketBoard.nextId();
        }

        @Override
        public Action existingAction(ActionId id) {
            return actionLog.existing(id);
        }

        @Override
        public Optional<Action> action(ActionId id) {
            return actionLog.action(id);
        }

        @Override
        public Collection<Action> actions() {
            return actionLog.actionsView();
        }

        @Override
        public List<Action> actions(MissionId mission) {
            return actionLog.actions(mission);
        }

        @Override
        public int incidentCount() {
            return actionLog.incidentCount();
        }

        @Override
        public Question existingQuestion(QuestionId id) {
            return desk.existing(id);
        }

        @Override
        public Optional<Question> question(QuestionId id) {
            return desk.question(id);
        }

        @Override
        public Collection<Question> questions() {
            return desk.questionsView();
        }

        @Override
        public List<Question> questions(MissionId mission) {
            return desk.questions(mission);
        }

        @Override
        public QuestionId nextQuestionId() {
            return desk.nextId();
        }

        @Override
        public boolean forgotten(QuestionId id) {
            return desk.forgotten(id);
        }

        @Override
        public boolean remembered(QuestionId id) {
            return desk.remembered(id);
        }

        @Override
        public Optional<AgentSession> session(String id) {
            return sessionLog.session(id);
        }

        @Override
        public Collection<AgentSession> sessions() {
            return sessionLog.sessionsView();
        }

        @Override
        public List<AgentSession> sessions(MissionId mission) {
            return sessionLog.sessions(mission);
        }

        @Override
        public Optional<Workspace> workspace(MissionId mission, AgentId agent) {
            return workbench.workspace(mission, agent);
        }

        @Override
        public boolean editedByDirector(MissionId mission, AgentId agent, String path) {
            return workbench.editedByDirector(mission, agent, path);
        }

        @Override
        public List<Intervention> interventions(MissionId mission) {
            return workbench.interventions(mission);
        }

        @Override
        public Optional<Committee> committee(CommitteeRef ref) {
            return committeeHall.committee(ref);
        }

        @Override
        public List<Committee> committees(MissionId mission) {
            return committeeHall.committees(mission);
        }

        @Override
        public Collection<Committee> committees() {
            return committeeHall.committeesView();
        }

        @Override
        public Optional<Retro> retro(MissionId mission) {
            return retrospectives.retro(mission);
        }

        @Override
        public List<Retro> openRetros() {
            return retrospectives.openRetros();
        }

        @Override
        public boolean isStaffingTicket(TicketId ticket) {
            return staffingBoard.isStaffingTicket(ticket);
        }

        @Override
        public int lessonCount() {
            return retrospectives.lessons().size();
        }

        @Override
        public Optional<Meeting> meetingOf(AgentId agent) {
            return meetings.attending(agent);
        }

        @Override
        public List<Meeting> meetingsOf(MissionId mission) {
            return meetings.of(mission);
        }

        @Override
        public Optional<Decision> defaultPermission(String tool) {
            return preferences.defaultPermission(tool);
        }

        @Override
        public Optional<Meeting> meetingOfTicket(TicketId ticket) {
            return meetings.ofTicket(ticket);
        }
    }
}
