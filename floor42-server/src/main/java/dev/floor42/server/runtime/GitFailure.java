package dev.floor42.server.runtime;

import java.io.IOException;
import java.util.List;

/**
 * A git command that did not do what its caller needed: it exited with an error, ran past {@link GitProcess#TIMEOUT_SECONDS},
 * could not be started, or was interrupted. It keeps the command, the exit code and what git wrote on its error stream, so
 * that the one place it reaches the API (a mapper, 409 {@code GIT_FAILED}) can say exactly what failed.
 */
public final class GitFailure extends RuntimeException {

    /** The exit code when git gave none: not started, stopped after the time limit, or interrupted. */
    public static final int NO_EXIT = -1;

    private final List<String> args;
    private final int exitCode;
    private final String stderr;
    private final boolean timedOut;

    private GitFailure(List<String> args, int exitCode, String stderr, boolean timedOut, String said, Throwable cause) {
        super(describe(args, exitCode, timedOut, said), cause);
        this.args = List.copyOf(args);
        this.exitCode = exitCode;
        this.stderr = stderr == null ? "" : stderr;
        this.timedOut = timedOut;
    }

    /** Git ran and failed, or ran past the time limit. */
    public static GitFailure of(List<String> args, GitProcess.Result r) {
        return new GitFailure(args, r.timedOut() ? NO_EXIT : r.code(), r.err(), r.timedOut(), r.said(), null);
    }

    /** Git could not be started at all (not installed, not on the PATH, a temporary file it needs refused). */
    public static GitFailure unavailable(List<String> args, IOException cause) {
        return new GitFailure(
                args, NO_EXIT, cause.getMessage(), false, "git is not available: " + cause.getMessage(), cause);
    }

    /** The thread waiting for git was interrupted; the interrupt flag is set again by the caller of this factory. */
    public static GitFailure interrupted(List<String> args, InterruptedException cause) {
        return new GitFailure(args, NO_EXIT, "interrupted", false, "interrupted", cause);
    }

    private static String describe(List<String> args, int exitCode, boolean timedOut, String said) {
        String command = "git " + String.join(" ", args);
        if (timedOut) return command + " timed out after " + GitProcess.TIMEOUT_SECONDS + " s";
        if (exitCode == NO_EXIT) return command + " failed: " + said;
        return command + " failed: " + (said == null || said.isBlank() ? "exit code " + exitCode : said);
    }

    /** The command as it ran, {@code git <args>}. */
    public String command() {
        return "git " + String.join(" ", args);
    }

    public List<String> args() {
        return args;
    }

    /** Git's exit code, or {@link #NO_EXIT}. */
    public int exitCode() {
        return exitCode;
    }

    /** What git wrote on its error stream (or why it could not run). */
    public String stderr() {
        return stderr;
    }

    public boolean timedOut() {
        return timedOut;
    }
}
