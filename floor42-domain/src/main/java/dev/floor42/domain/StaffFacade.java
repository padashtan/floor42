package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The staff: agents hired, reconfigured, starred, suspended or dismissed, and the staffing reviews the director asks
 * the planner for. Reached by {@link Company#staff()}; the rules live in the roster and the staffing board.
 */
public final class StaffFacade {

    private final Roster roster;
    private final StaffingBoard staffingBoard;
    private final TicketsFacade tickets;

    StaffFacade(Roster roster, StaffingBoard staffingBoard, TicketsFacade tickets) {
        this.roster = roster;
        this.staffingBoard = staffingBoard;
        this.tickets = tickets;
    }

    public Collection<Agent> all() {
        return roster.agents();
    }

    public Optional<Agent> find(AgentId id) {
        return roster.agent(id);
    }

    public List<EventEnvelope> hire(
            CommandContext ctx,
            AgentId id,
            String displayName,
            String role,
            String provider,
            String model,
            String effort,
            List<String> skills) {
        return roster.hire(ctx, id, displayName, role, provider, model, effort, skills);
    }

    /** Hires an agent for a work role (ADR-0019): what Floor42 gives it to do follows from the role, by code. */
    public List<EventEnvelope> hire(
            CommandContext ctx,
            AgentId id,
            String displayName,
            String role,
            WorkRole workRole,
            String model,
            String effort) {
        return roster.hire(ctx, id, displayName, role, workRole, model, effort);
    }

    /**
     * The agent leaves: refused only while it is at work right now (an open session), so nothing is cut in the middle.
     * A ticket it had started goes back to the queue (its changes stay on its branch); a delivery it handed over stays
     * in review. Tickets still to do that were assigned to it are given to another agent of the same role.
     */
    public List<EventEnvelope> dismiss(CommandContext ctx, AgentId id, String reason) {
        return roster.dismiss(ctx, id, reason);
    }

    public List<EventEnvelope> suspendAssignments(CommandContext ctx, AgentId id, String reason) {
        return roster.suspend(ctx, id, reason);
    }

    public List<EventEnvelope> resumeAssignments(CommandContext ctx, AgentId id) {
        return roster.resume(ctx, id);
    }

    /** The model and effort of the agent's next sessions; a session already running keeps its own. */
    public List<EventEnvelope> reconfigure(CommandContext ctx, AgentId id, String model, String effort) {
        return roster.reconfigure(ctx, id, model, effort);
    }

    /**
     * The director stars an agent as a core member, or takes the star away. A real decision of the director only (never
     * an agent's, never Floor42's, never in a simulation); asking for the state the agent is already in records nothing.
     */
    public List<EventEnvelope> markCore(CommandContext ctx, AgentId id, boolean core) {
        return core ? roster.markCore(ctx, id) : roster.unmarkCore(ctx, id);
    }

    /**
     * The director asks the planner whether more developers would finish the mission sooner, at a cost worth it. The
     * review gets its own ticket, the director's (no approval), assigned to the planner and started at once.
     */
    public List<EventEnvelope> requestStaffingReview(CommandContext ctx, MissionId mission, AgentId planner) {
        return staffingBoard.requestStaffingReview(ctx, mission, planner, tickets::create);
    }

    /**
     * Records the planner's advice (or, from Floor42, why there is none) and, when it names developers to hire, asks
     * the director yes or no. The question blocks nothing: the mission goes on meanwhile.
     */
    public List<EventEnvelope> adviseStaffing(
            CommandContext ctx,
            MissionId mission,
            String advice,
            boolean worthIt,
            List<StaffingReview.Hire> hires,
            Integer parallel,
            String figures) {
        return staffingBoard.adviseStaffing(ctx, mission, advice, worthIt, hires, parallel, figures);
    }

    /** The latest staffing review of a mission, if any. */
    public Optional<StaffingReview> staffingReview(MissionId mission) {
        return staffingBoard.staffingReview(mission);
    }

    /** The ticket is a staffing review: worked by the planner alone, never by the dispatcher. */
    public boolean isStaffingTicket(TicketId ticket) {
        return staffingBoard.isStaffingTicket(ticket);
    }
}
