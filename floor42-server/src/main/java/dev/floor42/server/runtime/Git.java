package dev.floor42.server.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Floor42's one way to run git, over {@link GitProcess} (literal pathspecs, {@code LC_ALL=C}, a time limit, the index
 * race retried). One error policy for every caller:
 *
 * <ul>
 *   <li>{@link #run} answers with git's exit code: a non-zero exit is an answer the caller reads ("not an ancestor",
 *       "no such ref");
 *   <li>{@link #out} and {@link #toFile} need git to succeed and throw a {@link GitFailure} otherwise;
 *   <li>a git that cannot be started, or a wait that is interrupted, always throws a {@link GitFailure}, cause kept:
 *       it is never taken for an answer.
 * </ul>
 */
@ApplicationScoped
public class Git {

    private static final Set<Integer> ZERO = Set.of(0);

    /** Runs {@code git <args>} in {@code dir}; its exit code and its time-out are data. */
    public GitProcess.Result run(Path dir, List<String> args) {
        try {
            return GitProcess.git(dir, args);
        } catch (IOException e) {
            throw GitFailure.unavailable(args, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw GitFailure.interrupted(args, e);
        }
    }

    public GitProcess.Result run(Path dir, String... args) {
        return run(dir, List.of(args));
    }

    /** Git's output; any exit but 0, or the time limit, throws a {@link GitFailure}. */
    public String out(Path dir, String... args) {
        return out(dir, ZERO, List.of(args));
    }

    /** Git's output when it exits with one of {@code ok} ({@code diff --no-index} exits 1 when files differ). */
    public String out(Path dir, Set<Integer> ok, List<String> args) {
        GitProcess.Result r = run(dir, args);
        if (r.timedOut() || !ok.contains(r.code())) throw GitFailure.of(args, r);
        return r.out();
    }

    /** Git's output when it exits with one of {@code ok}; {@link GitProcess.OutputTooLarge} past {@code maxBytes}. */
    public String outBounded(Path dir, Set<Integer> ok, List<String> args, long maxBytes) {
        GitProcess.Result r;
        try {
            r = GitProcess.gitBounded(dir, args, maxBytes);
        } catch (IOException e) {
            throw GitFailure.unavailable(args, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw GitFailure.interrupted(args, e);
        }
        if (r.timedOut() || !ok.contains(r.code())) throw GitFailure.of(args, r);
        return r.out();
    }

    /** Git's output when it exits 0; empty when it answers otherwise (or times out). */
    public Optional<String> outIfOk(Path dir, String... args) {
        GitProcess.Result r = run(dir, List.of(args));
        return r.timedOut() || r.code() != 0 ? Optional.empty() : Optional.of(r.out());
    }

    /** Writes git's output, byte for byte, to {@code target}; any failure throws a {@link GitFailure}. */
    public void toFile(Path dir, List<String> args, Path target) {
        GitProcess.Result r;
        try {
            r = GitProcess.gitToFile(dir, args, target);
        } catch (IOException e) {
            throw GitFailure.unavailable(args, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw GitFailure.interrupted(args, e);
        }
        if (r.timedOut() || r.code() != 0) throw GitFailure.of(args, r);
    }

    /** The full hash {@code revision} names in {@code dir}; empty when it names nothing there. */
    public Optional<String> resolve(Path dir, String revision) {
        return outIfOk(dir, "rev-parse", "--verify", "--quiet", revision).map(String::strip);
    }

    /** {@code git status --porcelain=v1 -z <extra>}, read; empty when git cannot read the folder. */
    public List<GitProcess.StatusEntry> status(Path dir, String... extra) {
        String[] args = new String[extra.length + 3];
        args[0] = "status";
        args[1] = "--porcelain=v1";
        args[2] = "-z";
        System.arraycopy(extra, 0, args, 3, extra.length);
        return outIfOk(dir, args).map(GitProcess::status).orElse(List.of());
    }
}
