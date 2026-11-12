package dev.floor42.server.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Runs shell commands, the agents' and the director's checks, isolated by the OS in a WSL2 distribution (ADR-0028, pays
 * D5). Each command gets its own Linux namespaces, unprivileged: it sees its worktree (as its working directory) and the
 * distribution's own system files, never the Windows drives, the home folders or WSL's bridge that starts Windows
 * programs; the network is cut unless the director opens it.
 *
 * <p>The agent's command reaches the sandbox through Claude Code's PreToolUse hook: Floor42 answers "allow" with the
 * command rewritten to run here (measured: Claude Code 2.1.312 runs the rewritten command).
 */
@ApplicationScoped
public class Sandbox {

    private static final Logger LOG = Logger.getLogger(Sandbox.class);

    /**
     * The script on disk. The outer shell reads it whole and is replaced by the namespaces before anything is hidden;
     * inside them, Windows drives, run-time sockets (WSL's interop), home folders and the temporary folder are replaced
     * by empty ones, the worktree is mounted on its own and the command runs there. When the worktree is a linked git
     * worktree, the repository's git directory is mounted read-only (its hooks, config and other branches cannot be
     * changed) and the worktree's own git directory read-write, so git works. Arguments: worktree (Linux path), command
     * (base64), network (on|off), the repository's git directory and the worktree's name in it ("none" when the
     * worktree is not a linked one), and git's core.autocrlf as the director's git has it ("none" if unset), so git in
     * Linux reads Windows line endings as the director's git does; then the wall time in seconds, the process cap and
     * the file size cap in bytes, and "ro" or "rw": "ro" mounts the worktree and its git directory read-only (a
     * reviewer's session, ADR-0046 amended 2026-11-05) or "proof" (the worktree writable, the git directory read-only;
     * a proof copy has no .git, so the source's git directory is mounted, and a plain repository's is given with the
     * name "-"); /tmp stays a writable tmpfs.
     *
     * <p>The masks hold only because the command runs one user namespace deeper than the one that made them (amended
     * ADR-0028, 2026-11-03): mounts copied into a mount namespace owned by a less privileged user namespace are locked
     * by the kernel, so neither {@code umount} nor {@code mount --move} can uncover {@code /mnt}, {@code /run} or the
     * fresh {@code /proc}, and the read-only git mount cannot be made writable again. The sandbox also gets its own,
     * empty binfmt_misc, so WSL's handler that starts Windows programs is not applied inside. The files that tie the
     * worktree to its repository ({@code .git}, and {@code commondir}, {@code gitdir}, {@code config.worktree} and
     * {@code HEAD} in the worktree's git directory; a plain repository's whole {@code .git}) are mounted read-only, so
     * a command cannot make the director's next git on Windows read a configuration it wrote, nor move the worktree
     * onto another branch. The script first starts again with no file descriptor but 0, 1 and 2 (wsl.exe passes on the
     * director's terminal) and an environment of only LANG, LC_ALL, TERM and TZ, to which it adds PATH, HOME and the
     * GIT_* variables above; the sandbox gets its own {@code /dev} (a few harmless devices, a new devpts instance, a
     * private {@code shm}), an empty read-only {@code /sys} and its own cgroup, IPC and UTS namespaces, so neither the
     * VM's devices, its cgroups (the director's WSL sessions) nor its SysV queues, shared memory and semaphores are
     * within reach; {@code /var/tmp}, which persists on the distribution's disk, is an empty one too. The nested step does not
     * fork: the PID namespace's first process is {@code init.py}, which installs a seccomp filter (no AF_VSOCK socket,
     * the route to the host's file server; no io_uring; no ptrace of it), reaps orphans and, at the wall time, kills
     * every process left.
     */
    static final String RUN = """
            #!/bin/sh
            # Floor42's shell sandbox (ADR-0028).
            # wsl.exe hands every process more than stdin, stdout and stderr (fd 5 is the master of the director's login
            # terminal): start again with nothing but those three, and with an environment of only the locale and the
            # terminal (wsl.exe forwards what WSLENV lists, and the desktop's DISPLAY, WAYLAND_DISPLAY and the like).
            if [ "$1" != floor42-clean ]; then
              command -v python3 >/dev/null || { echo "Floor42 sandbox: python3 is missing in the distribution" >&2; exit 126; }
              exec python3 -I -c 'import os, sys
            os.closerange(3, os.sysconf("SC_OPEN_MAX"))
            env = {k: v for k, v in os.environ.items() if k in ("LANG", "LC_ALL", "TERM", "TZ")}
            env["PATH"] = "/usr/bin:/bin"
            os.execve("/bin/sh", ["sh", sys.argv[1], "floor42-clean"] + sys.argv[2:], env)' "$0" "$@"
            fi
            shift
            NET=-n
            [ "$3" = "on" ] && NET=
            export F42_NET="$3"
            INIT="${0%/*}/init.py"
            INNER='
            set -e
            rr() { mount -o "remount,bind,$(python3 -I /media/.mounts.py flags "$1")${2:+,$2}" "$1"; }; ro() { mount --bind "$1" "$1"; rr "$1"; }
            mount -t tmpfs tmpfs /media
            cp "${10}" /media/.floor42-init
            cp "${10%/*}/sandbox-mounts.py" /media/.mounts.py; cp "${10%/*}/toolchain.list" /media/.tools
            # Network off is a new namespace whose lo is down: bring it up (a private loopback; the loopback of the host stays out of reach).
            [ "$F42_NET" = on ] || python3 -I /media/.mounts.py loopback
            mkdir -p /media/work
            mount --bind "$1" /media/work
            if [ "$9" = ro ]; then rr /media/work; fi
            if [ "$3" != none ]; then
              mkdir -p /media/git
              mount --bind "$3" /media/git
              rr /media/git
              if [ "$4" != - ]; then
                mount --bind "$3/worktrees/$4" "/media/git/worktrees/$4"
                # ro and proof: the whole folder is read-only first and nothing is created in the source repository.
                if [ "$9" != rw ]; then rr "/media/git/worktrees/$4"; fi
                # rw: an overlay whose writes (index, logs, anything) go to this tmpfs and vanish: the source .git is
                # never written, so no file an agent plants can reach the git of the director on Windows. Read-only if no overlay.
                if [ "$9" = rw ]; then
                  mkdir -p /media/.wtlow /media/.ovg/u /media/.ovg/w
                  mount --bind "$3/worktrees/$4" /media/.wtlow; rr /media/.wtlow
                  mount -t overlay overlay -o "lowerdir=/media/.wtlow,upperdir=/media/.ovg/u,workdir=/media/.ovg/w" "/media/git/worktrees/$4" 2>/dev/null || rr "/media/git/worktrees/$4"
                fi
              fi
            fi
            if [ -e /media/work/.git ]; then ro /media/work/.git; fi; for p in .claude .mcp.json; do if [ -e "/media/work/$p" ]; then ro "/media/work/$p"; fi; done
            # A proof copy: the node_modules of the source worktree (git ignores them), nothing copied: overlays whose writes
            # (the temp files of vite) vanish, else read-only. Mounted from open folder handles, never following a link.
            [ -z "${11}" ] || python3 -I /media/.mounts.py deps "${12}" "${11}" /media/work /media
            D=/media/.dev
            mkdir "$D"
            mount -t tmpfs -o mode=755,nosuid,noexec tmpfs "$D"
            for n in null zero full random urandom tty; do : > "$D/$n"; mount --bind "/dev/$n" "$D/$n"; done
            mkdir "$D/pts" "$D/shm"
            mount -t devpts -o newinstance,ptmxmode=0666,mode=620 devpts "$D/pts"
            mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs "$D/shm"
            ln -s pts/ptmx "$D/ptmx"
            ln -s /proc/self/fd "$D/fd"
            ln -s /proc/self/fd/0 "$D/stdin"
            ln -s /proc/self/fd/1 "$D/stdout"
            ln -s /proc/self/fd/2 "$D/stderr"
            mount --move "$D" /dev
            mount -t tmpfs -o ro,nosuid,nodev,noexec,mode=555 tmpfs /sys
            R=$(readlink -f /etc/resolv.conf 2>/dev/null || :)
            cat /etc/resolv.conf > /media/.resolv 2>/dev/null || :
            # The host user Maven cache (toolchain.list, WSL path), read-only, under /media: the /mnt mask below hides its own path.
            unset F42_NET
            MR=/media/.mroot
            mkdir "$MR"
            python3 -I /media/.mounts.py tools /media/.tools "$MR" /media
            for d in /mnt /run /home /root /tmp /var/tmp; do mount -t tmpfs tmpfs "$d"; done
            # WSL links /etc/resolv.conf into /mnt/wsl (or /run), masked above: put the name servers back, nothing else.
            case "$R" in /mnt/*|/run/*) mkdir -p "${R%/*}"; cp /media/.resolv "$R";; esac
            mount -t binfmt_misc binfmt_misc /proc/sys/fs/binfmt_misc 2>/dev/null || true
            unset WSL_INTEROP WSLENV
            export HOME=/tmp PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            # Maven reads the cache as a read-only tail (3.9+); its local repository stays an empty one under /tmp.
            while read -r k p; do case "$k" in cache) case "$p" in *" "*) ;; *) if [ -d "${MR-}$p" ]; then export MAVEN_ARGS="-Dmaven.repo.local.tail=${MR-}$p"; fi;; esac;; esac; done < /media/.tools
            cd /media/work
            unset OLDPWD
            if [ "$9" != rw ]; then export GIT_OPTIONAL_LOCKS=0; fi
            if [ "$4" = - ]; then export GIT_DIR=/media/git GIT_WORK_TREE=/media/work
            elif [ "$3" != none ]; then export GIT_DIR="/media/git/worktrees/$4" GIT_WORK_TREE=/media/work; fi
            if [ "$5" != none ]; then export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.autocrlf GIT_CONFIG_VALUE_0="$5"; fi
            printf "%s" "$2" | base64 -d > /tmp/.floor42-command
            exec unshare -Urm --propagation private setpriv --no-new-privs \\
              prlimit --nproc="$7" --fsize="$8" -- python3 -I /media/.floor42-init "$6" /bin/sh /tmp/.floor42-command
            '
            exec unshare -Urm -C -i -u $NET -p -f --mount-proc --propagation private \\
              sh -c "$INNER" sh "$1" "$2" "$4" "$5" "$6" "$7" "$8" "$9" "${10}" "$INIT" "${11}" "${12}"
            """;

