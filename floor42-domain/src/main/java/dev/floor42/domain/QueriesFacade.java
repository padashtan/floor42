package dev.floor42.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What the company answers from several areas at once: what an agent is doing, what its dismissal would hand back, the
 * commit of a ticket's work, when a mission last moved. Reached by {@link Company#queries()}.
 */
public final class QueriesFacade {

    private final CompanyQueries queries;
    private final CompanyCore core;

    QueriesFacade(CompanyQueries queries, CompanyCore core) {
        this.queries = queries;
        this.core = core;
    }

    /**
     * What the agent is doing now, derived from facts. Only active missions count, so an agent never appears busy on a
     * finished or cancelled mission.
     */
    public AgentStatus statusOf(AgentId id) {
        return queries.statusOf(id);
    }

    /** True while the agent runs a session: the one time it cannot leave. */
    public boolean atWork(AgentId id) {
        return queries.atWork(id);
    }

    /** The tickets a dismissal of this agent would give back to the queue: those it had started, missions still open. */
    public List<TicketId> handedBackOnDismissal(AgentId id) {
        return queries.handedBackOnDismissal(id);
    }

    /**
     * The commit the director accepted in the workshop for a ticket's work: the latest one on its developer's
     * workspace of that mission, accepted after the ticket started. Null when the work changed no file.
     */
    public String commitFor(TicketId id) {
        return queries.commitFor(id);
    }

    /** When anything last happened to the mission (its latest event); empty for an unknown mission. */
    public Optional<Instant> lastActivity(MissionId mission) {
        return core.lastActivity(mission);
    }
}
