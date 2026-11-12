package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real sandbox script in the real Linux, in WSL on Windows and natively on Linux (ADR-0028, amended 2026-11-09):
 * every known way out of it is tried, and the proof is on the host side: a folder that only a program outside the
 * sandbox can create is never created. Skipped only where no backend exists (no WSL distribution, or a Linux kernel
 * without unprivileged user namespaces). Every probe only reads, or writes inside the sandbox.
 */
@Tag("slow")
class SandboxEscapeTest {

    @TempDir
    Path data;

    @TempDir
    Path windows;

    @BeforeAll
    static void sandboxRunsHere() {
        WslSandbox.platformOrSkip();
    }

    /** One escape script: any line starting with LEAK names a way out; the marker proves a Windows program ran. */
    static final String ESCAPES = """
            leak() { echo "LEAK $*"; }
            win() { "$@" /c mkdir "$MARK" >/dev/null 2>&1; }
            socks() { find / \\( -path /proc -o -path /sys -o -path /mnt -o -path /media -o -path /tmp/moved \\) -prune -o -name '*_interop' -print 2>/dev/null; }
            try_interop() {
              for s in $(socks) /run/WSL/1_interop; do WSL_INTEROP=$s win "$1"; done
              win "$1"
            }
            umount /mnt 2>/dev/null; umount -l /mnt 2>/dev/null
            umount /run 2>/dev/null; umount -l /run 2>/dev/null
            umount /proc 2>/dev/null; umount -l /proc 2>/dev/null
            mkdir -p /tmp/moved && mount --move /mnt /tmp/moved 2>/dev/null
            [ -e /mnt/c/Windows ] && leak mnt-c
            [ -e /tmp/moved/c/Windows ] && leak mount-move
            [ -n "$(socks)" ] && leak interop-socket $(socks)
            [ -S /run/docker.sock ] && leak docker-socket
            # mount(8) keeps its own table in /run/mount, made inside the sandbox's empty /run.
            [ -n "$(ls /run 2>/dev/null | grep -v '^mount$')" ] && leak run-not-empty
            for p in /proc/[0-9]*; do
              [ -e "$p/root/mnt/c/Windows" ] && leak "$p/root" && try_interop "$p/root/mnt/c/Windows/System32/cmd.exe"
              [ -e "$p/cwd/Windows" ] && leak "$p/cwd"
              nsenter -t "${p#/proc/}" -m -U --preserve-credentials sh -c '[ -e /mnt/c/Windows ]' 2>/dev/null \\
                && leak "nsenter ${p#/proc/}"
            done
            [ "$(ls /proc | grep -c '^[0-9]')" -gt 15 ] && leak host-proc
            try_interop /mnt/c/Windows/System32/cmd.exe
            try_interop /tmp/moved/c/Windows/System32/cmd.exe
            try_interop ./win.exe
            ls /proc/sys/fs/binfmt_misc 2>/dev/null | grep -qi interop && leak binfmt-interop
            echo ':WSLInterop:M::MZ::/init:PF' > /proc/sys/fs/binfmt_misc/register 2>/dev/null
            echo ':f42probe:E::f42probe::/bin/true:' > /proc/sys/fs/binfmt_misc/register 2>/dev/null
            try_interop ./win.exe
            python3 -I -c 'import socket; s = socket.socket(40, 1); s.settimeout(3); s.connect((2, 50002)); print("LEAK vsock")' 2>/dev/null
            python3 -I -c 'import socket; socket.socket(40, 1); print("LEAK vsock-socket")' 2>/dev/null
            for p in /proc/[0-9]*; do
              for fd in "$p"/fd/*; do
                t=$(readlink "$fd" 2>/dev/null) || continue
                case "$t" in /dev/ptmx|/dev/pts/*) leak "terminal $fd $t";; esac
              done
            done
            # Nothing persistent is writable but the worktree (and its git directory): the masks are in memory.
            find / \\( -path /proc -o -path /sys -o -path /dev -o -path /tmp -o -path /var/tmp -o -path /media \\
              -o -path /run -o -path /home -o -path /root -o -path /mnt \\) -prune -o ! -type l -writable -print \\
              2>/dev/null | head -5 | while read -r p; do leak "writable $p"; done
            grep -q ' /var/tmp tmpfs ' /proc/self/mounts || leak var-tmp-on-disk
            # The VM's sysfs and cgroups: the director's WSL sessions could be frozen or killed from there.
            w=$(find /sys -writable 2>/dev/null | head -3 | tr '\\n' ' ')
            [ -n "$w" ] && leak "sys-writable $w"
            [ -e /sys/fs/cgroup/cgroup.procs ] && leak cgroup-visible
            grep -q '^0::/$' /proc/self/cgroup || leak "cgroup-not-own $(cat /proc/self/cgroup)"
            for fs in cgroup2 debugfs tracefs configfs fusectl securityfs; do
              grep -q " $fs " /proc/self/mounts && [ -n "$(ls /sys/fs/cgroup /sys/kernel 2>/dev/null)" ] && leak "fs $fs"
            done
            # The VM's devices: the director's terminals, the GPU, fuse, tun, vsock, the kernel log.
            python3 -I -c 'import os
            for f, mode in [("/dev/pts/0", os.O_RDWR), ("/dev/pts/1", os.O_RDWR), ("/dev/pts/2", os.O_RDWR),
                            ("/dev/pts/3", os.O_RDWR), ("/dev/tty", os.O_RDWR), ("/dev/dxg", os.O_RDWR),
                            ("/dev/fuse", os.O_RDWR), ("/dev/net/tun", os.O_RDWR), ("/dev/vsock", os.O_RDWR),
                            ("/dev/kmsg", os.O_RDONLY), ("/dev/kvm", os.O_RDWR), ("/dev/sda", os.O_RDONLY)]:
                try:
                    os.close(os.open(f, mode | os.O_NOCTTY | os.O_NONBLOCK))
                    print("LEAK device", f)
                except OSError:
                    pass
            m, s = os.openpty()
            print("pty-works" if os.ttyname(s).startswith("/dev/pts/") else "LEAK pty " + os.ttyname(s))'
            # What any program the command starts inherits: nothing past stdin, stdout and stderr.
            python3 -I -c 'import os
            for f in os.listdir("/proc/self/fd"):
                t = os.readlink("/proc/self/fd/" + f) if os.path.exists("/proc/self/fd/" + f) else ""
                if int(f) > 2 and t and not t.startswith("/proc/"): print("LEAK fd", f, t)'
            # The worktree's git directory is an overlay: these writes succeed in the sandbox and never reach the host
            # (the test hashes the source .git before and after). Only the .git file of the work is read-only.
            if [ -n "$GIT_DIR" ]; then
              for f in "$GIT_DIR/commondir" "$GIT_DIR/gitdir" "$GIT_DIR/config.worktree" "$GIT_DIR/HEAD"; do
                echo tampered >> "$f" 2>/dev/null
                mv "$f" "$f.moved" 2>/dev/null
              done
              echo '[core]' > "$GIT_DIR/config.worktree" 2>/dev/null
              echo tampered >> /media/work/.git 2>/dev/null && leak "write /media/work/.git"
              mv /media/work/.git /media/work/.git.moved 2>/dev/null && leak "move /media/work/.git"
            fi
            if [ -n "$GIT_DIR" ]; then
              mount -o remount,bind,rw /media/git 2>/dev/null
              touch /media/git/hooks/floor42-escape 2>/dev/null && leak git-writable
            fi
            echo inside > inside.txt
            echo floor42-escape-end
            """;

