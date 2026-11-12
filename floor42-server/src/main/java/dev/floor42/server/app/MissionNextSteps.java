package dev.floor42.server.app;

import static dev.floor42.server.app.MissionReports.MAX_LINE;
import static dev.floor42.server.app.MissionReports.clip;

import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Company;
import dev.floor42.domain.Intervention;
import dev.floor42.domain.Mission;
import dev.floor42.domain.Origin;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.app.MissionReports.FollowUp;
import dev.floor42.server.app.MissionReports.NextStep;
import dev.floor42.server.app.MissionReports.ReportOutput;
import dev.floor42.server.app.MissionReports.ReportTicket;
import dev.floor42.server.app.MissionReports.ReportWarning;
import java.util.List;
import java.util.Set;

/**
 * What the director can do once a mission's work is over (ADR-0039), each step with the refusal it would meet now,
 * asked of the domain as a query; and the follow-up mission Floor42 proposes from what was left open.
 */
final class MissionNextSteps {

    /** A next step's kind, in the order the report offers them. */
    enum Step {
        CLOSE,
        REOPEN,
        PULL_REQUEST,
        MERGE,
        FOLLOW_UP,
        ARCHIVE
    }

    private static final Set<String> CLOSED = Set.of(TicketStatus.DONE.name(), TicketStatus.CANCELLED.name());

    private MissionNextSteps() {}

    static List<NextStep> next(
            Company c, Mission m, CommandContext director, List<ReportTicket> tickets, List<ReportOutput> outputs) {
        return List.of(
                close(c, m, director),
                reopen(tickets),
                pullRequest(c, m),
                merge(outputs),
                m.workspace() == null || m.workspace().isBlank()
                        ? refused(Step.FOLLOW_UP, "NO_REPOSITORY", m.id() + " works on no repository")
                        : available(Step.FOLLOW_UP),
                available(Step.ARCHIVE));
    }

    private static NextStep available(Step step) {
        return new NextStep(step.name(), true, null, null);
    }

    private static NextStep refused(Step step, String code, String message) {
        return new NextStep(step.name(), false, code, message);
    }

    private static NextStep close(Company c, Mission m, CommandContext director) {
        return c.missions()
                .refusalToComplete(director, m.id())
                .map(r -> refused(Step.CLOSE, r.code(), r.message()))
                .orElse(available(Step.CLOSE));
    }

    /** Available when a directive can go to some ticket; else the first ticket's refusal. */
    private static NextStep reopen(List<ReportTicket> tickets) {
        if (tickets.stream().anyMatch(t -> t.reopenTo() != null)) return available(Step.REOPEN);
        return tickets.stream()
                .filter(t -> t.reopenRefusedCode() != null)
                .findFirst()
                .map(t -> refused(Step.REOPEN, t.reopenRefusedCode(), t.reopenRefused()))
                .orElse(refused(Step.REOPEN, "NO_TICKET", "The mission has no ticket to reopen"));
    }

    private static NextStep pullRequest(Company c, Mission m) {
        if (m.origin() != Origin.REAL)
            return refused(Step.PULL_REQUEST, "SIMULATED_MISSION", "A simulated mission has no branch");
        boolean accepted =
                c.workshop().interventions(m.id()).stream().anyMatch(i -> i.kind() == Intervention.Kind.PATCH_ACCEPTED);
        return accepted
                ? available(Step.PULL_REQUEST)
                : refused(
                        Step.PULL_REQUEST,
                        "NOTHING_ACCEPTED",
                        "No work of this mission was accepted in the workshop: a pull request carries accepted commits"
                                + " only");
    }

    private static NextStep merge(List<ReportOutput> outputs) {
        return outputs.stream().anyMatch(o -> o.mergeCommand() != null)
                ? available(Step.MERGE)
                : refused(
                        Step.MERGE,
                        "NOTHING_ACCEPTED",
                        "No branch of this mission holds commits beyond the project's: accept work in the workshop"
                                + " first");
    }

    /** The follow-up mission's text: the objective, then what the report found open, line by line. */
    static FollowUp followUp(Mission m, List<ReportTicket> tickets, List<ReportWarning> warnings) {
        StringBuilder b = new StringBuilder();
        b.append("Follow-up of ").append(m.id()).append(" \"").append(m.title()).append("\".\n\n");
        b.append("Objective of ")
                .append(m.id())
                .append(":\n")
                .append(clip(m.objective(), 1500))
                .append("\n\n");
        b.append("What ").append(m.id()).append(" left open (Floor42's report):\n");
        List<String> open = tickets.stream()
                .filter(t -> !MissionReportComposer.Kind.RETRO.name().equals(t.kind()) && !CLOSED.contains(t.status()))
                .map(t -> t.id() + " " + t.title() + ": " + t.status())
                .toList();
        List<String> found = warnings.stream()
                .map(MissionNextSteps::openLine)
                .filter(line -> line != null)
                .map(line -> clip(line, MAX_LINE))
                .toList();
        open.forEach(line -> b.append("- ").append(line).append('\n'));
        found.forEach(line -> b.append("- ").append(line).append('\n'));
        if (open.isEmpty() && found.isEmpty()) b.append("- nothing recorded as open\n");
        return new FollowUp(clip("Follow-up: " + m.title(), 120), b.toString().strip(), m.workspace());
    }

    /** What a warning leaves open, in a line; null for one that leaves nothing to do. */
    private static String openLine(ReportWarning w) {
        return switch (w.code()) {
            case "PRODUCER_PARTIAL" -> w.ticket() + ": the producer reported " + w.text();
            case "REVIEW_VALIDATED_WITH_RESERVATIONS", "REVIEW_UNVERIFIABLE", "REVIEW_REJECTED" ->
                w.ticket() + ": review " + w.code().substring("REVIEW_".length()) + " (" + w.text() + ")";
            case "OUTPUTS_IGNORED" -> w.count() + " file(s) ignored by git in " + w.path() + " (" + w.text() + ")";
            case "OUTPUTS_NOT_COMMITTED" -> w.count() + " file(s) not committed in " + w.path() + " (" + w.text() + ")";
            case "CHECKS_FAILED" -> "the last check failed: " + w.text();
            default -> null;
        };
    }
}
