package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;

/** The checks every area of the company makes the same way, with the same refusal codes and messages. */
final class Rules {

    private Rules() {}

    static void requireDirector(CommandContext ctx) {
        require(ctx.actor().isDirector(), Refusal.DIRECTOR_ONLY, "Only the director can do this");
    }

    /** The director, or Floor42 writing on the director's behalf (a mission's table at its creation); never an agent. */
    static void requireDirectorOrFloor42(CommandContext ctx) {
        require(
                ctx.actor().isDirector() || ctx.actor().kind() == Actor.Kind.SYSTEM,
                Refusal.DIRECTOR_ONLY,
                "Only the director can do this");
    }

    static void requireSystem(CommandContext ctx, String message) {
        require(ctx.actor().kind() == Actor.Kind.SYSTEM, Refusal.FORBIDDEN, message);
    }

    static void requireReal(CommandContext ctx, String code, String message) {
        require(ctx.origin() == Origin.REAL, code, message);
    }

    static void requireText(String value, String field) {
        require(value != null && !value.isBlank(), "MISSING_" + field.toUpperCase(), field + " is required");
    }

    static void requireOpen(Mission m) {
        require(!m.status.isTerminal(), Refusal.MISSION_CLOSED, m.id() + " is closed");
    }

    static void requireTransition(Mission m, MissionStatus next) {
        require(
                m.status.canMoveTo(next),
                Refusal.INVALID_TRANSITION,
                m.id() + ": " + m.status + " -> " + next + " is not allowed");
    }

    static void requireTransition(Ticket t, TicketStatus next) {
        require(
                t.status.canMoveTo(next),
                Refusal.INVALID_TRANSITION,
                t.id() + ": " + t.status + " -> " + next + " is not allowed");
    }

    /** The director's actions on workspaces never happen under a running agent's feet. */
    static void requireNoAgentAtWork(CompanyView view, MissionId id) {
        require(
                view.sessions(id).stream().noneMatch(AgentSession::isOpen),
                Refusal.AGENT_AT_WORK,
                "An agent session is running on " + id + ": wait for it to end");
    }

    /** A meeting's own ticket (ADR-0049) is worked by its turns of speech only, never by a ticket's commands. */
    static void requireWorkTicket(Ticket t) {
        require(
                t.kind() != TicketKind.MEETING,
                Refusal.MEETING_TICKET,
                t.id() + " is a meeting's own ticket: only the meeting's turns of speech work on it");
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** The agent behind the command, or null when the director or Floor42 issued it. */
    static AgentId agentOf(CommandContext ctx) {
        return ctx.actor().kind() == Actor.Kind.AGENT ? new AgentId(ctx.actor().id()) : null;
    }
}