    @Inject
    SandboxPolicy policy;

    @ConfigProperty(name = "floor42.sandbox.distro", defaultValue = "Debian")
    String distro;

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    /** Wall time of one command, after which its whole Linux process tree is killed (its PID namespace ends). */
    @ConfigProperty(name = "floor42.sandbox.timeout-seconds", defaultValue = "1800")
    long timeoutSeconds = 1800;

    /** Processes and threads one command may hold at once, counted in its own user namespace. */
    @ConfigProperty(name = "floor42.sandbox.max-processes", defaultValue = "2048")
    long maxProcesses = 2048;

    /** Largest file one command may write, in bytes (drvfs writes land on the Windows disk). */
    @ConfigProperty(name = "floor42.sandbox.max-file-bytes", defaultValue = "4294967296")
    long maxFileBytes = 4_294_967_296L;

    /** Set where probing is not wanted (the tests): the sandbox is then taken as working or not, unmeasured. */
    @ConfigProperty(name = "floor42.sandbox.assume-available")
    java.util.Optional<Boolean> assumed;

    /** The system the script is started on: Windows through wsl.exe, Linux directly (SandboxHost). */
    String osName = SandboxHost.current();

    private volatile Boolean available;
    private volatile String probeFailure;

    /** Why the last probe failed (null when it passed or did not run); tests report it instead of skipping. */
    public String probeFailure() {
        return probeFailure;
    }

