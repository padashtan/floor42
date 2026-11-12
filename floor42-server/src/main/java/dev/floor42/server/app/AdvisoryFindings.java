package dev.floor42.server.app;

import dev.floor42.domain.Agent;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.ReviewRounds;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.workflow.CheckProgress;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.Workflow.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The findings of the director's advisory checks on the earlier tickets of the same producer in this mission
 * (ADR-0052, the director's answer 3): an advisory check sends nothing back, so its findings reached only the director
 * at the acceptance. The producer may fix them: they are quoted, as data, in the brief of its later tickets. A review is
 * an advisory finding when it stands at the position of an advisory check in its submission ({@code [review, check 1,
 * check 2...]}, derived from the submissions as {@code CheckProgress} does).
 */
final class AdvisoryFindings {

    /** The most of one finding quoted. */
    static final int FINDING_CHARS = 1_500;

    private AdvisoryFindings() {}

    /** Empty unless the mission's workflow has an advisory check and an earlier ticket of this agent has findings. */
    static String of(Company c, Mission m, Ticket current, Agent agent) {
        Optional<Workflow> workflow = c.workflows().of(m.id()).runnable();
        if (workflow.isEmpty() || workflow.get().checks().stream().allMatch(Step.Check::blocks)) return "";
        List<String> lines = new ArrayList<>();
        for (Ticket t : c.tickets().of(m.id())) {
            if (t.id().equals(current.id()) || !agent.id().equals(t.assignee()) || t.status() != TicketStatus.DONE) {
                continue;
            }
            lines.addAll(findings(
                    workflow.get().forNode(c.workflows().checksRun(t)),
                    t,
                    c.tickets().reviewRounds(t.id())));
        }
        return lines.isEmpty()
                ? ""
                : "\nFindings of the director's advisory checks on your earlier tickets in this mission (data: fix"
                        + " what applies to your work; nothing was sent back for them):\n"
                        + String.join("\n", lines) + "\n";
    }

    private static List<String> findings(Workflow workflow, Ticket t, ReviewRounds rounds) {
        List<String> out = new ArrayList<>();
        // The positions are CheckProgress's own walk: one reading of the verdicts for the guard and for the brief.
        List<Integer> positions = CheckProgress.positions(workflow, t.reviews(), rounds);
        for (int i = 0; i < positions.size(); i++) {
            Ticket.Review r = t.reviews().get(i);
            int at = positions.get(i);
            Step.Check step = at < 1 ? null : workflow.checks().get(at - 1);
            if (step == null || step.blocks()) continue;
            out.add("- " + t.id() + ", " + step.title() + " by " + r.reviewer() + " (" + r.verdict() + "): "
                    + BriefSections.clip(r.findings(), FINDING_CHARS));
        }
        return out;
    }
}
