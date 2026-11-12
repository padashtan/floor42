package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;

import java.util.List;

/**
 * What an agent is doing now, derived from the facts of every area, in order of precedence. Only active missions count,
 * so an agent never appears busy on a finished or cancelled mission.
 */
final class AgentStatusRule {

    private final CompanyView view;

    AgentStatusRule(CompanyView view) {
        this.view = view;
    }

    AgentStatus statusOf(AgentId id) {
        require(view.agent(id).isPresent(), Refusal.UNKNOWN_AGENT, "No agent " + id);
        if (waitsForDirector(id)) return AgentStatus.WAITING_FOR_DIRECTOR;
        List<Action> live = view.actions().stream()
                .filter(a -> a.agent().equals(id) && isActive(a.mission()) && !a.orphaned && !atMeeting(a))
                .toList();
        if (live.stream().anyMatch(a -> a.stage() == ActionStage.REQUESTED)) {
            return AgentStatus.WAITING_FOR_AUTHORIZATION;
        }
        if (live.stream().anyMatch(a -> a.stage() == ActionStage.AUTHORIZED)) return AgentStatus.EXECUTING;
        // The call it was making is done: the director's meeting comes before any work.
        if (view.meetingOf(id).isPresent()) return AgentStatus.IN_MEETING;
        if (sits(id)) return AgentStatus.IN_COMMITTEE;
        if (reviews(id)) return AgentStatus.REVIEWING;
        return byTickets(id);
    }

    private AgentStatus byTickets(AgentId id) {
        List<Ticket> mine = view.tickets().stream()
                .filter(t -> id.equals(t.assignee()) && !t.status().isTerminal())
                .toList();
        if (mine.stream().anyMatch(t -> t.status() == TicketStatus.IN_PROGRESS && isActive(t.mission()))) {
            return AgentStatus.WORKING;
        }
        // Nothing under way elsewhere: a participant of a retrospective still open sits at the table.
        if (view.openRetros().stream().anyMatch(r -> !r.concluded && r.isParticipant(id))) return AgentStatus.IN_RETRO;
        if (mine.stream().anyMatch(t -> !statusOf(t).isTerminal() && statusOf(t) != MissionStatus.SUSPENDED)) {
            return AgentStatus.ASSIGNED;
        }
        if (mine.stream().anyMatch(t -> statusOf(t) == MissionStatus.SUSPENDED)) return AgentStatus.PAUSED;
        return AgentStatus.AVAILABLE;
    }

    /** A read at the meeting (its own ticket, ADR-0049) keeps the speaker seated: it is no work at its desk. */
    private boolean atMeeting(Action a) {
        return a.ticket() != null
                && view.ticket(a.ticket())
                        .map(t -> t.kind() == TicketKind.MEETING)
                        .orElse(false);
    }

    private boolean waitsForDirector(AgentId id) {
        return view.questions().stream()
                .anyMatch(q -> q.status() == QuestionStatus.OPEN && q.blocking() && id.equals(q.askedBy()));
    }

    private boolean sits(AgentId id) {
        return view.committees().stream().anyMatch(c -> c.isMember(id) && c.deliberating() && isActive(c.mission()));
    }

    private boolean reviews(AgentId id) {
        return view.sessions().stream()
                .anyMatch(s -> s.isOpen()
                        && s.agent().equals(id)
                        && isActive(s.mission())
                        && view.ticket(s.ticket()).orElseThrow().kind() != TicketKind.MEETING
                        && !id.equals(view.ticket(s.ticket()).orElseThrow().assignee));
    }

    private MissionStatus statusOf(Ticket t) {
        return view.mission(t.mission()).orElseThrow().status();
    }

    private boolean isActive(MissionId mission) {
        return view.mission(mission).orElseThrow().status() == MissionStatus.IN_PROGRESS;
    }
}
