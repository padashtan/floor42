package dev.floor42.server.app;

import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.OptionCode;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.WorkRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Real missions, run by live Claude Code sessions, as the rest of the daemon sees them:
 *
 * <ol>
 *   <li>the director creates the mission on a repository; Floor42 proposes a plan (one investigation ticket, a
 *       conservative permission table) and submits it ({@link MissionFactory});
 *   <li>when the director approves the plan, the producing agent works in its own worktree under the mission policy
 *       ({@link Dispatcher}, one {@link TaskRunner} per kind of work);
 *   <li>its report goes to an independent review by another agent, in another session, with read-only tools;
 *   <li>a rejection sends the ticket back once with the findings; anything unclear is escalated to the director.
 * </ol>
 *
 * Reactions to the journal live in {@link EventReactions}, the start and the recovery after a restart in
 * {@link LiveStartup}.
 */
@ApplicationScoped
public class LiveMissions {

    /** Labels of Floor42's own options, as shown; the code compares their {@link OptionCode}, never the label. */
    public static final String RETRY = OptionCode.RETRY.label();

    private final Dispatcher dispatcher;
    private final MissionFactory factory;
    private final StallDiagnosis stalls;
    private final HeldWork heldWork;
    private final AgentReservations reservations;

    @Inject
    LiveMissions(
            Dispatcher dispatcher,
            MissionFactory factory,
            StallDiagnosis stalls,
            HeldWork heldWork,
            AgentReservations reservations) {
        this.dispatcher = dispatcher;
        this.factory = factory;
        this.stalls = stalls;
        this.heldWork = heldWork;
        this.reservations = reservations;
    }

    /** What Floor42 gives this agent to do, from its work role (ADR-0019): shown on its profile. */
    public static List<String> duties(Agent agent) {
        List<String> d = new ArrayList<>();
        WorkRole role = Staff.roleOf(agent);
        if (role != null) {
            switch (role) {
                case PLANNER -> d.add("Plans a mission into tickets you approve (one read-only session)");
                case DEVELOPER -> d.add("Works approved tickets, one at a time, in its own git worktree");
                case REVIEWER ->
                    d.add("Reviews deliverables it did not produce, reading only; may consult a colleague or ask for"
                            + " web research");
                case RESEARCHER -> d.add("Researches the web for a reviewer, without access to the repository");
            }
        }
        if (CommitteeRoles.of(agent.id()) != null) d.add("Sits on committees (read only)");
        return d;
    }

    /** With committee roles, the mission is a decision, by the architecture committee. */
    public MissionId create(String title, String objective, String repository, List<String> committee, boolean plan) {
        return create(title, objective, repository, committee, plan, CommitteeRoles.ARCHITECTURE, null, null);
    }

    /**
     * @param panel {@code architecture}, or {@code idea} for a committee convened before a new project
     * @param workflow a company workflow's name, pinned to the mission; null for the built-in one (ADR-0050)
     * @param deliverTo the folder for the accepted deliverables (ADR-0051); null for none
     */
    public MissionId create(
            String title,
            String objective,
            String repository,
            List<String> committee,
            boolean plan,
            String panel,
            String workflow,
            String deliverTo) {
        return factory.create(title, objective, repository, committee, plan, panel, workflow, deliverTo);
    }

    /** As above; {@code graph} pins the workflow in graph mode (ADR-0062). */
    public MissionId create(
            String title,
            String objective,
            String repository,
            List<String> committee,
            boolean plan,
            String panel,
            String workflow,
            String deliverTo,
            boolean graph) {
        return factory.create(title, objective, repository, committee, plan, panel, workflow, deliverTo, graph);
    }

    /** The director asks Floor42 to look at a mission again: what can run starts, from what the journal says. */
    public void nudge(MissionId m) {
        dispatcher.nudge(m);
    }

    /** Keycloak is back: every real mission in planning or in progress looks again, as after {@link #nudge}. */
    void onIamRecovered(@jakarta.enterprise.event.Observes dev.floor42.server.iam.IamRecovered ignored) {
        dispatcher.identityBack();
    }

    /** The staff changed: every real mission in progress looks again. */
    public void staffChanged() {
        dispatcher.staffChanged();
    }

    /**
     * Test seam (package-private, {@code StallTest}): starts what can run on the mission now, on the caller's thread,
     * to race the dispatcher against a reservation. Production goes through {@link #nudge} and the event reactions.
     */
    void produce(MissionId m) {
        dispatcher.produce(m);
    }

    /** Whether the mission is stuck, by the dispatcher's own rule. Read only: nothing starts here. */
    public Stall stall(MissionId m, Instant now) {
        return stalls.stall(m, now);
    }

    /** The developers whose worktree holds finished work the director has not accepted or rejected yet. */
    public Map<AgentId, TicketId> held(MissionId m) {
        return heldWork.held(m);
    }

    /**
     * Test seam (package-private, {@code StallTest}, and {@code LiveSeams} for the API tests): reserves an agent the way
     * a task outside the dispatcher does, empty while it runs a session elsewhere. Production reserves through
     * {@link #await}.
     */
    Optional<AgentReservations.Reservation> reserve(AgentId agent) {
        return reservations.reserve(agent);
    }

    /** Waits until the agent can be reserved, at most {@code patience}. */
    public AgentReservations.Reservation await(AgentId agent, Duration patience) throws InterruptedException {
        return reservations.await(agent, patience);
    }

    /** Ends a reservation, only by its holder; missions waiting for this agent look again. */
    public void release(AgentReservations.Reservation reservation) {
        if (reservation != null && reservations.release(reservation)) dispatcher.staffChanged();
    }

    /** How long work outside the dispatcher (a retrospective turn, a staffing review) waits for a busy agent. */
    public Duration reservationPatience() {
        return reservations.patience();
    }

    /** True while a task of this agent is chosen or running: it cannot be dismissed in the middle. */
    public boolean busy(AgentId agent) {
        return reservations.busy(agent);
    }
}