    @Test
    void noKnownWayOutReachesWindows() throws Exception {
        Path wt = linkedWorktree();
        Path foreign = wt.resolve("win.exe");
        if (SandboxHost.linux(SandboxHost.current())) {
            // No Windows program on Linux: a stand-in that makes the folder in its arguments, as cmd.exe /c mkdir does.
            Files.writeString(foreign, "#!/bin/sh\nshift\nexec \"$@\"\n");
            assertThat(foreign.toFile().setExecutable(true)).isTrue();
        } else {
            Files.copy(Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe"), foreign);
        }
        Path marker = windows.resolve("windows-ran");
        Sandbox sandbox = sandbox(1800, 2048, 4_294_967_296L);

        Path sourceGit = Path.of(Files.readString(wt.resolve(".git"))
                        .strip()
                        .substring("gitdir:".length())
                        .strip())
                .getParent()
                .getParent();
        String before = ProofGitTest.snapshot(sourceGit);

        Result r = run(sandbox.argv(wt, "MARK='" + marker + "'\n" + ESCAPES), 300);

        assertThat(r.out()).as("the script ran to its end").contains("floor42-escape-end");
        assertThat(r.out()).as("a pseudo-terminal still works inside").contains("pty-works");
        assertThat(wt.resolve("inside.txt")).as("the worktree stays writable").exists();
        assertThat(r.out()).as("no way out is open").doesNotContain("LEAK");
        assertThat(marker).as("no Windows program ran").doesNotExist();
        assertThat(ProofGitTest.snapshot(sourceGit))
                .as("the source .git, byte for byte")
                .isEqualTo(before);
        String common = Files.readString(wt.resolve(".git"))
                .strip()
                .substring("gitdir:".length())
                .strip();
        assertThat(Path.of(common).getParent().getParent().resolve("hooks").resolve("floor42-escape"))
                .as("the repository's git directory stays read-only")
                .doesNotExist();
        assertThat(Files.readString(Path.of(common).resolve("commondir")))
                .as("the worktree's link to its repository is unchanged")
                .doesNotContain("tampered");
        assertThat(GitProcess.git(wt, List.of("status", "--porcelain")).code())
                .as("Floor42's git still accepts the worktree")
                .isZero();
    }

    @Test
    void theProcessCapBoundsAForkLoop() throws Exception {
        Path work = Files.createDirectories(data.resolve("work"));
        String loop = "grep processes /proc/self/limits\n"
                + "( i=0; while [ $i -lt 200 ]; do sleep 30 & echo started $i; i=$((i+1)); done ) 2>&1 | tail -n 1\n"
                + "echo loop-end\n";

        Result r = run(sandbox(60, 32, 4_294_967_296L).argv(work, loop), 120);

        assertThat(r.out()).contains("Max processes", "32", "loop-end");
        assertThat(r.out())
                .as("the loop was stopped well before its 200 processes")
                .doesNotContain("started 199");
    }

    @Test
    void freezingTheFirstProcessByPtraceDoesNotCancelTheWallTime() throws Exception {
        Path work = Files.createDirectories(data.resolve("work"));
        String hold = "import ctypes, time\n"
                + "libc = ctypes.CDLL(None, use_errno=True)\n"
                + "libc.ptrace.argtypes = [ctypes.c_long, ctypes.c_long, ctypes.c_void_p, ctypes.c_void_p]\n"
                + "print('seize', libc.ptrace(0x4206, 1, None, None), flush=True)\n"
                + "libc.ptrace(0x4207, 1, None, None)\n"
                + "while True: time.sleep(1)\n";
        String command = "printf '%s' '" + Sandbox.b64(hold) + "' | base64 -d > /tmp/hold.py\n"
                + "setsid python3 -I /tmp/hold.py f42hold </dev/null >/dev/null 2>&1 &\n"
                + "sleep 1; grep State /proc/1/status; sleep 60\n";
        long start = System.nanoTime();
        try {
            Result r = run(sandbox(4, 2048, 4_294_967_296L).argv(work, command), 60);

            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
            assertThat(seconds).as(r.out()).isLessThan(30);
            Result left = run(WslSandbox.host("sh", "-c", "pgrep -fc f42hol[d]"), 60);
            assertThat(left.out().strip())
                    .as("the frozen sandbox's processes are gone")
                    .isEqualTo("0");
        } finally {
            run(WslSandbox.host("pkill", "-KILL", "-f", "f42hold"), 60);
        }
    }

    /** What the command may see of its environment: what the sandbox sets, and the locale and terminal kept by name. */
    static final java.util.Set<String> ALLOWED = java.util.Set.of(
            "PATH",
            "HOME",
            "PWD",
            "OLDPWD",
            "LANG",
            "LC_ALL",
            "TERM",
            "TZ",
            "GIT_DIR",
            "GIT_WORK_TREE",
            "GIT_CONFIG_COUNT",
            "GIT_CONFIG_KEY_0",
            "GIT_CONFIG_VALUE_0",
            "MAVEN_ARGS");

    @Test
    void onlyTheAllowedEnvironmentReachesTheCommandEvenWhenWslForwardsMore() throws Exception {
        Path work = Files.createDirectories(data.resolve("work"));
        java.util.Map<String, String> env = new java.util.HashMap<>(java.util.Map.of(
                "ANTHROPIC_API_KEY", "sk-floor42-fake",
                "FLOOR42_TOKEN", "floor42-fake-token",
                "DISPLAY", ":9",
                "WAYLAND_DISPLAY", "wayland-9",
                "PULSE_SERVER", "unix:/tmp/pulse",
                "DBUS_SESSION_BUS_ADDRESS", "unix:path=/tmp/bus",
                "XDG_RUNTIME_DIR", "/tmp/xdg"));
        env.put("WSLENV", String.join("/u:", env.keySet()) + "/u");

        Result r = run(sandbox(60, 2048, 4_294_967_296L).argv(work, "env; echo env-end"), 120, env);

        assertThat(r.out()).contains("env-end").doesNotContain("fake");
        List<String> names = r.out()
                .lines()
                .filter(l -> l.matches("[A-Za-z_][A-Za-z0-9_]*=.*"))
                .map(l -> l.substring(0, l.indexOf('=')))
                .toList();
        assertThat(names).as(r.out()).isNotEmpty().allMatch(ALLOWED::contains);
    }

    /** The director's Maven cache is a read-only tail: artifacts only, nothing else of ~/.m2, nothing written back. */
    @Test
    void theMavenCacheIsAReadOnlyTailAndNothingElseOfDotM2IsShown() throws Exception {
        assumeTrue(SandboxHost.windows(SandboxHost.current()));
        Path m2 = Path.of(System.getProperty("user.home"), ".m2");
        assumeTrue(Files.isDirectory(m2.resolve("repository")));
        Path work = Files.createDirectories(data.resolve("work"));
        String probe = "t=${MAVEN_ARGS#-Dmaven.repo.local.tail=}\n"
                + "echo tail=$t\n"
                + "[ -d \"$t\" ] && echo tail-visible\n"
                + "(: > \"$t/f42-write\") 2>/dev/null && echo LEAK tail-writable\n"
                + "mkdir \"$t/f42-dir\" 2>/dev/null && echo LEAK tail-mkdir\n"
                + "ls \"${t%/*}\"\n"
                + "ls /mnt | head -3\n"
                + "echo probe-end\n";

        Result r = run(sandbox(60, 2048, 4_294_967_296L).argv(work, probe), 120);

        assertThat(r.out())
                .contains("tail=/media/", "tail-visible", "probe-end")
                .doesNotContain("LEAK");
        assertThat(r.out()).as("only the repository folder of .m2").doesNotContain("settings", "wrapper");
        assertThat(m2.resolve("repository/f42-write")).doesNotExist();
        assertThat(m2.resolve("repository/f42-dir")).doesNotExist();
    }

    @Test
    void theBuildToolsStillWorkWithoutTheMachinesSysfs() throws Exception {
        Path work = Files.createDirectories(data.resolve("work"));
        String tools = "t() { if ! command -v \"$1\" >/dev/null; then echo \"absent-$1\";"
                + " elif \"$@\" >/dev/null 2>&1; then echo \"ok-$1\"; else echo \"fail-$1\"; fi; }\n"
                + "t java -version; t node -e 'process.exit(0)'; t mvn -v; t git --version; t nproc\n";

        Result r = run(sandbox(120, 2048, 4_294_967_296L).argv(work, tools), 180);

        assertThat(r.out()).as(r.out()).doesNotContain("fail-").contains("ok-nproc");
    }

    @Test
    void theVmsSysVQueuesAreNotVisible() throws Exception {
        Path work = Files.createDirectories(data.resolve("work"));
        Result made = run(WslSandbox.host("ipcmk", "-Q"), 60);
        String id = made.out().replaceAll("(?s).*?(\\d+)\\s*$", "$1");
        assertThat(id).as(made.out()).matches("\\d+");
        try {
            Result r =
                    run(sandbox(60, 2048, 4_294_967_296L).argv(work, "ipcs -q | awk '{print $2}'; echo ipc-end"), 120);

            assertThat(r.out()).contains("ipc-end");
            assertThat(r.out().lines()).as("the queue made outside").doesNotContain(id);
        } finally {
            run(WslSandbox.host("ipcrm", "-q", id), 60);
        }
    }

    @Test
    void orphansAreReapedSoTheyDoNotHoldTheProcessCap() throws Exception {
        Path work = Files.createDirectories(data.resolve("work"));
        String orphans = "i=0; while [ $i -lt 20 ]; do (true &); i=$((i+1)); done; sleep 1\n"
                + "echo zombies=$(grep -l '^State:.*Z' /proc/[0-9]*/status 2>/dev/null | wc -l)\n";

        Result r = run(sandbox(60, 2048, 4_294_967_296L).argv(work, orphans), 120);

        assertThat(r.out()).contains("zombies=0");
    }

    @Test
    void theFileCapBoundsOneFile() throws Exception {
        Path work = Files.createDirectories(data.resolve("work"));

        Result r = run(sandbox(60, 2048, 1_048_576).argv(work, "head -c 3000000 /dev/zero > big; echo done"), 120);

        assertThat(r.out()).contains("done");
        assertThat(Files.size(work.resolve("big"))).isLessThanOrEqualTo(1_048_576);
    }

    @Test
    void theWallTimeEndsTheWholeProcessTree() throws Exception {
        Path work = Files.createDirectories(data.resolve("work"));
        long start = System.nanoTime();

        Result r = run(sandbox(3, 2048, 4_294_967_296L).argv(work, "sleep 4711 & sleep 4712 & sleep 60"), 120);

        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        assertThat(seconds)
                .as("stopped at the wall time, not after the 60 s sleep")
                .isLessThan(30);
        assertThat(r.code()).isNotZero();
        Result left = run(WslSandbox.host("sh", "-c", "pgrep -fc 'sleep 471[12]'"), 60);
        assertThat(left.out().strip())
                .as("no background process outlives the sandbox")
                .isEqualTo("0");
    }

    @Test
    void theCallersOwnWallTimeKillsTheTreeWhateverTheConfiguredOneIs() throws Exception {
        Path work = Files.createDirectories(data.resolve("work"));
        long start = System.nanoTime();

        Result r = run(sandbox(1800, 2048, 4_294_967_296L).argv(work, "sleep 4731 & sleep 4732; sleep 30", 2), 120);

        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        assertThat(seconds).as(r.out()).isLessThan(20);
        assertThat(r.code()).as("killed: 128 + 9").isEqualTo(137);
        Result left = run(WslSandbox.host("sh", "-c", "pgrep -fc 'sleep 473[12]'"), 60);
        assertThat(left.out().strip()).as("no process left").isEqualTo("0");
    }

    /** A repository with a linked worktree, so the sandbox mounts the repository's git directory read-only. */
    private Path linkedWorktree() throws IOException, InterruptedException {
        Path repo = Files.createDirectories(data.resolve("repo"));
        assertThat(GitProcess.git(repo, List.of("init", "-q", "-b", "main")).code())
                .isZero();
        Files.writeString(repo.resolve("a.txt"), "a\n");
        GitProcess.git(repo, List.of("add", "a.txt"));
        GitProcess.git(repo, List.of("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "a"));
        Path wt = data.resolve("wt");
        assertThat(GitProcess.git(repo, List.of("worktree", "add", "-q", wt.toString()))
                        .code())
                .isZero();
        assertThat(WorktreeGitDirs.gitDirs(wt)).isPresent();
        return wt;
    }

    @Test
    void withTheNetworkOnTheNameServersAreThereAndNothingElseOfMnt() throws Exception {
        Path work = Files.createDirectories(data.resolve("work"));
        String probe =
                "grep -c '^nameserver' /etc/resolv.conf; find /mnt /run -mindepth 1 -not -path '/run/mount*' | sort";

        boolean nativeLinux = SandboxHost.linux(SandboxHost.current());
        // Native Linux gives network access through pasta only (SandboxNative): without it nothing here is measured.
        assumeTrue(
                !nativeLinux || NativeChecks.measure().pasta(),
                "pasta is not installed (sudo apt install passt): network on is refused, see SandboxNativeEscapeTest");
        Result on = run(sandbox(60, 2048, 4_294_967_296L, true).argv(work, probe, 30), 90);

        if (nativeLinux) {
            assertThat(on.code()).as(on.out()).isZero();
            // pasta may print a line of its own first ("No interfaces with usable IPv6 routes").
            assertThat(Integer.parseInt(on.out()
                            .lines()
                            .filter(l -> l.strip().matches("\\d+"))
                            .findFirst()
                            .orElse("0")
                            .strip()))
                    .as("name servers, else npm fails with EAI_AGAIN")
                    .isPositive();
            assertThat(on.out().strip().lines().skip(1).filter(l -> l.startsWith("/")))
                    .as("no /mnt, no /run on a native root")
                    .isEmpty();
            return;
        }

        assertThat(on.code()).as(on.out()).isZero();
        String[] lines = on.out().strip().split("\\R");
        assertThat(Integer.parseInt(lines[0].strip()))
                .as("name servers, else npm fails with EAI_AGAIN")
                .isPositive();
        // WSL links /etc/resolv.conf to /mnt/wsl/resolv.conf: only that file comes back, never a drive.
        assertThat(java.util.Arrays.copyOfRange(lines, 1, lines.length))
                .allMatch(l -> l.equals("/mnt/wsl") || l.equals("/mnt/wsl/resolv.conf"), "only the resolver file");
    }

    /** Network off: lo is up in the sandbox's own namespace (a test may serve itself), the host's loopback is not. */
    @Test
    void theSandboxHasAPrivateLoopbackAndTheHostsLoopbackIsOutOfReach() throws Exception {
        Path work = Files.createDirectories(data.resolve("work"));
        try (java.net.ServerSocket host = new java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())) {
            String probe = "python3 -I -c 'import socket\n"
                    + "def tcp(): return socket.socket(socket.AF_INET, socket.SOCK_STREAM)\n"
                    + "h = tcp(); h.settimeout(3)\n"
                    + "try: h.connect((\"127.0.0.1\", " + host.getLocalPort() + ")); print(\"LEAK host-loopback\")\n"
                    + "except OSError as e: print(\"host-refused\")\n"
                    + "s = tcp(); s.bind((\"127.0.0.1\", 0)); s.listen(1)\n"
                    + "c = tcp(); c.settimeout(3)\n"
                    + "c.connect((\"127.0.0.1\", s.getsockname()[1])); print(\"own-loopback-works\")'\n"
                    + "echo probe-end\n";

            Result r = run(sandbox(60, 2048, 4_294_967_296L).argv(work, probe), 120);

            assertThat(r.out()).contains("host-refused", "own-loopback-works", "probe-end");
            assertThat(r.out()).doesNotContain("LEAK");
        }
    }

    private Sandbox sandbox(long seconds, long processes, long fileBytes) {
        return sandbox(seconds, processes, fileBytes, false);
    }

    private Sandbox sandbox(long seconds, long processes, long fileBytes, boolean network) {
        Sandbox s = new Sandbox();
        s.policy = new SandboxPolicy() {
            @Override
            public boolean sandboxEnabled() {
                return true;
            }

            @Override
            public boolean sandboxNetwork() {
                return network;
            }
        };
        s.distro = "Debian";
        s.dataDir = data;
        s.timeoutSeconds = seconds;
        s.maxProcesses = processes;
        s.maxFileBytes = fileBytes;
        return s;
    }

    record Result(int code, String out) {}

    private static Result run(List<String> argv, int seconds) {
        return run(argv, seconds, java.util.Map.of());
    }

    /** Runs {@code argv} with {@code extra} added to this process's environment, as wsl.exe would receive it. */
    private static Result run(List<String> argv, int seconds, java.util.Map<String, String> extra) {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            pb.environment().putAll(extra);
            Process p = pb.start();
            p.getOutputStream().close();
            var out = new java.io.ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    p.getInputStream().transferTo(out);
                } catch (IOException e) {
                    // Handled: the output read so far is kept
                }
            });
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) p.destroyForcibly();
            reader.join(5000);
            return new Result(p.isAlive() ? -1 : p.exitValue(), out.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Handled: the test reads a failed start as a failed run
            return new Result(-1, e.getMessage());
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept; the run reads as failed
            Thread.currentThread().interrupt();
            return new Result(-1, "interrupted");
        }
    }
}
