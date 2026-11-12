package dev.floor42.server.app;

import dev.floor42.domain.Company;
import dev.floor42.domain.EnvironmentBlocker;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Workspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The end-of-mission report (ADR-0039): what was done per ticket, what it cost and took with the basis of each figure,
 * the reviews and checks, the retrospective's lessons, what each worktree still holds outside any commit, and the next
 * steps with the refusal each would meet. Composed from the journal and git on each call; nothing is recorded.
 */
@ApplicationScoped
public class MissionReports {

    /** Floor42 measured it (its clock, its check runs, git read now). */
    public static final String MEASURED = "MEASURED";
    /** Claude Code's estimate (cost from tokens and list prices): not a bill. */
    public static final String ESTIMATED = "ESTIMATED";
    /** An agent's own words (a verdict, a finding). */
    public static final String REPORTED = "REPORTED";
    /** A simulated mission called no model: its cost is 0, whatever the recording it replays once cost. */
    public static final String SIMULATED = "SIMULATED";
    /**
     * At most this much, neither an estimate nor a measure: the cap of a session that reported no cost, or the budget of
     * a meeting turn charged for want of an estimate (ADR-0049).
     */
    public static final String UPPER_BOUND = "UPPER_BOUND";

    /** At most this many paths named per list; all are counted. */
    static final int MAX_PATHS = 50;

    static final int MAX_LINE = 240;

    private static final Pattern VERDICT_LINE = Pattern.compile("(?im)^\\W{0,4}VERDICT\\W{0,4}:\\s*(.+?)\\s*$");

    /**
     * A figure and how certain it is: {@link #MEASURED}, {@link #ESTIMATED}, {@link #REPORTED}, {@link #SIMULATED} or
     * {@link #UPPER_BOUND}; value null when none.
     */
    public record Measure(Double value, String basis) {}

    /**
     * The sessions that ended without reporting a cost: {@code costUsd} is their caps' sum, {@link #UPPER_BOUND} (null
     * when there is none), shown apart from the estimate and never added to it. The mission's total counts the meeting
     * turns charged for want of an estimate too, each once (Verification).
     */
    public record CostBound(Measure costUsd, int sessions) {}

    public record ReportReview(String reviewer, String verdict, String firstLine) {}

    /**
     * @param kind WORK, DECISION (the committee's), RETRO or STAFFING
     * @param producerVerdict the producer's own {@code VERDICT:} line, REPORTED; null when it wrote none
     * @param environmentBlocker what its last report says the environment lacks; null when none (the screen warns
     *     before a directive, which alone will not provide it)
     * @param reopenTo the ticket a directive would go to (itself, or a decided committee's implementation); null when
     *     refused
     */
    public record ReportTicket(
            String id,
            String title,
            String kind,
            String status,
            String producer,
            List<ReportReview> reviews,
            int rejections,
            String producerVerdict,
            int directives,
            int sessions,
            int failedSessions,
            Measure workMs,
            Measure elapsedMs,
            Measure costUsd,
            CostBound costBound,
            String commit,
            String reopenTo,
            String reopenRefusedCode,
            String reopenRefused,
            EnvironmentBlocker environmentBlocker) {
        public ReportTicket {
            reviews = reviews == null ? null : Collections.unmodifiableList(new ArrayList<>(reviews));
        }
    }

    public record ReportTotals(
            int done,
            int dropped,
            int open,
            int sessions,
            int failedSessions,
            Measure elapsedMs,
            Measure workMs,
            Measure costUsd,
            CostBound costBound,
            Double budgetUsd) {}

    /**
     * @param withReservations validating reviews whose reservations stand (a kept one, or unreadable; ADR-0053)
     * @param validated validating reviews with no reservation that stands
     */
    public record ReportReviews(int reviews, int validated, int withReservations, int rejected, int unverifiable) {}

    public record ReportCheck(
            String workspace,
            String command,
            Integer exitCode,
            boolean timedOut,
            long durationMs,
            String trigger,
            String at,
            boolean passed) {}

    public record ReportLesson(String id, String audience, String text, String status) {}

