package dev.floor42.server.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The seam between Floor42 and Claude Code's own command line for plugins ({@code claude plugin ...}, ADR-0040). Only
 * the director's confirmed install or uninstall runs it, never an agent. Tests replace the command by a fake one
 * ({@code FakeClaudeCli.java}); no test reaches the network or a model.
 */
public interface ClaudeCli {

    /**
     * @param missing the command could not be started at all
     * @param timedOut stopped after the limit: what it did is unknown, Floor42 reads the installed state again
     */
    record Result(int code, String out, String err, boolean timedOut, boolean missing) {

        public boolean ok() {
            return code == 0 && !timedOut && !missing;
        }
    }

    /**
     * @param configDir the Claude Code configuration folder to act on ({@code CLAUDE_CONFIG_DIR}); null for the
     *     director's default one
     */
    Result run(List<String> args, Path configDir, long timeoutSeconds);

    /** The command as the director reads it in the confirmation dialog. */
    static String shown(List<String> args, Path configDir) {
        StringBuilder s = new StringBuilder();
        if (configDir != null)
            s.append("CLAUDE_CONFIG_DIR=").append(quote(configDir.toString())).append(' ');
        s.append("claude");
        for (String a : args) s.append(' ').append(quote(a));
        return s.toString();
    }

    private static String quote(String a) {
        return a.matches("[A-Za-z0-9@%_+=:,./\\\\-]+") ? a : "'" + a.replace("'", "'\\''") + "'";
    }

    /** Runs a real process: {@code command} (by default {@code claude}) followed by the arguments. */
    final class Process implements ClaudeCli {

        private final List<String> command;

        public Process(List<String> command) {
            this.command = List.copyOf(command);
        }

        @Override
        public Result run(List<String> args, Path configDir, long timeoutSeconds) {
            List<String> cmd = new ArrayList<>(command.stream()
                    .map(c -> Files.isRegularFile(Path.of(c))
                            ? Path.of(c).toAbsolutePath().toString()
                            : c)
                    .toList());
            cmd.addAll(args);
            return CliProcess.run(
                    cmd,
                    "floor42-claude-",
                    pb -> {
                        Map<String, String> env = pb.environment();
                        // Whatever Claude Code session or configuration the daemon was started from stays out: the
                        // folder acted
                        // on is the one Floor42 reads afterwards, and nothing else.
                        env.keySet()
                                .removeIf(k ->
                                        k.toUpperCase(java.util.Locale.ROOT).startsWith("CLAUDE"));
                        if (configDir != null)
                            env.put(
                                    "CLAUDE_CONFIG_DIR",
                                    configDir.toAbsolutePath().toString());
                        env.put("NO_COLOR", "1");
                        env.put("GIT_TERMINAL_PROMPT", "0");
                    },
                    20_000,
                    timeoutSeconds,
                    Result::new);
        }
    }
}
