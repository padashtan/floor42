package dev.floor42.server.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * A skill can carry a dynamic command: a {@code !} followed by a command in backticks, or a fenced block opened by
 * {@code ```!}. Claude Code runs it when the skill loads, on the host, without a PreToolUse hook (measured 2026-11-11
 * on 2.1.322, ADR-0028). The root fix is the session setting {@code disableSkillShellExecution}; this is the second
 * line: a skill holding such a line is not given to a session while the sandbox is on. Fail closed: a file that cannot
 * be read whole, a folder too big to scan or a link loop counts as having one.
 */
public final class DynamicSkills {

    /** Larger than any real skill; a bigger file is not read and counts as having a command. */
    static final long MAX_BYTES = 1024 * 1024;

    /** More entries than any real skill folder holds: past it the folder counts as having a command. */
    static final int MAX_ENTRIES = 2_000;

    private DynamicSkills() {}

    /** Whether the file (links followed) may run a command when loaded, or cannot be told. */
    public static boolean runsCommand(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return true;
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return text.contains("!`") || text.contains("```!");
        } catch (IOException e) {
            // Handled: a file that cannot be read cannot be shown free of a command
            return true;
        }
    }

    /**
     * Whether a skill folder holds a {@code SKILL.md} (any case, at any depth, links followed) that runs a command.
     * Unreadable, looping or oversized: true.
     */
    public static boolean folderRunsCommand(Path folder) {
        try (Stream<Path> walk = Files.walk(folder, FileVisitOption.FOLLOW_LINKS)) {
            int[] seen = {0};
            return walk.anyMatch(p -> {
                if (++seen[0] > MAX_ENTRIES) return true;
                return p.getFileName().toString().toLowerCase(Locale.ROOT).equals("skill.md") && runsCommand(p);
            });
        } catch (IOException | UncheckedIOException | SecurityException e) {
            // Handled: an unreadable folder or a link loop is not shown free of a command
            return true;
        }
    }
}
