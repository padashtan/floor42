package dev.floor42.server.app;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommitteeRef;
import dev.floor42.domain.TicketId;
import java.util.ArrayList;
import java.util.List;

/**
 * One unit of work started by the dispatcher. {@code agent} runs it (for a consultation, the reviewer who asked);
 * {@code helper} is the consultant or researcher who answers, or null when Floor42 answers itself. {@code others} are
 * every other agent whose session the task runs (committee members); all of them are reserved. {@code committee} is the
 * committee a {@link Work#COMMITTEE} task runs, as the workflow interpreter named it (ADR-0046); null otherwise.
 */
record Task(Work work, TicketId ticket, AgentId agent, AgentId helper, List<AgentId> others, CommitteeRef committee) {
    Task(Work work, TicketId ticket, AgentId agent) {
        this(work, ticket, agent, null, List.of(), null);
    }

    Task(Work work, TicketId ticket, AgentId agent, AgentId helper, List<AgentId> others) {
        this(work, ticket, agent, helper, others, null);
    }

    /** The sessions of this committee: its chair runs them, every member and the analyst are reserved. */
    static Task committee(CommitteeRef committee, TicketId ticket, AgentId chair, List<AgentId> members) {
        return new Task(Work.COMMITTEE, ticket, chair, null, members, committee);
    }

    List<AgentId> reserved() {
        List<AgentId> all = new ArrayList<>(others);
        all.add(agent);
        if (helper != null) all.add(helper);
        return all;
    }
}
