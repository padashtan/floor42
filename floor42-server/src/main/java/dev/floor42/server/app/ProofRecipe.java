package dev.floor42.server.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A producer's verification recipe, read from {@code PROVE.md} at the root of its worktree (ADR-0058): steps, each a
 * level-two heading, one fenced shell command and what it must show. Parsing is pure: nothing here runs anything.
 *
 * @param state {@link #FOUND}, {@link #MISSING} or {@link #UNKNOWN}
 * @param digest SHA-256 of the file (16 hex characters), null when there is no file
 * @param problems codes about the whole file
 */
public record ProofRecipe(String state, String digest, List<Step> steps, List<String> problems) {
    public ProofRecipe {
        problems = problems == null ? null : Collections.unmodifiableList(new ArrayList<>(problems));
        steps = steps == null ? null : Collections.unmodifiableList(new ArrayList<>(steps));
    }

    public static final String FILE = "PROVE.md";
    public static final String FOUND = "FOUND";
    public static final String MISSING = "MISSING";
    public static final String UNKNOWN = "UNKNOWN";

    static final int MAX_BYTES = 64 * 1024;
    static final int MAX_STEPS = 20;
    static final int MAX_COMMAND = 4_000;
    static final int MAX_TITLE = 120;

    /**
     * One step as written by the producer.
     *
     * @param index from 1, in the file's order
     * @param expectExit null when the step does not name an exit code
     * @param director the producer marked it for the director
     * @param problems codes that keep the step from running; empty when it can run
     */
    public record Step(
            int index,
            String title,
            String command,
            Integer expectExit,
            List<String> expectOutput,
            boolean director,
            List<String> problems) {
        public Step {
            expectOutput = expectOutput == null ? null : Collections.unmodifiableList(new ArrayList<>(expectOutput));
            problems = problems == null ? null : Collections.unmodifiableList(new ArrayList<>(problems));
        }

        public boolean runnable() {
            return problems.isEmpty();
        }
    }

    private static final Pattern FENCE = Pattern.compile("^(```+|~~~+)\\s*([A-Za-z]*)\\s*(\\S*)\\s*$");
    private static final Pattern EXIT = Pattern.compile("(?i)^expect exit:\\s*(.*)$");
    private static final Pattern OUTPUT = Pattern.compile("(?i)^expect output:\\s?(.*)$");

    /**
     * What a command shown with a copy button must not hold: bidirectional overrides and isolates, zero-width
     * characters and control characters other than a line break or a tab. The text pasted would not be the text read.
     */
    static final Pattern HIDDEN = Pattern.compile("[\\u202A-\\u202E\\u2066-\\u2069\\u200E\\u200F\\u061C"
            + "\\u200B-\\u200D\\u2060\\uFEFF\\u00AD\\p{Cc}&&[^\\n\\t]]");

    /** No {@code PROVE.md} in the worktree. */
    public static ProofRecipe missing() {
        return new ProofRecipe(MISSING, null, List.of(), List.of());
    }

    /** Floor42 cannot read the worktree or the file: said, never taken as a pass. */
    public static ProofRecipe unknown(String problem) {
        return new ProofRecipe(UNKNOWN, null, List.of(), List.of(problem));
    }

    /** Reads the file's bytes; a file over the limit is not parsed. */
    public static ProofRecipe parse(byte[] bytes) {
        String digest = digest(bytes);
        if (bytes.length > MAX_BYTES) return new ProofRecipe(FOUND, digest, List.of(), List.of("TOO_LARGE"));
        List<Step> steps = new Reader(new String(bytes, StandardCharsets.UTF_8)).steps();
        List<String> problems = new ArrayList<>();
        if (steps.isEmpty()) problems.add("NO_STEPS");
        if (steps.size() > MAX_STEPS) {
            problems.add("TOO_MANY_STEPS");
            steps = steps.subList(0, MAX_STEPS);
        }
        return new ProofRecipe(FOUND, digest, List.copyOf(steps), List.copyOf(problems));
    }

    static String digest(byte[] bytes) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    .substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    /** Reads the steps line by line: a heading opens a step, a shell fence is its command, expect lines follow. */
    private static final class Reader {
        private final List<String> lines;
        private final List<Step> steps = new ArrayList<>();
        private String title;
        private final List<String> commands = new ArrayList<>();
        private final List<String> exits = new ArrayList<>();
        private final List<String> outputs = new ArrayList<>();
        private boolean director;

        Reader(String text) {
            this.lines = text.replace("\r\n", "\n").lines().toList();
        }

        List<Step> steps() {
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                Matcher fence = FENCE.matcher(line.strip());
                if (fence.matches()) {
                    i = fenced(i, fence);
                } else if (line.startsWith("## ")) {
                    close();
                    title = line.substring(3).strip();
                } else if (title != null) {
                    expectation(line.strip());
                }
            }
            close();
            return steps;
        }

        /** A fenced block: the step's command when it is a shell one; any other fence is skipped whole. */
        private int fenced(int start, Matcher fence) {
            String marker = fence.group(1);
            StringBuilder body = new StringBuilder();
            int i = start + 1;
            while (i < lines.size() && !lines.get(i).strip().startsWith(marker)) {
                if (!body.isEmpty()) body.append('\n');
                body.append(lines.get(i));
                i++;
            }
            String lang = fence.group(2).toLowerCase(Locale.ROOT);
            if (title != null && (lang.equals("sh") || lang.equals("bash") || lang.equals("shell"))) {
                commands.add(body.toString().strip());
                director |= fence.group(3).equalsIgnoreCase("director");
            }
            return i;
        }

        private void expectation(String line) {
            Matcher exit = EXIT.matcher(line);
            if (exit.matches()) {
                exits.add(exit.group(1).strip());
                return;
            }
            Matcher output = OUTPUT.matcher(line);
            if (output.matches() && !output.group(1).isBlank())
                outputs.add(output.group(1).strip());
        }

        private void close() {
            if (title == null) return;
            List<String> problems = new ArrayList<>();
            if (commands.isEmpty()) problems.add("NO_COMMAND");
            if (commands.size() > 1) problems.add("TWO_COMMANDS");
            String command = commands.isEmpty() ? "" : commands.getFirst();
            if (command.length() > MAX_COMMAND) problems.add("COMMAND_TOO_LONG");
            if (title.length() > MAX_TITLE) problems.add("TITLE_TOO_LONG");
            if (hidden(command)) problems.add("HIDDEN_CHARACTER");
            Integer exit = exit(problems);
            if (exits.isEmpty() && outputs.isEmpty()) problems.add("NO_EXPECTATION");
            steps.add(new Step(
                    steps.size() + 1,
                    title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE) : title,
                    command.length() > MAX_COMMAND ? command.substring(0, MAX_COMMAND) : command,
                    exit,
                    List.copyOf(outputs),
                    director,
                    List.copyOf(problems)));
            title = null;
            commands.clear();
            exits.clear();
            outputs.clear();
            director = false;
        }

        /** The step's exit code, null when none or a bad one (said in {@code problems}). */
        private Integer exit(List<String> problems) {
            if (exits.size() > 1) problems.add("TWO_EXIT_CODES");
            if (exits.size() != 1) return null;
            Integer exit = exitCode(exits.getFirst());
            if (exit == null) problems.add("BAD_EXIT_CODE");
            return exit;
        }

        private boolean hidden(String command) {
            return HIDDEN.matcher(command).find()
                    || HIDDEN.matcher(title).find()
                    || outputs.stream().anyMatch(o -> HIDDEN.matcher(o).find());
        }

        private static Integer exitCode(String text) {
            if (!text.matches("\\d{1,3}")) return null;
            int n = Integer.parseInt(text);
            return n <= 255 ? n : null;
        }
    }
}