    /** For tests: what the next calls take as measured. */
    void setAvailable(Boolean value) {
        available = value;
    }

    /** Whether commands must run in the sandbox: the director's setting. */
    public boolean enabled() {
        return policy.sandboxEnabled() || policy.sandboxRequired();
    }

    /** Whether the sandbox works on this machine: measured once by running a command in it, then remembered. */
    public boolean available() {
        Boolean a = available;
        if (a == null && assumed.isPresent()) a = assumed.get();
        if (a == null) {
            a = probe();
            available = a;
        }
        return a;
    }

    /** Measures again, after the director installed or repaired WSL. */
    public boolean recheck() {
        available = null;
        return available();
    }

    /**
     * The command line for Claude Code's Bash tool (Git Bash on Windows): the same command, run in the sandbox at the
     * worktree. Path conversion is off so Git Bash passes Linux paths to wsl.exe untouched.
     */
    public String wrapForShell(Path worktree, String command, boolean readOnly) {
        // The shell reads this line: each argument in single quotes (an apostrophe is closed, escaped, reopened), so
        // & ; $( ) and backquotes in a path stay characters of that path.
        List<String> args =
                tail(worktree, command, policy.sandboxNetwork(), gitArgs(worktree), timeoutSeconds, readOnly);
        StringBuilder line = new StringBuilder(shellPrefix()).append(args.getFirst());
        for (String a : args.subList(1, args.size())) {
            line.append(" '").append(a.replace("'", "'\\''")).append('\'');
        }
        return line.toString();
    }

