package dev.floor42.server.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Floor42's one git runner: the workshop and the workspaces run git the same way. Pathspecs are literal (a file named
 * {@code *} or {@code :(top)} is only that file), paths are written as they are ({@code core.quotepath=off}), and the
 * output goes to temporary files, not to pipes read before the wait: a git that hangs (a lock, a prompt, a slow disk)
 * is stopped after a minute instead of holding the caller forever. Errors stay apart from the output, so that
 * machine-read output ({@code -z}) is never mixed with a warning.
 */
public final class GitProcess {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(GitProcess.class);

    public static final long TIMEOUT_SECONDS = 60;

    public record Result(int code, String out, String err, boolean timedOut) {

        /** What git said, output then errors: for a message to the director. */
        public String said() {
            return (out + (out.isBlank() || err.isBlank() ? "" : "\n") + err).strip();
        }
    }

    private GitProcess() {}

    /** The command line Floor42 runs for {@code git <args>} in {@code dir}. */
    public static List<String> command(Path dir, List<String> args) {
        List<String> cmd = new ArrayList<>(
                List.of("git", "--literal-pathspecs", "-c", "core.quotepath=off", "-C", dir.toString()));
        cmd.addAll(args);
        int sub = subcommand(args);
        // A .gitmodules the agent wrote could set ignore=none, which beats diff.ignoreSubmodules: only the command line
        // keeps git from entering a nested repository whose own configuration runs programs.
        if (sub >= 0 && ENTERS_SUBMODULES.contains(args.get(sub)) && !args.contains("--no-index"))
            cmd.add(cmd.size() - args.size() + sub + 1, "--ignore-submodules=all");
        return cmd;
    }

    private static final java.util.Set<String> ENTERS_SUBMODULES =
            java.util.Set.of("status", "diff", "diff-files", "diff-index");

