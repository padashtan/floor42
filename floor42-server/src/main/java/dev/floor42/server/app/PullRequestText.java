package dev.floor42.server.app;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.CheckRun;
import dev.floor42.domain.Committee;
import dev.floor42.domain.Company;
import dev.floor42.domain.Intervention;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketStatus;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The title and body Floor42 proposes for a pull request (ADR-0036), composed from the journal and from git, with no
 * model: the objective, the tickets done, the reviews' verdicts, the checks Floor42 measured with their dates, the
 * architecture decisions. The body says it was prepared with Floor42 and which parts agents wrote. The director reads
 * it, may change it, and confirms before anything leaves the machine.
 */
public final class PullRequestText {

    public record Text(String title, String body) {}

    static final int MAX_CHECKS = 5;
    static final int MAX_FINDINGS = 240;

    private PullRequestText() {}

    /** A file under an adr, adrs or decisions folder, or named adr then a number (adr-0012-x.md), in linear time. */
    static boolean isDecisionRecord(String file) {
        String[] parts = file.split("/", -1);
        for (int i = 0; i < parts.length - 1; i++) {
            String p = parts[i];
            if (p.equalsIgnoreCase("adr") || p.equalsIgnoreCase("adrs") || p.equalsIgnoreCase("decisions")) return true;
        }
        String last = parts[parts.length - 1].toLowerCase(java.util.Locale.ROOT);
        if (!last.startsWith("adr")) return false;
        int k = 3;
        if (k < last.length() && "-_ ".indexOf(last.charAt(k)) >= 0) k++;
        return k < last.length() && last.charAt(k) >= '0' && last.charAt(k) <= '9';
    }

    /**
     * @param workspace the agent whose branch is proposed; null for the director's own branch
     * @param commits the branch's own commits, newest first (hash, subject)
     * @param changedFiles the files the branch changes, to find the decision records among them
     */
    public static Text compose(
            Company c,
            MissionId id,
            AgentId workspace,
            String branch,
            List<Workspaces.CommitLine> commits,
            List<String> changedFiles) {
        Mission m = c.missions().find(id).orElseThrow();
        List<Ticket> done = c.tickets().of(id).stream()
                .filter(t -> t.status() == TicketStatus.DONE)
                .toList();
        String body = "> Prepared with Floor42 from its journal of mission " + id
                + ". Floor42 composed this description itself, with no model; the parts written by AI agents are"
                + " marked below.\n\n"
                + "## Objective\n\n" + m.objective().strip() + "\n\n"
                + ticketsDone(done)
                + reviews(done)
                + checks(c, id, workspace)
                + decisions(c, id, changedFiles)
                + commits(branch, commits)
                + authors(c, id, workspace, done);
        return new Text(oneLine(m.title()) + " (" + id + ")", body);
    }

    private static String ticketsDone(List<Ticket> done) {
        StringBuilder b = new StringBuilder("## Tickets done\n\n");
        if (done.isEmpty()) b.append("_No ticket of this mission is done._\n");
        for (Ticket t : done) {
            b.append("- **").append(t.id()).append("** ").append(oneLine(t.title()));
            if (t.assignee() != null)
                b.append(" — produced by agent `").append(t.assignee()).append('`');
            if (!t.reviews().isEmpty()) {
                Ticket.Review last = t.reviews().getLast();
                b.append(", last review by agent `")
                        .append(last.reviewer())
                        .append("`: ")
                        .append(last.verdict());
            }
            b.append('\n');
        }
        return b.append('\n').toString();
    }

    private static String reviews(List<Ticket> done) {
        StringBuilder b = new StringBuilder("## Reviews\n\n");
        boolean anyReview = false;
        for (Ticket t : done) {
            int round = 0;
            for (Ticket.Review r : t.reviews()) {
                round++;
                anyReview = true;
                b.append("- ").append(t.id()).append(", review ").append(round);
                b.append(" by agent `")
                        .append(r.reviewer())
                        .append("`: **")
                        .append(r.verdict())
                        .append("**");
                String findings = firstLine(r.findings());
                if (!findings.isEmpty())
                    b.append(" — written by the agent: \"").append(findings).append('"');
                b.append('\n');
            }
        }
        if (!anyReview) b.append("_No review recorded._\n");
        return b.append('\n').toString();
    }

    /** The last checks Floor42 ran on this branch's worktree (every worktree's for the director's branch). */
    private static String checks(Company c, MissionId id, AgentId workspace) {
        StringBuilder b = new StringBuilder("## Checks measured by Floor42\n\n");
        List<CheckRun> runs = c.verification().checkRuns(id).stream()
                .filter(r -> workspace == null || r.workspace().equals(workspace))
                .toList();
        if (runs.isEmpty()) b.append("_Floor42 ran no check on this work._\n");
        for (CheckRun r : runs.subList(Math.max(0, runs.size() - MAX_CHECKS), runs.size())) b.append(check(r));
        if (!runs.isEmpty()) {
            b.append(
                            "\nExit codes and durations were measured by Floor42 on the director's machine, not reported by an")
                    .append(" agent. They do not replace this repository's own CI.\n");
        }
        return b.append('\n').toString();
    }