    String shellPrefix() {
        return SandboxHost.shellPrefix(osName, distro);
    }

    /** The git mounts of a worktree: the repository's git directory, the worktree's name in it, core.autocrlf. */
    private List<String> gitArgs(Path worktree) {
        var git = WorktreeGitDirs.gitDirs(worktree);
        return List.of(
                git.map(g -> linux(g.common())).orElse("none"),
                git.map(WorktreeGitDirs.GitDirs::name).orElse("none"),
                WorktreeGitDirs.autocrlf(worktree));
    }

    /** The arguments after {@code wsl.exe -d <distro> --exec}, with the git mounts already decided. */
    private List<String> tail(
            Path worktree, String command, boolean network, List<String> git, long seconds, boolean readOnly) {
        return tail(worktree, command, network && !readOnly, git, seconds, readOnly ? "ro" : "rw");
    }

    /** {@code mode}: "rw", "ro" (worktree and git read-only) or "proof" (worktree writable, git read-only). */
    private List<String> tail(
            Path worktree, String command, boolean network, List<String> git, long seconds, String mode) {
        List<String> args =
                new ArrayList<>(List.of("sh", linux(script()), linux(worktree), b64(command), network ? "on" : "off"));
        // A reviewer's shell is offline whatever the director opened for the agents (ADR-0046, amended 2026-11-05).
        args.addAll(git);
        // The same limits for every command, a proof step's included: wall time (the caller's own, so the Linux tree
        // ends when the caller gives up), processes, file size (run.sh $7-$9).
        args.add(Long.toString(Math.max(1, seconds)));
        args.add(Long.toString(Math.max(1, maxProcesses)));
        args.add(Long.toString(Math.max(1, maxFileBytes)));
        args.add(mode);
        return args;
    }

    /** The argument list for a process Floor42 starts itself, with the configured wall time. */
    public List<String> argv(Path worktree, String command) {
        return argv(worktree, command, timeoutSeconds);
    }

    /** The same, with the caller's wall time: the sandbox ends the Linux tree when the caller stops waiting. */
    public List<String> argv(Path worktree, String command, long seconds) {
        return argv(worktree, command, seconds, false);
    }

    /** The same, the worktree read-only inside when {@code readOnly}. */
    List<String> argv(Path worktree, String command, long seconds, boolean readOnly) {
        return wsl(tail(worktree, command, policy.sandboxNetwork(), gitArgs(worktree), seconds, readOnly));
    }

    /**
     * The argument list for a command an agent wrote that Floor42 runs itself (a delivery's proof, ADR-0058): the
     * network is cut whatever the director opened for the agents.
     */
    public List<String> argvOffline(Path worktree, String command, long seconds) {
        // Nothing is read from the folder (no .git file, no git config): a step may have rewritten them, and the
        // next step must not mount what they name. No git folder is shared with a proof.
        return wsl(tail(worktree, command, false, List.of("none", "none", "none"), seconds, false));
    }

    /** The same for a copy of {@code source}: its {@code node_modules} folders are mounted read-only (run.sh ${11}, ${12}). */
    public List<String> argvOffline(Path worktree, String command, long seconds, Path source) {
        List<String> argv = wsl(tail(worktree, command, false, proofGit(source), seconds, "proof"));
        return DependencyMounts.with(argv, source, linux(source));
    }

