package dev.floor42.domain;

/**
 * Who may work on a ticket right now: its producer while it is in progress, another agent while it is in review, a
 * member (or the business analyst) of the committee deliberating on it, and a participant of a retrospective still open.
 * Sessions and tool requests are both checked against it.
 */
final class WorkEligibility {

    private final CompanyView view;

    WorkEligibility(CompanyView view) {
        this.view = view;
    }

    boolean worksOn(AgentId agent, Ticket t) {
        if (t.kind() == TicketKind.MEETING) return speaks(agent, t);
        boolean producing = agent.equals(t.assignee) && t.status == TicketStatus.IN_PROGRESS;
        boolean reviewing = !agent.equals(t.assignee) && t.status == TicketStatus.IN_REVIEW;
        return producing || reviewing || sits(agent, t);
    }

    private boolean sits(AgentId agent, Ticket t) {
        return t.status == TicketStatus.IN_PROGRESS
                && view.committees(t.mission()).stream()
                        .anyMatch(c -> c.ticket().equals(t.id())
                                && (c.isMember(agent) || agent.equals(c.analyst()))
                                && c.deliberating());
    }

    /**
     * A meeting's own ticket is worked by one agent at a time: the next speaker of the round under way, while the meeting
     * is open (ADR-0049). Nobody else, ever.
     */
    private boolean speaks(AgentId agent, Ticket t) {
        return view.meetingOfTicket(t.id())
                .filter(Meeting::open)
                .flatMap(Meeting::roundUnderWay)
                .flatMap(MeetingRound::next)
                .map(agent::equals)
                .orElse(false);
    }

    /** The retrospective is the one kind of work on an ended mission (ADR-0020). */
    boolean speaksInRetro(AgentId agent, Ticket t) {
        Retro r = view.retro(t.mission()).orElse(null);
        return r != null && !r.concluded && r.ticket().equals(t.id()) && r.isParticipant(agent);
    }
}