    /** @param state NONE, RUNNING, CONCLUDED or CLOSED (ended without the chair's conclusion, see reason) */
    public record ReportRetro(
            String state, String chair, List<String> conclusions, String reason, List<ReportLesson> lessons) {
        public ReportRetro {
            conclusions = conclusions == null ? null : Collections.unmodifiableList(new ArrayList<>(conclusions));
            lessons = lessons == null ? null : Collections.unmodifiableList(new ArrayList<>(lessons));
        }
    }

    /**
     * What a worktree holds, read from git now (MEASURED).
     *
     * @param accepted commits on the branch beyond the project's current branch; -1 when git cannot count
     * @param mergeCommand the command for the director to merge the branch into the project; null with nothing
     *     accepted
     */
    public record ReportOutput(
            String agent,
            String path,
            String branch,
            boolean readable,
            int accepted,
            List<String> uncommitted,
            int uncommittedCount,
            List<String> ignored,
            int ignoredCount,
            String mergeCommand) {
        public ReportOutput {
            ignored = ignored == null ? null : Collections.unmodifiableList(new ArrayList<>(ignored));
            uncommitted = uncommitted == null ? null : Collections.unmodifiableList(new ArrayList<>(uncommitted));
        }
    }

    /** A reason the end is not as clean as "every ticket done": loud ones first. */
    public record ReportWarning(String code, String ticket, String agent, String path, Integer count, String text) {}

    /**
     * One next step and whether it can be taken now.
     *
     * @param kind CLOSE, REOPEN, PULL_REQUEST, MERGE, FOLLOW_UP, ARCHIVE
     * @param code the refusal's code when not available
     */
    public record NextStep(String kind, boolean available, String code, String message) {}

    public record FollowUp(String title, String objective, String repository) {}

    /**
     * A question the director asked back instead of answering (ADR-0059), counted in the report.
     *
     * @param round which ask-back on its thread this one was, from 1 (the cap is {@code Desk.MAX_ASK_BACK})
     */
    public record ReportAskedBack(String question, String ticket, String agent, String subject, int round) {
        static List<ReportAskedBack> of(Company c, MissionId id) {
            return c.desk().of(id).stream()
                    .filter(q -> c.desk().askBack(q).event() != null)
                    .map(q -> new ReportAskedBack(
                            q.id().value(),
                            q.ticket() == null ? null : q.ticket().value(),
                            q.askedBy().value(),
                            q.subject(),
                            c.desk().askBack(q).rounds() + 1))
                    .toList();
        }
    }

    public record MissionReport(
            String mission,
            String title,
            String status,
            String origin,
            String outcome,
            String repository,
            String projectBranch,
            String readAt,
            ReportTotals totals,
            List<ReportTicket> tickets,
            ReportReviews reviews,
            List<ReportCheck> checks,
            ReportRetro retro,
            List<ReportOutput> outputs,
            List<ReportWarning> warnings,
            List<NextStep> next,
            FollowUp followUp,
            List<ReportObjectionRound> objections,
            List<ReportAskedBack> askedBack) {
        public MissionReport {
            askedBack = askedBack == null ? null : Collections.unmodifiableList(new ArrayList<>(askedBack));
            checks = checks == null ? null : Collections.unmodifiableList(new ArrayList<>(checks));
            next = next == null ? null : Collections.unmodifiableList(new ArrayList<>(next));
            objections = objections == null ? null : Collections.unmodifiableList(new ArrayList<>(objections));
            outputs = outputs == null ? null : Collections.unmodifiableList(new ArrayList<>(outputs));
            tickets = tickets == null ? null : Collections.unmodifiableList(new ArrayList<>(tickets));
            warnings = warnings == null ? null : Collections.unmodifiableList(new ArrayList<>(warnings));
        }
    }

    /**
     * One round of the director's objections at acceptance (ADR-0060), grouped by the cause its events share.
     *
     * @param targeted the tickets reopened with the objection as a directive
     * @param created the tickets made of new work
     */
    public record ReportObjectionRound(int number, List<String> targeted, List<String> created) {
        public ReportObjectionRound {
            created = created == null ? null : Collections.unmodifiableList(new ArrayList<>(created));
            targeted = targeted == null ? null : Collections.unmodifiableList(new ArrayList<>(targeted));
        }
    }

