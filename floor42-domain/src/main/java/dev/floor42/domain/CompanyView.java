package dev.floor42.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * What one area of the company may see of the others: lookups, never a way to change them. Each area changes only its
 * own state, through the events it applies; it reaches another area's state here, so no area holds a reference to
 * another. The one thing beyond reading is {@link #record}: the events it records go through the company's
 * {@code apply}, like every event.
 */
interface CompanyView {

    Recorder record(CommandContext ctx, MissionId mission);

    /** The mission, checked to exist and to share the command's origin (a real command never touches a simulation). */
    Mission missionFor(CommandContext ctx, MissionId id);

    Optional<Mission> mission(MissionId id);

    /** The workflow the mission follows (ADR-0050). */
    dev.floor42.domain.workflow.MissionWorkflow workflowOf(MissionId mission);

    /** The planner's workflow proposals, (an open one of a cancelled mission reads dismissed). */
    java.util.List<dev.floor42.domain.workflow.WorkflowProposal> proposals();

    /** The tickets the mission's workflow tasks opened, by task key (ADR-0061). */
    java.util.Map<String, TicketId> workflowLinks(MissionId mission);

    /** The node's risk, tier and tests in a graph mission (ADR-0062); empty for every other ticket. */
    Optional<NodePlan> nodeOf(TicketId ticket);

    /** Whether the workflow's checks run on the ticket: always in the loop, on high-risk nodes in graph mode. */
    boolean checksRun(Ticket ticket);

    /** The tests of this node that failed so far (ADR-0062). */
    int nodeTestFailures(TicketId node);

    /** The key of the task a ticket was opened for; empty for every other ticket. */
    Optional<String> workflowStepOf(Ticket ticket);

    Collection<Mission> missions();

    Agent existingAgent(AgentId id);

    Optional<Agent> agent(AgentId id);

    Collection<Agent> agents();

    Ticket existingTicket(TicketId id);

    Optional<Ticket> ticket(TicketId id);

    /** The ticket's submissions and the submission each review was recorded in, derived (ADR-0052). */
    ReviewRounds reviewRounds(TicketId id);

    Collection<Ticket> tickets();

    List<Ticket> tickets(MissionId mission);

    /** The id of the next ticket: after the highest number ever created, read or not. */
    TicketId nextTicketId();

    Action existingAction(ActionId id);

    Optional<Action> action(ActionId id);

    Collection<Action> actions();

    List<Action> actions(MissionId mission);

    int incidentCount();

    Question existingQuestion(QuestionId id);

    Optional<Question> question(QuestionId id);

    Collection<Question> questions();

    List<Question> questions(MissionId mission);

    QuestionId nextQuestionId();

    /** An approval whose answer decides nothing beyond its own action. */
    boolean forgotten(QuestionId id);

    /** An answered approval that still decides later requests in its scope. */
    boolean remembered(QuestionId id);

    Optional<AgentSession> session(String id);

    Collection<AgentSession> sessions();

    List<AgentSession> sessions(MissionId mission);

    Optional<Workspace> workspace(MissionId mission, AgentId agent);

    /** The director edited this file of the agent's workspace: a write on it always asks first. */
    boolean editedByDirector(MissionId mission, AgentId agent, String path);

    List<Intervention> interventions(MissionId mission);

    Optional<Committee> committee(CommitteeRef ref);

    /** The mission's committees, oldest first (ADR-0046). */
    List<Committee> committees(MissionId mission);

    Collection<Committee> committees();

    Optional<Retro> retro(MissionId mission);

    List<Retro> openRetros();

    boolean isStaffingTicket(TicketId ticket);

    int lessonCount();

    /** The director's line for this tool in the company's default table (D3), if set. */
    Optional<Decision> defaultPermission(String tool);

    /** The open meeting this agent sits in, if any (ADR-0047). */
    Optional<Meeting> meetingOf(AgentId agent);

    /** The mission's meetings, open or closed, with their rounds and turns of speech (ADR-0049). */
    List<Meeting> meetingsOf(MissionId mission);

    /** The meeting whose own ticket this is (ADR-0049), if any. */
    Optional<Meeting> meetingOfTicket(TicketId ticket);
}
