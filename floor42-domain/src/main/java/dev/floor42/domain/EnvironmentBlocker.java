package dev.floor42.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A step the producer says it could not do because the environment it runs in lacks something (a tool, a browser, the
 * network, an operating system), read from its report's marker line, for instance {@code BLOCKED BY ENVIRONMENT: a
 * desktop browser; step: render report.pdf with tools/render.cmd}. Another session in the same environment would hit
 * the same wall: a review that does not validate such a report asks the director instead of sending it back to work.
 *
 * @param missing what the environment lacks, as the producer named it
 * @param step the step it prevents; null when the report names none
 */
public record EnvironmentBlocker(String missing, String step) {

    /** The marker the developer's brief asks for; the last such line of a report wins. */
    public static final String MARKER = "BLOCKED BY ENVIRONMENT:";

    // The marker opens its line (bold markdown allowed around it), never a quote of it inside a sentence.
    private static final Pattern LINE =
            Pattern.compile("(?im)^[*_]{0,2}BLOCKED BY ENVIRONMENT[*_]{0,2}:[*_]{0,2}\\s*(.+?)\\s*$");
    private static final Pattern ONLY = Pattern.compile("(?im)^[*_]{0,2}ENVIRONMENT ONLY[*_]{0,2}:[*_]{0,2}\\s*yes\\b");
    private static final Pattern STEP_SPLIT = Pattern.compile("(?i)^(.*?)\\s*;\\s*step\\s*:\\s*(.*)$");

    /** The blocker a report declares, or empty when it declares none (or names nothing missing). */
    public static Optional<EnvironmentBlocker> in(String report) {
        if (report == null) return Optional.empty();
        Matcher m = LINE.matcher(report);
        String last = null;
        while (m.find()) {
            // The brief's own template line ("<what is missing>; step: ...") names nothing.
            if (!m.group(1).contains("<what is missing>")) last = m.group(1);
        }
        if (last == null) return Optional.empty();
        String missing = last;
        String step = null;
        Matcher s = STEP_SPLIT.matcher(last);
        if (s.matches()) {
            missing = s.group(1);
            step = s.group(2).isBlank()
                    ? null
                    : clip(s.group(2).strip().replaceFirst("(?<=[\\p{L}\\p{N}`'\")])\\.$", ""), 240);
        }
        missing = missing.replaceAll("(?:^[*_`\\s]+)|(?:[*_`\\s]+$)", "");
        missing = missing.replaceFirst("(?i)^(?:no|missing|without)\\s+", "");
        return missing.isEmpty() ? Optional.empty() : Optional.of(new EnvironmentBlocker(clip(missing, 120), step));
    }

    /**
     * The phrase codes of Floor42's questions about tools a plan needs and the sandbox lacks or cannot reach (server's
     * MissingTools and DockerQuestion).
     */
    public static final java.util.Set<String> MISSING_TOOLS_PHRASES = java.util.Set.of(
            "missingTools", "missingToolsNoCommand", "dockerUnreachable", "dockerMissing", "dockerUnmeasured");

    /**
     * The blocker the director took on before the work: a missing-tools question naming this ticket, answered "I do
     * the step myself". Its review is then the director's to settle, never a reopening for that step.
     */
    public static Optional<EnvironmentBlocker> directorStep(java.util.List<Question> questions, TicketId ticket) {
        for (Question q : questions) {
            if (q.status() != QuestionStatus.ANSWERED || q.phrase() == null) continue;
            if (!MISSING_TOOLS_PHRASES.contains(q.phrase().code()) || !q.answered(OptionCode.DO_IT_MYSELF)) continue;
            String tickets = q.phrase().params().getOrDefault("tickets", "");
            boolean named = java.util.Arrays.stream(tickets.split(","))
                    .map(String::strip)
                    .anyMatch(ticket.value()::equals);
            String tools = q.phrase().params().getOrDefault("tools", "");
            if (named && !tools.isBlank()) return Optional.of(new EnvironmentBlocker(clip(tools, 120), null));
        }
        return Optional.empty();
    }

    /**
     * The step the director took on for the whole mission: such a question naming no ticket (no ticket said it NEEDS
     * the tool), answered "I do the step myself". Briefs say it; no review escalates for it, every ticket's review runs
     * as usual.
     */
    public static Optional<EnvironmentBlocker> missionStep(java.util.List<Question> questions) {
        for (Question q : questions) {
            if (q.status() != QuestionStatus.ANSWERED || q.phrase() == null) continue;
            if (!MISSING_TOOLS_PHRASES.contains(q.phrase().code()) || !q.answered(OptionCode.DO_IT_MYSELF)) continue;
            String tools = q.phrase().params().getOrDefault("tools", "");
            if (q.phrase().params().getOrDefault("tickets", "").isBlank() && !tools.isBlank()) {
                return Optional.of(new EnvironmentBlocker(clip(tools, 120), null));
            }
        }
        return Optional.empty();
    }

    private static final Pattern DOCKER = Pattern.compile("(?i)\\bdocker\\b");

    /**
     * True when what is missing is Docker: the sandbox cannot reach its daemon whatever is installed (ADR-0028), so the
     * question about it says so and offers no install.
     */
    public boolean docker() {
        return DOCKER.matcher(missing).find();
    }

    /** True when the reviewer's findings say, on a line of their own, that the blocker is the only issue left. */
    public static boolean onlyIssue(String findings) {
        return findings != null && ONLY.matcher(findings).find();
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
