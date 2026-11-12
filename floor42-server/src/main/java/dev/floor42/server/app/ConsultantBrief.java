package dev.floor42.server.app;

import dev.floor42.domain.Agent;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.Ticket;

/** The help a reviewer asked for (ADR-0018): a peer reading the worktree, or a web research that never sees it. */
final class ConsultantBrief {

    private ConsultantBrief() {}

    /** A peer asked for advice by a reviewer: reads the producer's worktree, changes nothing, advises. */
    static String consultant(Company c, Mission m, Ticket t, Agent agent, Ticket.Consultation h) {
        return BriefTemplate.named("consultant")
                        .with("name", agent.displayName())
                        .with("role", agent.role())
                        .with("reviewer", h.reviewer())
                        .with("ticket", t.id())
                        .with("mission", m.id())
                        .with("title", m.title())
                        .with("objective", m.objective())
                        .with("ticketTitle", t.title())
                        .with("producer", t.assignee())
                        .with("deliverable", BriefSections.clip(t.deliverable() == null ? "" : t.deliverable(), 8_000))
                        .with("question", h.question())
                        .render()
                + BriefSections.directorFiles(c, m);
    }

    /** A web research for a reviewer, by a session that has no access to the repository: it only gets the question. */
    static String researcher(Agent agent, Ticket.Consultation h) {
        return BriefTemplate.named("researcher")
                .with("name", agent.displayName())
                .with("role", agent.role())
                .with("question", h.question())
                .render();
    }
}
