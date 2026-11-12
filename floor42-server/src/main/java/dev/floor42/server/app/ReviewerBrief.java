package dev.floor42.server.app;

import static dev.floor42.server.app.BriefSections.clip;

import dev.floor42.domain.Agent;
import dev.floor42.domain.Company;
import dev.floor42.domain.ConsultationKind;
import dev.floor42.domain.Mission;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.WorkRole;
import java.util.stream.Collectors;

/**
 * The independent reviewer's brief: the deliverable, the checks Floor42 measured on it, and the help it may ask for
 * once per review (ADR-0018).
 */
final class ReviewerBrief {

    /** The reviewer ends with this marker; anything else is treated as unverifiable and escalated. */
    static final String VERDICT_MARKER = "VERDICT:";

    private ReviewerBrief() {}

    static String of(Company c, Mission m, Ticket t, Agent agent) {
        return BriefTemplate.named("reviewer")
                .with("name", agent.displayName())
                .with("role", agent.role())
                .with("mission", m.id())
                .with("title", m.title())
                .with("objective", m.objective())
                .with("ticket", t.id())
                .with("ticketTitle", t.title())
                .with("producer", t.assignee())
                .with("criteria", numbered(t.acceptanceCriteria()))
                .with("deliverable", t.deliverable())
                .with("meeting", BriefSections.meeting(c, m, agent))
                .with("workshop", BriefSections.workshop(c, m, t))
                .with("directorFiles", BriefSections.directorFiles(c, m))
                .with("checks", BriefSections.checks(c, m, t))
                .with("environment", BriefEnvironment.of(c))
                .with("permissions", BriefPermissions.permissions(c, m, agent, t.id()))
                .with("lessons", BriefSections.lessons(c, WorkRole.REVIEWER))
                .with("help", help(t))
                .with("marker", VERDICT_MARKER)
                .render();
    }

    /** The criteria as C1, C2...: a reservation cites one by this label, or Floor42 records it as a note (ADR-0053). */
    static String numbered(java.util.List<String> criteria) {
        if (criteria.isEmpty()) return "- (none stated: anything you would reserve is recorded as a note)";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < criteria.size(); i++) {
            if (i > 0) out.append('\n');
            out.append("- C").append(i + 1).append(": ").append(criteria.get(i));
        }
        return out.toString();
    }

    /** What the reviewer may ask for, or the help it already got in this review round. */
    private static String help(Ticket t) {
        return t.consultationThisRound().map(ReviewerBrief::received).orElseGet(ReviewerBrief::offer);
    }

    private static String received(Ticket.Consultation h) {
        return "You asked for help during this review:\n"
                + (h.kind() == ConsultationKind.CONSULT ? "CONSULT " + h.role() : "RESEARCH")
                + ": " + h.question() + "\nAnswer"
                + (h.answeredBy() == null ? " from Floor42" : " from " + h.answeredBy())
                + ":\n---\n" + clip(h.answer() == null ? "(none)" : h.answer(), 6_000)
                + "\n---\nWeigh it as advice, check what you can yourself, and give your verdict now: you"
                + " cannot ask again in this review.\n";
    }

    private static String offer() {
        String roles =
                CommitteeRoles.all().stream().map(CommitteeRoles.Role::key).collect(Collectors.joining("|"));
        return BriefTemplate.named("reviewer-help-offer").with("roles", roles).render();
    }
}