    /** Where git's command is in {@code args}, after the global options ({@code -c} takes a value); -1 if none. */
    static int subcommand(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("-c") || a.equals("-C")) i++;
            else if (!a.startsWith("-")) return i;
        }
        return -1;
    }

    /**
     * The environment of every git Floor42 runs. Never wait for a password or a passphrase nobody will type; and speak
     * English whatever the machine's language: {@link #raced} reads git's messages, which a French or German git would
     * translate ({@code LC_ALL=C}, and {@code LANGUAGE=C}, which gettext reads before it).
     */
    public static void environment(Map<String, String> env) {
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("LC_ALL", "C");
        env.put("LANGUAGE", "C");
        env.put(
                "GIT_OPTIONAL_LOCKS",
                "0"); // a read (status) never takes index.lock: no race with a commit in the same worktree
        // An agent writes the worktree: whatever it plants there must not make this git run a program (ADR-0028,
        // amended 2026-11-03). Passed as environment, so a git this one starts in a submodule gets it too. Hooks stay
        // on: they gate the director's commits. The one push of a pull request skips them when the director has no
        // global core.hooksPath, since .git/hooks is then an agent's to write (PullRequestGit.push, docs/quality.md).
        List<String> pinned = List.of(
                "core.fsmonitor", "false",
                "protocol.ext.allow", "never",
                "diff.ignoreSubmodules", "all",
                "submodule.recurse", "false",
                // A committed .lfsconfig names the server git-lfs downloads from: no download, no failure. The clean
                // filter stays (it works offline), so an LFS project's accepted files are still stored as pointers.
                "filter.lfs.smudge", "",
                "filter.lfs.process", "",
                "filter.lfs.required", "false");
        env.put("GIT_LFS_SKIP_SMUDGE", "1");
        env.put("GIT_CONFIG_COUNT", Integer.toString(pinned.size() / 2));
        for (int i = 0; i < pinned.size() / 2; i++) {
            env.put("GIT_CONFIG_KEY_" + i, pinned.get(2 * i));
            env.put("GIT_CONFIG_VALUE_" + i, pinned.get(2 * i + 1));
        }
    }

    /**
     * Refuses a worktree whose links to its repository were changed: its {@code .git} file must point outside the
     * worktree, and for a linked worktree its git directory's {@code commondir} must name the repository that holds it
     * and its {@code gitdir} must point back at this {@code .git} file. Floor42 made them so; an agent's command could
     * repoint them at a git directory it wrote, whose configuration would then run a program on Windows.
     */
    static void checkLinks(Path dir) throws IOException {
        Path dotGit = dir.resolve(".git");
        if (Files.isDirectory(dotGit) && Files.exists(dotGit.resolve("commondir")))
            refuse(dir, "a plain repository's .git holds a commondir");
        if (!Files.isRegularFile(dotGit)) return;
        Path root = dir.toAbsolutePath().normalize();
        String line = Files.readString(dotGit).strip();
        if (!line.startsWith("gitdir:")) refuse(dir, ".git does not name a git directory");
        Path own = root.resolve(line.substring("gitdir:".length()).strip()).normalize();
        if (own.startsWith(root)) refuse(dir, ".git points inside the worktree");
        Path worktrees = own.getParent();
        if (worktrees == null || !worktrees.getFileName().toString().equals("worktrees")) return;
        Path commondir = own.resolve("commondir");
        if (Files.isRegularFile(commondir)) {
            Path common = own.resolve(Files.readString(commondir).strip()).normalize();
            if (!Files.exists(common) || !Files.isSameFile(common, worktrees.getParent()))
                refuse(dir, "commondir does not name the repository that holds the worktree");
        }
        Path back = own.resolve("gitdir");
        if (Files.isRegularFile(back)) {
            Path target = own.resolve(Files.readString(back).strip()).normalize();
            if (!Files.exists(target) || !Files.isSameFile(target, dotGit))
                refuse(dir, "gitdir does not point back at the worktree");
        }
    }

    /**
     * {@code git add} enters every populated submodule whatever the configuration says (measured 2026-11-03) and runs
     * there what that repository's own configuration names (a clean filter, for one). A worktree Floor42 made has no
     * populated submodule ({@code git worktree add} does not fill them): one with a {@code .git} was planted.
     */
    private static void checkBeforeAdd(Path dir) throws IOException, InterruptedException {
        checkNoPopulatedGitlink(dir);
        // An untracked folder holding a repository is listed with a trailing slash; add would make it a gitlink.
        Result r = once(dir, List.of("ls-files", "-z", "--others", "--exclude-standard"));
        if (r.code() != 0) refuse(dir, "git could not list the untracked files: " + r.said());
        for (String path : paths(r.out())) {
            if (path.endsWith("/")) refuse(dir, path + " holds a repository of its own; Floor42 does not stage it");
        }
    }

    /** The states another command left that {@code git commit} would carry on (an author, a message, a parent). */
    static final List<String> PENDING = List.of(
            "CHERRY_PICK_HEAD",
            "REVERT_HEAD",
            "MERGE_HEAD",
            "MERGE_MSG",
            "SQUASH_MSG",
            "sequencer",
            "rebase-merge",
            "rebase-apply");

    /**
     * Refuses to commit while the git directory holds a pending operation: Floor42 never leaves one, and one planted
     * from the sandbox would lend Floor42's commit another commit's author.
     */
    private static void checkNoPendingOperation(Path dir) throws IOException {
        Path dotGit = dir.resolve(".git");
        Path own = Files.isRegularFile(dotGit)
                ? dir.toAbsolutePath()
                        .resolve(Files.readString(dotGit)
                                .strip()
                                .substring("gitdir:".length())
                                .strip())
                        .normalize()
                : dotGit;
        for (String name : PENDING) {
            if (Files.exists(own.resolve(name), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                refuse(dir, "the git directory holds " + name + ", left by a command Floor42 did not run");
        }
    }

    private static void checkNoPopulatedGitlink(Path dir) throws IOException, InterruptedException {
        Result r = once(dir, List.of("ls-files", "-z", "--stage"));
        if (r.code() != 0) refuse(dir, "git could not list the index: " + r.said());
        for (String entry : r.out().split("\0")) {
            int tab = entry.indexOf('\t');
            if (!entry.startsWith("160000 ") || tab < 0) continue;
            String path = entry.substring(tab + 1);
            boolean populated;
            try {
                populated = Files.exists(dir.resolve(path).resolve(".git"), java.nio.file.LinkOption.NOFOLLOW_LINKS);
            } catch (java.nio.file.InvalidPathException e) {
                // Handled: a name Windows cannot hold is refused like a populated one
                populated = true;
            }
            if (populated) refuse(dir, "the submodule " + path + " holds a repository Floor42 did not make");
        }
    }

    /** A push or a read of a remote runs only with settings Floor42 can trust: see {@link NetworkConfigGuard}. */
    private static void checkNetworkConfig(Path dir) throws IOException, InterruptedException {
        Result r = once(dir, NetworkConfigGuard.LIST);
        if (r.code() != 0) refuse(dir, "git could not list its settings: " + r.said());
        java.util.Optional<String> key = NetworkConfigGuard.risky(r.out());
        if (key.isPresent())
            refuse(dir, "the repository sets " + key.get() + ", which decides where a push goes or what carries it");
    }

    private static void refuse(Path dir, String why) throws IOException {
        LOG.warnf("Floor42 refused to run git in %s: %s (changed from inside the worktree?)", dir, why);
        throw new IOException("Floor42 refused to run git in " + dir + ": " + why);
    }

    /** How many times a git command is run again when another git process held the index (see {@link #raced}). */
    public static final int RACE_RETRIES = 5;

    public static Result git(Path dir, List<String> args) throws IOException, InterruptedException {
        Result r = once(dir, args);
        for (int i = 1; i <= RACE_RETRIES && raced(r); i++) {
            Thread.sleep(40L * i);
            r = once(dir, args);
        }
        return r;
    }

    /**
     * True when git stopped before doing anything because another git process held the worktree's index at that very
     * moment (D13): on Windows, git cannot open the index while another one replaces it ("index file open failed:
     * Permission denied"), and a second writer finds the first one's lock ("index.lock': File exists"). Run again, the
     * same command succeeds; any other failure is the caller's to read.
     */
    public static boolean raced(Result r) {
        if (r.timedOut() || r.code() == 0) return false;
        return r.err().contains("index file open failed: Permission denied")
                || (r.err().contains("index.lock") && r.err().contains("File exists"));
    }

    /** Git's output went past the bound the caller set: the run was stopped, nothing more was read. */
    public static final class OutputTooLarge extends RuntimeException {
        public OutputTooLarge(long maxBytes) {
            super("git printed more than " + maxBytes + " bytes");
        }
    }

    /** Like {@link #git}, but stops git and throws {@link OutputTooLarge} once its output passes {@code maxBytes}. */
    public static Result gitBounded(Path dir, List<String> args, long maxBytes)
            throws IOException, InterruptedException {
        return once(dir, args, maxBytes);
    }

    private static Result once(Path dir, List<String> args) throws IOException, InterruptedException {
        return once(dir, args, -1);
    }

    private static Result once(Path dir, List<String> args, long maxBytes) throws IOException, InterruptedException {
        checkLinks(dir);
        int sub = subcommand(args);
        String name = sub >= 0 ? args.get(sub) : "";
        if (name.equals("add")) checkBeforeAdd(dir);
        if (name.equals("commit")) checkNoPendingOperation(dir);
        if (NetworkConfigGuard.COMMANDS.contains(name)) checkNetworkConfig(dir);
        List<String> cmd = command(dir, args);
        Path out = Files.createTempFile("floor42-git-", ".out"); // NOSONAR owner-only temp
        Path err = Files.createTempFile("floor42-git-", ".err"); // NOSONAR owner-only temp
        try {
            ProcessBuilder pb =
                    new ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectError(err.toFile());
            environment(pb.environment());
            Process p = pb.start();
            p.getOutputStream().close();
            if (!waited(p, out, maxBytes)) {
                stopAndWait(p);
                return new Result(-1, read(out), read(err), true);
            }
            return new Result(p.exitValue(), read(out), read(err), false);
        } finally {
            quietDelete(out);
            quietDelete(err);
        }
    }

    /** Waits for the end of git within the time limit; with a bound, watches the output and stops past it. */
    private static boolean waited(Process p, Path out, long maxBytes) throws IOException, InterruptedException {
        if (maxBytes < 0) return p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!p.waitFor(50, TimeUnit.MILLISECONDS)) {
            if (Files.size(out) > maxBytes) {
                stopAndWait(p);
                throw new OutputTooLarge(maxBytes);
            }
            if (System.nanoTime() > deadline) return false;
        }
        if (Files.size(out) > maxBytes) throw new OutputTooLarge(maxBytes);
        return true;
    }

    /** Kills a timed-out git with its children and waits for its end: its output files are then free to delete. */
    private static void stopAndWait(Process p) throws InterruptedException {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        p.waitFor(10, TimeUnit.SECONDS);
    }

    /** A temp file that cannot be deleted (still locked) must not replace the result of the run. */
    private static void quietDelete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // Handled: a temp file left behind; the result of the run is what matters
            file.toFile().deleteOnExit();
        }
    }

    /**
     * Runs {@code git <args>} in {@code dir} and writes its output, byte for byte, to {@code target} (a blob, a binary
     * file): nothing is decoded. The result's {@code out} is empty; the same minute's timeout applies.
     */
    public static Result gitToFile(Path dir, List<String> args, Path target) throws IOException, InterruptedException {
        checkLinks(dir);
        List<String> cmd = command(dir, args);
        Path err = Files.createTempFile("floor42-git-", ".err"); // NOSONAR owner-only temp
        try {
            ProcessBuilder pb =
                    new ProcessBuilder(cmd).redirectOutput(target.toFile()).redirectError(err.toFile());
            environment(pb.environment());
            Process p = pb.start();
            p.getOutputStream().close();
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                stopAndWait(p);
                return new Result(-1, "", read(err), true);
            }
            return new Result(p.exitValue(), "", read(err), false);
        } finally {
            quietDelete(err);
        }
    }

    /**
     * One entry of {@code git status --porcelain=v1 -z}.
     *
     * @param code the two status letters, {@code ??} for an untracked file
     * @param path the path now (the new name of a renamed or copied file)
     * @param from the former name of a renamed or copied file, null otherwise
     */
    public record StatusEntry(String code, String path, String from) {}

    /**
     * Reads {@code git status --porcelain=v1 -z}: entries end with NUL and nothing is quoted, whatever the name holds
     * (spaces, quotes, accents, a newline). A rename or a copy is followed by its former name.
     */
    public static List<StatusEntry> status(String z) {
        List<StatusEntry> entries = new ArrayList<>();
        String[] fields = z.split("\0");
        for (int i = 0; i < fields.length; i++) {
            String f = fields[i];
            if (f.length() < 4) continue;
            String code = f.substring(0, 2);
            String path = f.substring(3);
            String from = null;
            if ((code.charAt(0) == 'R' || code.charAt(0) == 'C') && i + 1 < fields.length) from = fields[++i];
            entries.add(new StatusEntry(code, path, from));
        }
        return entries;
    }

    /** Reads a list of paths written with {@code -z} ({@code ls-files -z}, {@code diff --name-only -z}). */
    public static List<String> paths(String z) {
        List<String> paths = new ArrayList<>();
        for (String p : z.split("\0")) {
            if (!p.isEmpty()) paths.add(p);
        }
        return paths;
    }

    private static String read(Path file) throws IOException {
        // Bad bytes (a CP1252 file, a Latin-1 name) become U+FFFD instead of failing the whole diff or status.
        return StandardCharsets.UTF_8
                .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file)))
                .toString();
    }
}
