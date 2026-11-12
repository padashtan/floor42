package dev.floor42.server.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The seam between Floor42 and GitHub's own command line, {@code gh} (ADR-0036). Floor42 never holds a GitHub token: gh
 * uses the director's existing {@code gh auth}. Tests replace the command by a fake script; no test reaches GitHub.
 */
public interface GhCli {

    /**
     * @param missing gh could not be started at all (not installed, not on the PATH)
     * @param timedOut stopped after the limit: what it did on GitHub is unknown
     */
    record Result(int code, String out, String err, boolean timedOut, boolean missing) {

        public boolean ok() {
            return code == 0 && !timedOut && !missing;
        }

        /** What gh said, for the director: its error output, else its output. */
        public String said() {
            String e = err == null ? "" : err.strip();
            return e.isEmpty() ? (out == null ? "" : out.strip()) : e;
        }
    }

    Result run(Path dir, List<String> args, long timeoutSeconds);

    /** Runs a real process: {@code command} (by default {@code gh}) followed by the arguments. */
    final class Process implements GhCli {

        private final List<String> command;

        public Process(List<String> command) {
            this.command = List.copyOf(command);
        }

        @Override
        public Result run(Path dir, List<String> args, long timeoutSeconds) {
            List<String> cmd = new ArrayList<>(command);
            cmd.addAll(args);
            return CliProcess.run(
                    cmd,
                    "floor42-gh-",
                    pb -> {
                        pb.directory(dir.toFile());
                        var env = pb.environment();
                        // Never wait for an answer nobody will type, never a login, never a colour code in what is
                        // parsed.
                        env.put("GH_PROMPT_DISABLED", "1");
                        env.put("GH_NO_UPDATE_NOTIFIER", "1");
                        env.put("GH_SPINNER_DISABLED", "1");
                        env.put("NO_COLOR", "1");
                        env.put("GIT_TERMINAL_PROMPT", "0");
                    },
                    0,
                    timeoutSeconds,
                    Result::new);
        }
    }
}
