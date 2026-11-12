package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireDirector;
import static dev.floor42.domain.Rules.requireText;

import dev.floor42.domain.event.CommitteeEvent.CommitteeConvened;
import java.util.List;

/**
 * The checks of a committee's convening, and the event that records it with the committee's number. The committees are
 * the {@link CommitteeHall}'s, which applies the event.
 */
final class CommitteeConvening {

    private final CompanyView view;

    CommitteeConvening(CompanyView view) {
        this.view = view;
    }

    /**
     * Part of a plan. A mission convenes its next committee once the latest one is ruled on; while one is still open,
     * another is refused, as before numbers existed.
     */
    CommitteeConvened check(
            CommandContext ctx,
            MissionId id,
            TicketId ticket,
            String topic,
            List<AgentId> members,
            List<String> roles,
            Committee.Format format) {
        requireDirector(ctx);
        require(format != null, Refusal.INVALID_ARGUMENT, "How does the committee run?");
        Mission m = view.missionFor(ctx, id);
        require(
                m.status == MissionStatus.PLANNING,
                Refusal.INVALID_TRANSITION,
                "A committee is part of the plan of " + id);
        List<Committee> held = view.committees(id);
        require(held.isEmpty() || held.getLast().ruled(), Refusal.DUPLICATE_COMMITTEE, id + " already has a committee");
        requireText(topic, "topic");
        checkMembers(members, roles, format);
        Ticket t = view.existingTicket(ticket);
        Rules.requireWorkTicket(t);
        require(t.mission().equals(id), Refusal.UNKNOWN_TICKET, ticket + " is not part of " + id);
        require(
                members.getFirst().equals(t.assignee),
                Refusal.INVALID_ARGUMENT,
                "The chair is the decision ticket's assignee");
        return convened(new CommitteeRef(id, held.size() + 1), ticket, topic, members, roles, format);
    }

    private static CommitteeConvened convened(
            CommitteeRef ref,
            TicketId ticket,
            String topic,
            List<AgentId> members,
            List<String> roles,
            Committee.Format format) {
        AgentId seat = format.directorSeat();
        return new CommitteeConvened(
                ref.mission(),
                ticket,
                topic,
                members.stream().map(AgentId::value).toList(),
                List.copyOf(roles),
                format.panel(),
                format.moderated(),
                format.maxDebateRounds(),
                seat == null ? null : seat.value(),
                ref.number());
    }

    private void checkMembers(List<AgentId> members, List<String> roles, Committee.Format format) {
        require(
                members != null && roles != null && members.size() == roles.size(),
                Refusal.INVALID_ARGUMENT,
                "One role per member");
        require(
                members.size() >= (format.moderated() ? 3 : 2),
                Refusal.INVALID_ARGUMENT,
                format.moderated()
                        ? "A moderated committee needs at least two members besides the moderator"
                        : "A committee needs at least two members");
        AgentId seat = format.directorSeat();
        require(
                seat == null || (members.contains(seat) && !seat.equals(members.getFirst())),
                Refusal.INVALID_ARGUMENT,
                "The director's seat is a member's, not the chair's");
        require(
                members.stream().distinct().count() == members.size(),
                Refusal.INVALID_ARGUMENT,
                "Members are distinct");
        members.forEach(
                a -> require(!view.existingAgent(a).dismissed, Refusal.AGENT_DISMISSED, a + " has left the company"));
    }
}