    private static String check(CheckRun r) {
        String outcome = r.passed()
                ? "passed"
                : r.timedOut()
                        ? "stopped at the time limit"
                        : "failed (exit " + (r.exitCode() == null ? "?" : r.exitCode()) + ")";
        return "- `" + oneLine(r.command()) + "` in `" + r.workspace() + "`'s worktree at `" + shortHash(r.head())
                + "`: **" + outcome + "**, " + String.format(Locale.ROOT, "%.1f", r.durationMs() / 1000.0)
                + " s, on " + r.at() + " (asked by " + r.trigger() + ")\n";
    }

    /** The decision records the branch changes, and the committee's ruling when the director gave one. */
    private static String decisions(Company c, MissionId id, List<String> changedFiles) {
        StringBuilder b = new StringBuilder("## Architecture decisions\n\n");
        List<String> adrs =
                changedFiles.stream().filter(f -> isDecisionRecord(f)).toList();
        Committee committee = c.committees().latest(id).orElse(null);
        boolean ruled = committee != null
                && (committee.stage() == Committee.Stage.ADOPTED || committee.stage() == Committee.Stage.REJECTED);
        if (adrs.isEmpty() && !ruled) b.append("_No decision record on this branch._\n");
        for (String f : adrs) b.append("- `").append(f).append("`\n");
        if (ruled) {
            b.append("- Committee on \"")
                    .append(oneLine(committee.topic()))
                    .append("\": ")
                    .append(committee.stage() == Committee.Stage.ADOPTED ? "adopted" : "rejected")
                    .append(" by the director\n");
        }
        return b.append('\n').toString();
    }

    private static String commits(String branch, List<Workspaces.CommitLine> commits) {
        StringBuilder b = new StringBuilder("## Commits on `").append(branch).append("`\n\n");
        if (commits.isEmpty()) b.append("_No commit of its own._\n");
        for (Workspaces.CommitLine l : commits)
            b.append("- `")
                    .append(l.hash())
                    .append("` ")
                    .append(oneLine(l.subject()))
                    .append('\n');
        return b.append('\n').toString();
    }

    /** Who wrote what: the agents' code and verdicts, the director's own edits and acceptances, Floor42's text. */
    private static String authors(Company c, MissionId id, AgentId workspace, List<Ticket> done) {
        Set<String> producers = new LinkedHashSet<>();
        Set<String> reviewers = new LinkedHashSet<>();
        for (Ticket t : done) {
            if (t.assignee() != null) producers.add(t.assignee().value());
            t.reviews().forEach(r -> reviewers.add(r.reviewer().value()));
        }
        if (workspace != null) producers.add(workspace.value());
        StringBuilder b = new StringBuilder("## Who wrote what\n\n");
        b.append(
                        "- **Written by AI agents** (Claude Code sessions run by Floor42): the code changes of this branch, by ")
                .append(producers.isEmpty() ? "no agent recorded" : names(producers));
        if (!reviewers.isEmpty()) {
            b.append("; the review verdicts and the findings quoted above, by ").append(names(reviewers));
        }
        b.append(".\n");
        b.append(director(c, id, workspace));
        b.append("- **Composed by Floor42**, with no model: this description, from the journal and from git.\n");
        return b.toString();
    }

    /** The director's own part, from the workshop's trace of this branch's worktree (every worktree's for theirs). */
    private static String director(Company c, MissionId id, AgentId workspace) {
        List<String> edited = new ArrayList<>();
        List<String> accepted = new ArrayList<>();
        for (Intervention i : c.workshop().interventions(id)) {
            if (workspace != null && i.workspace() != null && !i.workspace().equals(workspace)) continue;
            if (i.kind() == Intervention.Kind.DIRECTOR_EDIT && !edited.contains(i.subject())) edited.add(i.subject());
            if (i.kind() == Intervention.Kind.PATCH_ACCEPTED && i.detail() != null) accepted.add(shortHash(i.detail()));
        }
        return "- **The director's own**: "
                + (edited.isEmpty()
                        ? "no file edited by hand in the workshop"
                        : "files edited by hand in the workshop: " + String.join(", ", edited))
                + "; "
                + (accepted.isEmpty()
                        ? "no commit accepted in the workshop"
                        : "every commit accepted in the workshop (" + String.join(", ", accepted) + ")")
                + "; the decision to open this pull request.\n";
    }

    private static String names(Set<String> agents) {
        return String.join(", ", agents.stream().map(a -> "`" + a + "`").toList());
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        String line = text.strip()
                .lines()
                .filter(l -> !l.isBlank())
                .findFirst()
                .orElse("")
                .strip();
        line = line.replace("\"", "'");
        return line.length() <= MAX_FINDINGS ? line : line.substring(0, MAX_FINDINGS) + "…";
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }

    private static String shortHash(String hash) {
        return hash == null ? "?" : hash.substring(0, Math.min(8, hash.length()));
    }
}
