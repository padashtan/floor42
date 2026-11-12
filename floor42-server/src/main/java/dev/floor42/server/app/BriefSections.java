package dev.floor42.server.app;

import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.CheckRun;
import dev.floor42.domain.Committee;
import dev.floor42.domain.Company;
import dev.floor42.domain.Lesson;
import dev.floor42.domain.Mission;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.Setting;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.WorkRole;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The fragments several briefs share, each restated from persisted facts: the director's files, decisions and
 * interventions, the checks Floor42 measured, previous reviews, accepted lessons. An empty fragment is the empty
 * string, so a brief reads the same whether or not it has one.
 */
final class BriefSections {

    /** Most lessons a brief carries: the latest accepted ones for the role. */
    static final int MAX_LESSONS_IN_BRIEF = 8;

    /** Most director files named in a brief; the rest are counted. */
    private static final int MAX_DIRECTOR_FILES = 40;

    private static final int MAX_CHECK_OUTPUT = 4_000;

    private BriefSections() {}

    static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n… (cut)";
    }

    static String bullets(List<String> items) {
        return items.isEmpty()
                ? "- (none stated)"
                : items.stream().map(i -> "- " + i).collect(Collectors.joining("\n"));
    }

    /**
     * The files the director gave the mission, so the agent knows to read them: they are in its working directory
     * once the director brought them to the agents, or from the start for an agent whose worktree came after.
     */
    static String directorFiles(Company c, Mission m) {
        List<String> files = c.workshop().directorFiles(m.id());
        if (files.isEmpty()) return "";
        StringBuilder s = new StringBuilder(
                "\nFiles the director gave for this mission (paths from the root of your working directory; read the"
                        + " ones that matter to your task, PDFs included):\n");
        files.stream()
                .limit(MAX_DIRECTOR_FILES)
                .forEach(f -> s.append("- ").append(f).append("\n"));
        if (files.size() > MAX_DIRECTOR_FILES)
            s.append("- … and ").append(files.size() - MAX_DIRECTOR_FILES).append(" more\n");
        return s.toString();
    }

    /** The director's files, then the director's answers so far. */
    static String decisions(Company c, Mission m) {
        String answered = c.desk().of(m.id()).stream()
                .filter(q -> q.status() == QuestionStatus.ANSWERED)
                .map(q -> "- " + q.id() + " \"" + q.subject() + "\": " + q.answer())
                .collect(Collectors.joining("\n"));
        return directorFiles(c, m) + (answered.isEmpty() ? "" : "\nDirector decisions so far:\n" + answered + "\n");
    }

    /** Most meeting notes a brief restates: the latest ones. */
    static final int MAX_MEETING_NOTES = 20;

    /**
     * What the director told this agent, or every attendee, at the mission's meetings it attended (ADR-0047), oldest
     * first; the empty string when nothing was said.
     */
    static String meeting(Company c, Mission m, Agent agent) {
        List<dev.floor42.domain.MeetingNote> notes = c.meetings().notesFor(m.id(), agent.id());
        if (notes.isEmpty()) return "";
        String lines = notes.stream()
                .skip(Math.max(0, notes.size() - MAX_MEETING_NOTES))
                .map(n -> "- " + n.meeting() + (n.attendee() == null ? ", to everyone: " : ", to you: ") + n.text())
                .collect(Collectors.joining("\n"));
        return BriefTemplate.named("meeting").with("notes", lines).render();
    }

    /** The director's interventions from the workshop: they take precedence over the agent's own ideas. */
    static String workshop(Company c, Mission m, Ticket t) {
        StringBuilder s = new StringBuilder();
        if (!t.directives().isEmpty()) {
            s.append("\nDirectives from the director (they take precedence; address every one):\n");
            t.directives().forEach(d -> s.append("- ").append(d).append("\n"));
        }
        List<String> edited = t.assignee() == null
                ? List.of()
                : c.workshop()
                        .workspace(m.id(), t.assignee())
                        .map(w -> w.editedByDirector().stream().sorted().toList())
                        .orElse(List.of());
        if (!edited.isEmpty()) {
            s.append("\nFiles the director edited by hand (keep their changes; Floor42 asks the director before any")
                    .append(" write to them):\n");
            edited.forEach(p -> s.append("- ").append(p).append("\n"));
        }
        return s.toString();
    }

    /** Dependencies another developer did: their changes are on that developer's branch, not in this worktree (D10). */
    static String elsewhere(Company c, Ticket t, Agent agent) {
        List<String> lines = t.dependsOn().stream()
                .map(d -> c.tickets().find(d).orElse(null))
                .filter(d -> d != null && byAnotherDeveloper(c, d, agent))
                .map(d -> d.id() + " (" + d.title() + ") was done by " + d.assignee()
                        + c.workshop()
                                .workspace(t.mission(), d.assignee())
                                .map(w -> " on branch " + w.branch())
                                .orElse("")
                        + ": its changes are not in your worktree.")
                .toList();
        if (lines.isEmpty()) return "";
        return "Work this ticket builds on that is not here:\n" + bullets(lines)
                + "\nDo not assume it is present: say what is missing and what you did without it.\n\n";
    }

    private static boolean byAnotherDeveloper(Company c, Ticket d, Agent agent) {
        return d.assignee() != null
                && !d.assignee().equals(agent.id())
                && c.staff()
                        .find(d.assignee())
                        .map(a -> Staff.roleOf(a) == WorkRole.DEVELOPER)
                        .orElse(false);
    }

    /** For the implementer: the decision the director adopted after the committee. */
    static String adoptedDecision(Company c, Mission m) {
        return c.committees()
                .latest(m.id())
                .filter(x -> x.stage() == Committee.Stage.ADOPTED && x.synthesis() != null)
                .map(x -> "\nDecision adopted by the director after the architecture committee (follow it):\n---\n"
                        + clip(x.synthesis().text(), 8_000) + "\n---\n")
                .orElse("");
    }

    static String constraints(Committee committee) {
        if (committee.constraints().isEmpty()) return "";
        StringBuilder s = new StringBuilder("Constraints added by the director (they bind every proposal):\n");
        committee.constraints().forEach(x -> s.append("- ").append(x).append("\n"));
        return s.append("\n").toString();
    }

    static String reviews(Ticket t) {
        if (t.reviews().isEmpty()) return "";
        return "\nPrevious reviews of this ticket (address them):\n"
                + t.reviews().stream()
                        .map(r -> "- " + r.reviewer() + ": " + r.verdict() + ". " + r.findings())
                        .collect(Collectors.joining("\n"))
                + "\n";
    }

    /** Lessons of earlier retrospectives the director accepted for this role (ADR-0020). */
    static String lessons(Company c, WorkRole role) {
        List<Lesson> all = c.retros().lessonsFor(role);
        if (all.isEmpty()) return "";
        List<Lesson> latest = all.subList(Math.max(0, all.size() - MAX_LESSONS_IN_BRIEF), all.size());
        return "\nLessons from earlier missions, accepted by the director (apply them unless this mission says"
                + " otherwise):\n"
                + latest.stream()
                        .map(l -> "- " + l.text() + " (" + l.id() + ", from " + l.mission() + ")")
                        .collect(Collectors.joining("\n"))
                + "\n";
    }

    /** The director's own description of how they think, or what Floor42 knows of them until they write it. */
    static String directorProfile(Company c) {
        return c.preferences().setting(Setting.DIRECTOR_PROFILE).orElse(OfficeSettings.DIRECTOR_PROFILE);
    }

    /** The last checks Floor42 ran on the producer's workspace: measured evidence, not the producer's word. */
    static String checks(Company c, Mission m, Ticket t) {
        if (m.checkCommand() == null) return "";
        CheckRun run = c.verification().checkRuns(m.id()).stream()
                .filter(r -> r.workspace().equals(t.assignee()))
                .reduce((first, last) -> last)
                .orElse(null);
        if (run == null) return "\nThe director's checks (" + m.checkCommand() + ") have not run on this work yet.\n";
        // Checks run before the producer's last session say nothing about what it delivered since: the worktree's
        // HEAD stays the same while agents do not commit, so the run is dated against the delivery.
        Instant delivered = delivered(c, m, t);
        if (delivered != null && run.at().isBefore(delivered)) {
            return "\nThe director's checks (" + m.checkCommand() + ") have not run on this delivery: their last run ("
                    + run.at() + ") came before it and says nothing about it.\n";
        }
        return "\nChecks run by Floor42 on this work (measured, not reported by the producer): `" + run.command() + "` "
                + (run.timedOut() ? "timed out" : "exited with " + run.exitCode())
                + " after " + run.durationMs() / 1000.0 + " s. Output (end):\n---\n" + tail(run.output()) + "\n---\n";
    }

    /** When the producer's last session on the ticket ended; null while none has. */
    private static Instant delivered(Company c, Mission m, Ticket t) {
        return c.sessions().of(m.id()).stream()
                .filter(s -> s.ticket().equals(t.id()) && s.agent().equals(t.assignee()) && s.endedAt() != null)
                .map(AgentSession::endedAt)
                .max(Instant::compareTo)
                .orElse(null);
    }

    private static String tail(String output) {
        return output.length() > MAX_CHECK_OUTPUT ? "…" + output.substring(output.length() - MAX_CHECK_OUTPUT) : output;
    }
}
