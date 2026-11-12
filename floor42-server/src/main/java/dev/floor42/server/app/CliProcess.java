package dev.floor42.server.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * What the command line seams ({@link ClaudeCli}, {@link GhCli}) share: one command run to its end with its output and
 * errors in temporary files, nobody to answer a prompt, stopped with its children at the limit. A process that cannot
 * start, a timeout and an interrupt are results, never thrown.
 */
final class CliProcess {

    /** Builds a seam's own result from what was measured. */
    @FunctionalInterface
    interface Outcome<R> {
        R of(int code, String out, String err, boolean timedOut, boolean missing);
    }

    private CliProcess() {}

    /**
     * @param tag names the temporary files
     * @param setup the seam's own settings (folder, environment), applied to the builder before it starts
     * @param cap the most characters kept of each output, 0 for all of it
     */
    static <R> R run(
            List<String> cmd,
            String tag,
            Consumer<ProcessBuilder> setup,
            int cap,
            long timeoutSeconds,
            Outcome<R> result) {
        Path out = null;
        Path err = null;
        try {
            out = Files.createTempFile(tag, ".out"); // NOSONAR owner-only temp
            err = Files.createTempFile(tag, ".err"); // NOSONAR owner-only temp
            ProcessBuilder pb =
                    new ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectError(err.toFile());
            setup.accept(pb);
            return start(pb, out, err, cap, timeoutSeconds, result);
        } catch (IOException e) {
            // Handled: the result carries the failure to the caller, who shows it
            return result.of(-1, "", e.getMessage(), false, false);
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept for the caller; the result says the command was interrupted
            Thread.currentThread().interrupt();
            return result.of(-1, "", "interrupted", true, false);
        } finally {
            delete(out);
            delete(err);
        }
    }

    private static <R> R start(ProcessBuilder pb, Path out, Path err, int cap, long timeoutSeconds, Outcome<R> result)
            throws IOException, InterruptedException {
        java.lang.Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            // Handled: the result carries the failure to the caller, who shows it
            return result.of(-1, "", e.getMessage(), false, true);
        }
        // Nobody will type an answer: a prompt reads end of input and the command refuses.
        p.getOutputStream().close();
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            return result.of(-1, read(out, cap), read(err, cap), true, false);
        }
        return result.of(p.exitValue(), read(out, cap), read(err, cap), false, false);
    }

    private static String read(Path file, int cap) throws IOException {
        String s = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        return cap > 0 && s.length() > cap ? s.substring(0, cap) + "\n…" : s;
    }

    private static void delete(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Handled: the command's result is read already; a temporary file left behind harms nothing.
        }
    }
}
