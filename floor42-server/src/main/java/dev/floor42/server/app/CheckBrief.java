package dev.floor42.server.app;

import dev.floor42.domain.Agent;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.ReviewRounds;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.workflow.StepAt;
import dev.floor42.domain.workflow.Workflow.Step;
import java.util.ArrayList;
import java.util.List;

/**
 * The brief of one of the director's checks (ADR-0052): the step's texts quoted as data under a line saying they change
 * nothing, the deliverable, and the earlier verdicts of this submission. The session's tools and rules are a reviewer's
 * on the mission ({@link ReviewRunner}), whatever the text says: no key of the step maps to a tool, a rule or a scope.
 */
final class CheckBrief {

    /** The most of an earlier verdict quoted: the check reads the deliverable itself. */
    static final int EARLIER_CHARS = 1_500;

    private CheckBrief() {}

    static String of(Company c, Mission m, Ticket t, Agent agent, Step.Check check, StepAt at) {
        return BriefTemplate.named("check")
                .with("name", agent.displayName())
                .with("role", agent.role())
                .with("mission", m.id())
                .with("title", m.title())
                .with("objective", m.objective())
                .with("ticket", t.id())
                .with("ticketTitle", t.title())
                .with("producer", t.assignee())
                .with("stepTitle", check.title())
                .with("does", check.does())
                .with("why", check.why())
                .with("handsOver", check.handsOver())
                .with("deliverable", t.deliverable())
                .with("earlier", earlier(t, c.tickets().reviewRounds(t.id()), at))
                .with("environment", BriefEnvironment.of(c))
                .with("permissions", BriefPermissions.permissions(c, m, agent, t.id()))
                .with(
                        "blocking",
                        check.blocks()
                                ? "A verdict that does not validate sends the ticket back to its developer."
                                : "This step is advisory: your findings go to the developer and to the director, and"
                                        + " send nothing back.")
                .with("marker", ReviewerBrief.VERDICT_MARKER)
                .render();
    }

    /** The verdicts given in this submission before this step: the review's, then the earlier checks'. */
    static String earlier(Ticket t, ReviewRounds rounds, StepAt at) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < t.reviews().size() && i < rounds.reviewedIn().size(); i++) {
            if (rounds.reviewedIn().get(i) != at.submission()) continue;
            Ticket.Review r = t.reviews().get(i);
            lines.add(
                    "- " + r.reviewer() + " (" + r.verdict() + "): " + BriefSections.clip(r.findings(), EARLIER_CHARS));
        }
        return lines.isEmpty() ? "" : "\nEarlier verdicts on this submission:\n" + String.join("\n", lines) + "\n";
    }
}
