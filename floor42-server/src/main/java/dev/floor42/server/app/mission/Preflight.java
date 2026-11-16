package dev.floor42.server.app.mission;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One verdict before a real mission starts (ADR-0070): blockers, warnings and what is fine, from facts handed in as
 * values. No I/O. Only what the server already refuses blocks (Claude Code missing, the sandbox required and not
 * working); everything else warns. Nothing is measured here: a tool never measured is "unmeasured", never "missing",
 * and what cannot be read (the offline ability of the sandbox) is "unknown", never a fact.
 */
public final class Preflight {

    public enum Level {
        BLOCK,
        WARN,
        INFO,
        OK
    }

    public enum Verdict {
        BLOCKED,
        WARNINGS,
        READY
    }

    /** What a sandbox measure said of one tool; UNKNOWN when there is none. */
    public enum ToolState {
        PRESENT,
        OLDER,
        MISSING,
        UNKNOWN
    }

    /** One tool the project needs, with its state in the sandbox. */
    public record Tool(String tool, String wanted, List<String> because, ToolState state, String version) {}

    /**
     * @param runtimeReason why Claude Code is unavailable, when it is (a diagnostic, in English)
     * @param runtimeReasonCode the same, as a code the screen translates
     * @param sandboxRequired the shell of a session must run in the sandbox (the setting, or mandatory outside unsafe
     *     mode)
     * @param repository the folder is a git work tree
     * @param hasCommit it has at least one commit
     * @param network the sandbox reaches the network
     * @param downloads build files that usually download dependencies (see {@link ProjectProfile.Profile})
     * @param hasChecks a checks command is given, or one is proposed
     */
    public record Facts(
            boolean runtimeAvailable,
            String runtimeReason,
            String runtimeReasonCode,
            boolean sandboxRequired,
            boolean sandboxWorking,
            boolean repository,
            boolean hasCommit,
            int uncommitted,
            boolean network,
            List<String> downloads,
            List<Tool> tools,
            boolean hasChecks) {}

    /** @param params the words the screen puts in the line's text, as strings */
    public record Line(String code, Level level, Map<String, String> params) {}

    public record Result(Verdict verdict, List<Line> lines) {}

    private Preflight() {}

    public static Result of(Facts f) {
        List<Line> lines = new ArrayList<>();
        if (!f.runtimeAvailable()) {
            lines.add(line(
                    "claude." + (f.runtimeReasonCode() == null ? "unavailable" : f.runtimeReasonCode()),
                    Level.BLOCK,
                    "reason",
                    f.runtimeReason() == null ? "" : f.runtimeReason()));
        }
        if (f.sandboxRequired() && !f.sandboxWorking()) lines.add(line("sandbox.required", Level.BLOCK));
        if (!f.repository() || !f.hasCommit()) lines.add(line("repo.noCommit", Level.WARN));
        else if (f.uncommitted() > 0)
            lines.add(line("repo.dirty", Level.WARN, "count", String.valueOf(f.uncommitted())));
        // The tools matter where the shell runs: in the sandbox.
        if (f.sandboxRequired()) f.tools().forEach(t -> toolLine(t, lines));
        if (!f.hasChecks()) lines.add(line("checks.none", Level.WARN));
        if (f.sandboxRequired() && !f.network()) {
            if (f.downloads().isEmpty()) lines.add(line("offline.unknown", Level.INFO));
            else f.downloads().forEach(file -> lines.add(line("offline.download", Level.WARN, "file", file)));
        }
        boolean calm = lines.stream().noneMatch(l -> l.level() == Level.BLOCK || l.level() == Level.WARN);
        if (calm) lines.add(line("ready", Level.OK));
        lines.sort(Comparator.comparing(Line::level)); // stable: the order above holds within a level
        Verdict v = lines.stream().anyMatch(l -> l.level() == Level.BLOCK)
                ? Verdict.BLOCKED
                : lines.stream().anyMatch(l -> l.level() == Level.WARN) ? Verdict.WARNINGS : Verdict.READY;
        return new Result(v, List.copyOf(lines));
    }

    private static void toolLine(Tool t, List<Line> lines) {
        String because = String.join(", ", t.because());
        switch (t.state()) {
            case MISSING -> lines.add(line("tool.missing", Level.WARN, "tool", t.tool(), "because", because));
            case OLDER ->
                lines.add(line(
                        "tool.older",
                        Level.WARN,
                        "tool",
                        t.tool(),
                        "version",
                        t.version() == null ? "" : t.version(),
                        "wanted",
                        t.wanted() == null ? "" : t.wanted(),
                        "because",
                        because));
            case UNKNOWN -> lines.add(line("tool.unmeasured", Level.WARN, "tool", t.tool()));
            case PRESENT -> {
                // Nothing to say: it is there.
            }
        }
    }

    private static Line line(String code, Level level, String... kv) {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) params.put(kv[i], kv[i + 1]);
        return new Line(code, level, Map.copyOf(params));
    }
}