    /**
     * The git mounts of a proof copy, which holds no {@code .git}: those of the source worktree, so git in the copy
     * reports the source's HEAD and index. A linked worktree as usual; a plain repository by its {@code .git} folder
     * (name "-"). Read-only whatever the command does ("proof" mode).
     */
    private List<String> proofGit(Path source) {
        Path dotGit = source.resolve(".git");
        if (WorktreeGitDirs.gitDirs(source).isEmpty()
                && java.nio.file.Files.isDirectory(dotGit, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return List.of(linux(dotGit), "-", WorktreeGitDirs.autocrlf(source));
        }
        return gitArgs(source);
    }

    private List<String> wsl(List<String> tail) {
        return SandboxHost.argv(osName, distro, tail);
    }

    /** C:\Users\x → /mnt/c/Users/x (WSL's default mount of Windows drives). */
    static String linux(Path p) {
        // A drive path is absolute on Windows only: read the drive letter from the text, so the result is the same
        // whatever system Floor42 (or its build) runs on.
        String raw = p.normalize().toString().replace('\\', '/');
        String s = raw.matches("[A-Za-z]:/.*")
                ? raw
                : p.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (s.length() > 1 && s.charAt(1) == ':') s = "/mnt/" + Character.toLowerCase(s.charAt(0)) + s.substring(2);
        // Spaces and apostrophes are fine (every argument is quoted: wrapForShell escapes '); control characters are
        // not.
        if (s.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Unsupported path for the sandbox: " + s);
        return s;
    }

    static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private Path script() {
        Path p = dataDir.resolve("sandbox").resolve("run.sh");
        try {
            write(p, SandboxHost.linux(osName) ? SandboxNative.script(RUN) : RUN);
            write(p.resolveSibling("init.py"), resource("sandbox-init.py"));
            write(p.resolveSibling("sandbox-mounts.py"), resource("sandbox-mounts.py"));
            write(
                    p.resolveSibling("toolchain.list"),
                    ToolchainMounts.listing(System.getProperty("user.home"), System.getenv()));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write the sandbox script: " + e.getMessage(), e);
        }
        return p;
    }

    /** The command's first process (seccomp filter, orphan reaper, wall timer) and the mount helper: Python, stdlib. */
    static String resource(String name) throws IOException {
        try (var in = Sandbox.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException(name + " is not in Floor42's jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void write(Path p, String content) throws IOException {
        String text = content.replace("\r\n", "\n");
        if (!Files.isRegularFile(p) || !Files.readString(p).equals(text)) {
            Files.createDirectories(p.getParent());
            Files.writeString(p, text);
        }
    }

    /**
     * Runs "echo floor42-sandbox-ok" in a scratch folder, in the sandbox, and expects exactly that back. The run is
     * bounded as the other probes are: output capped, process and children killed at the limit.
     */
    private boolean probe() {
        if (!SandboxHost.supported(osName)) return false;
        try {
            Path dir = Files.createDirectories(dataDir.resolve("sandbox").resolve("probe"));
            var r = SandboxProbe.start(argvOffline(dir, "echo floor42-sandbox-ok", 60), Duration.ofSeconds(60));
            boolean ok = works(r);
            probeFailure = ok
                    ? null
                    : (r.timedOut() ? "no answer in time: " : "") + r.output().strip();
            return ok;
        } catch (IOException | RuntimeException e) {
            // Handled: logged; the sandbox is reported as not working (fail closed)
            LOG.warnf("The shell sandbox does not work here: %s", e.getMessage());
            probeFailure = String.valueOf(e.getMessage());
            return false;
        }
    }

    /** Whether a probe run exited 0 with the expected word; a run cut at the limit does not work. */
    static boolean works(SandboxProbe.RunResult r) {
        boolean ok = !r.timedOut()
                && Integer.valueOf(0).equals(r.exitCode())
                && r.output().contains("floor42-sandbox-ok");
        if (!ok)
            LOG.warnf(
                    "The shell sandbox does not work here%s: %s",
                    r.timedOut() ? " (no answer in time)" : "", r.output().strip());
        return ok;
    }
}