    @Inject
    OfficeService office;

    @Inject
    Workspaces workspaces;

    public MissionReport report(MissionId id) {
        Mission mission =
                office.query(c -> c.missions().find(id)).orElseThrow(() -> new NotFoundException("No mission " + id));
        // git is read outside the company's lock: it may take a moment on a large repository.
        List<Workspace> spaces = office.query(c -> c.workshop().workspaces(id));
        Path repo = mission.workspace() == null || mission.workspace().isBlank() ? null : Path.of(mission.workspace());
        String projectHead = repo != null && Files.isDirectory(repo) ? workspaces.resolve(repo, "HEAD") : null;
        String projectBranch = projectHead == null
                ? null
                : workspaces.facts(repo).map(Workspaces.RepositoryFacts::branch).orElse(null);
        List<ReportOutput> outputs = new ArrayList<>();
        for (Workspace w : spaces) outputs.add(output(w, repo, projectHead));
        Instant now = Instant.now();
        return office.query(c -> compose(c, mission, projectBranch, outputs, now));
    }

    private ReportOutput output(Workspace w, Path repo, String projectHead) {
        Path dir = Path.of(w.path());
        boolean readable = Files.isDirectory(dir) && workspaces.facts(dir).isPresent();
        // A status git did not give is unknown, never "nothing uncommitted" (ADR-0055).
        Optional<List<String>> known = readable ? workspaces.pendingIfKnown(dir) : Optional.empty();
        if (known.isEmpty()) {
            return new ReportOutput(
                    w.agent().value(), w.path(), w.branch(), false, -1, List.of(), 0, List.of(), 0, null);
        }
        List<String> pending = known.get();
        List<String> ignored = workspaces.ignored(dir);
        int accepted = projectHead == null ? -1 : workspaces.count(dir, projectHead + "..HEAD");
        String branch =
                workspaces.facts(dir).map(Workspaces.RepositoryFacts::branch).orElse(w.branch());
        String merge = accepted > 0 && repo != null && branch != null && !branch.isBlank()
                ? "git -C \"" + repo + "\" merge --no-ff " + branch
                : null;
        return new ReportOutput(
                w.agent().value(),
                w.path(),
                branch,
                true,
                accepted,
                pending.stream().limit(MAX_PATHS).toList(),
                pending.size(),
                ignored.stream().limit(MAX_PATHS).toList(),
                ignored.size(),
                merge);
    }

    /** The report from one reading of the company and what git answered for each worktree. */
    static MissionReport compose(Company c, Mission m, String projectBranch, List<ReportOutput> outputs, Instant now) {
        return new MissionReportComposer(c, m, now).compose(projectBranch, outputs);
    }

    /** The director's rounds of objections at acceptance (ADR-0060), oldest first. */
    static List<ReportObjectionRound> objections(Company c, MissionId id) {
        return c.missions().objectionRounds(id).stream()
                .map(r -> new ReportObjectionRound(
                        r.number(),
                        r.targeted().stream().map(t -> t.value()).toList(),
                        r.created().stream().map(t -> t.value()).toList()))
                .toList();
    }

    /** The producer's own last {@code VERDICT:} line, as written; null when none. */
    static String producerVerdict(String deliverable) {
        if (deliverable == null) return null;
        Matcher matcher = VERDICT_LINE.matcher(deliverable);
        String last = null;
        while (matcher.find()) last = matcher.group(1);
        return last == null ? null : clip(last, MAX_LINE);
    }

    /** The first line that says something, without Markdown headings or emphasis. */
    static String firstLine(String text) {
        if (text == null) return null;
        for (String line : text.lines().toList()) {
            if (line.stripLeading().startsWith("#")) continue;
            String s = line.replaceAll("^[#>*\\-\\s]+", "").replace("**", "").strip();
            if (!s.isEmpty()) return clip(s, MAX_LINE);
        }
        return null;
    }

    static String clip(String s, int max) {
        String one = s == null ? "" : s.strip();
        return one.length() <= max ? one : one.substring(0, max - 1) + "…";
    }
}
