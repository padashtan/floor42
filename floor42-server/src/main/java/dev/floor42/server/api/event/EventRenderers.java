package dev.floor42.server.api.event;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Company;
import dev.floor42.domain.event.ActionEvent;
import dev.floor42.domain.event.CommitteeEvent;
import dev.floor42.domain.event.CommitteeSeatEvent;
import dev.floor42.domain.event.DeliveryEvent;
import dev.floor42.domain.event.DeskEvent;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.MeetingEvent;
import dev.floor42.domain.event.MissionEvent;
import dev.floor42.domain.event.NotebookEvent;
import dev.floor42.domain.event.PreferenceEvent;
import dev.floor42.domain.event.RecentProjectEvent;
import dev.floor42.domain.event.RetroEvent;
import dev.floor42.domain.event.RosterEvent;
import dev.floor42.domain.event.SessionEvent;
import dev.floor42.domain.event.StaffingEvent;
import dev.floor42.domain.event.TerminalEvent;
import dev.floor42.domain.event.TicketEvent;
import dev.floor42.domain.event.VerificationEvent;
import dev.floor42.domain.event.WorkbenchEvent;
import dev.floor42.domain.event.WorkflowEvent;

/** Hands each event to the renderer of its area: a new area does not build until it has one. */
public final class EventRenderers {

    private static final RosterRenderer ROSTER = new RosterRenderer();
    private static final MissionRenderer MISSION = new MissionRenderer();
    private static final TicketRenderer TICKET = new TicketRenderer();
    private static final ActionRenderer ACTION = new ActionRenderer();
    private static final DeskRenderer DESK = new DeskRenderer();
    private static final SessionRenderer SESSION = new SessionRenderer();
    private static final VerificationRenderer VERIFICATION = new VerificationRenderer();
    private static final WorkbenchRenderer WORKBENCH = new WorkbenchRenderer();
    private static final CommitteeRenderer COMMITTEE = new CommitteeRenderer();
    private static final CommitteeSeatRenderer COMMITTEE_SEAT = new CommitteeSeatRenderer();
    private static final StaffingRenderer STAFFING = new StaffingRenderer();
    private static final RetroRenderer RETRO = new RetroRenderer();
    private static final DeliveryRenderer DELIVERY = new DeliveryRenderer();
    private static final PreferenceRenderer PREFERENCE = new PreferenceRenderer();
    private static final NotebookRenderer NOTEBOOK = new NotebookRenderer();
    private static final TerminalRenderer TERMINAL = new TerminalRenderer();
    private static final RecentProjectRenderer RECENT_PROJECT = new RecentProjectRenderer();
    private static final MeetingRenderer MEETING = new MeetingRenderer();
    private static final WorkflowRenderer WORKFLOW = new WorkflowRenderer();

    private EventRenderers() {}

    /** The event's line, read against the company as it stands, by whoever caused it. */
    public static EventLine render(Company c, DomainEvent event, Actor actor) {
        return switch (event) {
            case RosterEvent e -> ROSTER.render(c, e);
            case MissionEvent e -> MISSION.render(c, e);
            case TicketEvent e -> TICKET.render(c, e, actor);
            case ActionEvent e -> ACTION.render(c, e);
            case DeskEvent e -> DESK.render(c, e);
            case SessionEvent e -> SESSION.render(c, e);
            case VerificationEvent e -> VERIFICATION.render(c, e);
            case WorkbenchEvent e -> WORKBENCH.render(c, e);
            case CommitteeEvent e -> COMMITTEE.render(c, e);
            case CommitteeSeatEvent e -> COMMITTEE_SEAT.render(c, e);
            case StaffingEvent e -> STAFFING.render(c, e);
            case RetroEvent e -> RETRO.render(c, e);
            case DeliveryEvent e -> DELIVERY.render(c, e);
            case PreferenceEvent e -> PREFERENCE.render(c, e);
            case NotebookEvent e -> NOTEBOOK.render(c, e);
            case TerminalEvent e -> TERMINAL.render(c, e);
            case RecentProjectEvent e -> RECENT_PROJECT.render(c, e);
            case MeetingEvent e -> MEETING.render(c, e);
            case WorkflowEvent e -> WORKFLOW.render(c, e);
        };
    }

    /** The line's message code: the event type, plus the variant where the sentence changes with the event. */
    public static String code(DomainEvent event, EventLine line) {
        String type = event.getClass().getSimpleName();
        return line.variant() == null ? type : type + "." + line.variant();
    }
}
