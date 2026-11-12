package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sandbox on native Linux (ADR-0028, amended 2026-11-09): the host is not Windows, so what must stay out of reach is
 * the whole host, not a list of drives. The test plants host-only files where no mask of the old script reached (/srv,
 * /opt, in a mount namespace of its own: the real host is never written), opens a loopback port and an abstract unix
 * socket on the host, then runs probes inside the sandbox. Every probe prints LEAK when it got through.
 */
class SandboxNativeEscapeTest {

    @TempDir
    Path data;

    @BeforeAll
    static void nativeLinux() {
        assumeTrue(SandboxHost.linux(SandboxHost.current()), "the native backend runs on Linux only");
        WslSandbox.platformOrSkip();
    }

    /** Planted in a private mount namespace: tmpfs over /srv and /opt, holding what a data directory would. */
    private static final String PLANT =
            "mount -t tmpfs tmpfs /srv && mkdir /srv/data && echo SECRET > /srv/data/vault.key"
                    + " && mount -t tmpfs tmpfs /opt && echo SECRET > /opt/host-file && exec \"$@\"";

    static final String FILES = """
            leak() { echo "LEAK $*"; }
            [ -e /srv/data/vault.key ] && leak vault-key
            [ -e /opt/host-file ] && leak opt-host-file
            [ -n "$(ls -A /srv 2>/dev/null)" ] && leak srv-listing
            [ -e /etc/hostname ] && leak etc-hostname
            [ -e /etc/machine-id ] && leak etc-machine-id
            [ -e /etc/shadow ] && leak etc-shadow
            [ -e /etc/ssh ] && leak etc-ssh
            [ -n "$(ls -A /var/log 2>/dev/null)" ] && leak var-log
            [ -e /var/lib/dpkg/status ] && leak var-lib
            # Only the toolchain and cache folders the daemon names (ToolchainMounts) may be below a home folder.
            for u in /home/*; do [ -d "$u" ] && ls -A "$u" | grep -qvE '^(\\.m2|\\.npm|\\.sdkman|\\.nvm)$' && leak "home $u"; done
            [ -e /home/*/.m2/settings.xml ] && leak m2-settings
            [ -e /home/*/.npmrc ] && leak npmrc
            [ -e /boot ] && [ -n "$(ls -A /boot 2>/dev/null)" ] && leak boot
            [ -e /proc/1/root/srv/data/vault.key ] && leak proc-root
            for f in /srv/f42 /opt/f42 /usr/f42 /etc/f42 /var/f42 /f42; do (: > "$f") 2>/dev/null && leak "write $f"; done
            for d in /usr /bin /lib; do touch "$d/f42" 2>/dev/null && leak "write $d"; done
            echo inside > inside.txt
            git status > /dev/null 2>&1 || echo "BROKEN git"
            node -v > /dev/null 2>&1 || echo "BROKEN node"
            python3 -c 1 || echo "BROKEN python3"
            (: > /tmp/f42) || echo "BROKEN tmp"
            echo probe-end
            """;

    @Test
    void nothingOfTheHostOutsideTheWorktreeIsReachableOrWritable() throws Exception {
        Path work = repository();
        List<String> argv = planted(sandbox(false).argv(work, FILES, 120));

        Result r = run(argv, 180);

        assertThat(r.out()).as("the probes ran to their end").contains("probe-end");
        assertThat(r.out()).as("a way out is open").doesNotContain("LEAK");
        assertThat(r.out()).as("a tool a shell needs is gone").doesNotContain("BROKEN");
        assertThat(work.resolve("inside.txt")).as("the worktree stays writable").exists();
    }

    /** Network off: lo is up in the sandbox's own namespace, the host's loopback is not reachable. */
    @Test
    void theSandboxHasAPrivateLoopbackAndTheHostsLoopbackIsOutOfReach() throws Exception {
        try (ServerSocket host = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try {
                    while (true) host.accept().close();
                } catch (IOException e) {
                    // Handled: the test ended
                }
            });
            String probe = "python3 -I -c \"import socket\n"
                    + "def tcp(): return socket.socket(socket.AF_INET, socket.SOCK_STREAM)\n"
                    + "h = tcp(); h.settimeout(3)\n"
                    + "try:\n h.connect(('127.0.0.1', " + host.getLocalPort() + ")); print('LEAK host-loopback')\n"
                    + "except OSError: print('host-refused')\n"
                    + "s = tcp(); s.bind(('127.0.0.1', 0)); s.listen(1)\n"
                    + "c = tcp(); c.settimeout(3)\n"
                    + "c.connect(('127.0.0.1', s.getsockname()[1])); print('own-loopback-works')\"\necho probe-end";

            Result r = run(planted(sandbox(false).argv(Files.createDirectories(data.resolve("lo")), probe, 60)), 120);

            assertThat(r.out()).contains("host-refused", "own-loopback-works", "probe-end");
            assertThat(r.out()).doesNotContain("LEAK");
        }
    }

    /** The host's Maven cache is a read-only tail; nothing else of ~/.m2 is shown. Creates nothing in the real ~/.m2. */
    @Test
    void theMavenCacheIsAReadOnlyTailAndSettingsAndWrapperAreNotVisible() throws Exception {
        Path m2 = Path.of(System.getProperty("user.home"), ".m2");
        assumeTrue(Files.isDirectory(m2.resolve("repository")), "no ~/.m2/repository on this host: nothing to share");
        Path repository = m2.resolve("repository").toRealPath();
        String probe = "t=${MAVEN_ARGS#-Dmaven.repo.local.tail=}\n"
                + "echo args=$MAVEN_ARGS\n"
                + "[ -d \"$t\" ] && ls \"$t\" >/dev/null && echo tail-readable\n"
                + "(: > \"$t/f42-write\") 2>/dev/null && echo LEAK tail-writable\n"
                + "mkdir \"$t/f42-dir\" 2>/dev/null && echo LEAK tail-mkdir\n"
                + "[ -e \"$t/../settings.xml\" ] && echo LEAK settings\n"
                + "[ -e \"$t/../settings-security.xml\" ] && echo LEAK settings-security\n"
                + "[ -e \"$t/../wrapper\" ] && echo LEAK wrapper\n"
                + "echo probe-end\n";

        Result r = run(planted(sandbox(false).argv(Files.createDirectories(data.resolve("m2")), probe, 60)), 120);

        assertThat(r.out()).contains("args=-Dmaven.repo.local.tail=" + repository, "tail-readable", "probe-end");
        assertThat(r.out()).doesNotContain("LEAK");
        assertThat(repository.resolve("f42-write")).doesNotExist();
        assertThat(repository.resolve("f42-dir")).doesNotExist();
    }

    /** What a TCP service or a desktop on the host would offer: a loopback port and an abstract unix socket. */
    @Test
    void withTheNetworkOnTheHostsLoopbackAndAbstractSocketsAreOutOfReach() throws Exception {
        assumeTrue(
                pasta(), "pasta is not installed (sudo apt install passt): network-on isolation is not measured here");
        String abstractName = "f42-probe-" + ProcessHandle.current().pid();
        Process holder = new ProcessBuilder(
                        "python3",
                        "-c",
                        "import socket,time; s=socket.socket(socket.AF_UNIX); s.bind('\\0" + abstractName
                                + "'); s.listen(5); time.sleep(120)")
                .redirectErrorStream(true)
                .start();
        // The ports of Floor42's daemons (7420, 7421) and of Keycloak (8180) when they are free, and any other port:
        // each is held open on the host's loopback, and each must be unreachable from inside.
        List<ServerSocket> held = new ArrayList<>();
        try {
            for (int port : new int[] {7420, 7421, 8180, 0}) {
                try {
                    held.add(new ServerSocket(port, 5, InetAddress.getLoopbackAddress()));
                } catch (IOException busy) {
                    // Handled: the port is in use by the real service on this machine, which is as good a target
                }
            }
            for (ServerSocket s : held) {
                Thread.ofVirtual().start(() -> {
                    try {
                        while (true) s.accept().close();
                    } catch (IOException e) {
                        // Handled: the test ended
                    }
                });
            }
            Thread.sleep(500);
            String ports = "7420, 7421, 8180"
                    + held.stream().map(s -> ", " + s.getLocalPort()).reduce("", String::concat);
            // The gateway pasta sets up is where a host's loopback would be mapped (never, with --no-map-gw).
            String probe = "python3 -I -c \"import socket, struct\n"
                    + "hosts = ['127.0.0.1']\n"
                    + "for line in open('/proc/net/route').read().split('\\n')[1:]:\n f = line.split()\n"
                    + " if len(f) > 2 and f[1] == '00000000': hosts.append(socket.inet_ntoa(struct.pack('<I', int(f[2], 16))))\n"
                    + "for h in hosts:\n for p in [" + ports + "]:\n"
                    + "  s = socket.socket(); s.settimeout(2)\n"
                    + "  try:\n   s.connect((h, p)); print('LEAK loopback', h, p)\n  except OSError: pass\n"
                    + "u = socket.socket(socket.AF_UNIX); u.settimeout(3)\n"
                    + "try:\n u.connect('\\0" + abstractName + "'); print('LEAK abstract')\n"
                    + "except OSError: pass\"\necho probe-end";
            Result r = run(planted(sandbox(true).argv(Files.createDirectories(data.resolve("net")), probe, 60)), 120);

            assertThat(r.out()).as("a way out of the network namespace").doesNotContain("LEAK");
            assertThat(r.out()).as("the probes ran to their end").contains("probe-end");
        } finally {
            held.forEach(s -> {
                try {
                    s.close();
                } catch (IOException e) {
                    // Handled: closing at the end of the test
                }
            });
            holder.destroyForcibly();
        }
    }

    /** Through pasta an outside host resolves and answers, when this machine itself reaches it (else not measured). */
    @Test
    void withPastaAnOutsideHostResolvesAndAnswers() throws Exception {
        assumeTrue(pasta(), "pasta is not installed (sudo apt install passt): network-on is not measured here");
        try (var s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("example.com", 443), 5000);
        } catch (IOException offline) {
            assumeTrue(false, "this machine has no outside network (" + offline.getMessage() + "): not measured");
        }
        String probe = "getent hosts example.com > /dev/null && echo dns-ok\n"
                + "python3 -I -c \"import socket; socket.create_connection(('example.com', 443), 8).close(); print('tcp-ok')\"\n"
                + "echo probe-end";

        Result r = run(sandbox(true).argv(Files.createDirectories(data.resolve("net2")), probe, 60), 90);

        assertThat(r.out()).contains("dns-ok", "tcp-ok", "probe-end");
    }

    /** Without pasta network access is refused, never given on the host's network (the only case this test runs in). */
    @Test
    void withoutPastaNetworkOnIsRefusedWithTheWayToGetIt() throws Exception {
        assumeTrue(
                !pasta(), "pasta is installed: the refusal is not reachable (the isolation test above runs instead)");

        Result r = run(sandbox(true).argv(Files.createDirectories(data.resolve("net")), "echo ran", 60), 60);

        assertThat(r.code()).isEqualTo(126);
        assertThat(r.out()).contains("sudo apt install passt").doesNotContain("ran\n");
    }

    private static boolean pasta() {
        return NativeChecks.measure().pasta();
    }

    /**
     * A dependency folder is opened and mounted at the point of use: a path that was a folder when the daemon looked
     * and is a link to the host's home when the sandbox starts shows nothing of the host.
     */
    @Test
    void aNodeModulesSwappedForALinkAfterTheWalkIsNeverFollowed() throws Exception {
        Path source = Files.createDirectories(data.resolve("source"));
        Files.createDirectories(source.resolve("node_modules"));
        Files.writeString(source.resolve("node_modules/dep.js"), "x");
        Path home = Files.createDirectories(data.resolve("fakehome"));
        Files.writeString(home.resolve("HOST-SECRET.txt"), "HOST-HOME-SECRET");
        Path copy = Files.createDirectories(data.resolve("copy"));
        List<String> argv =
                sandbox(false).argvOffline(copy, "ls node_modules; cat node_modules/HOST-SECRET.txt", 60, source);
        assertThat(argv).as("the walk saw the folder").anyMatch(a -> a.equals("node_modules"));

        Files.delete(source.resolve("node_modules/dep.js"));
        Files.delete(source.resolve("node_modules"));
        Files.createSymbolicLink(source.resolve("node_modules"), home);
        Result r = run(argv, 90);

        assertThat(r.out()).as("the host's folder is not shown").doesNotContain("HOST-SECRET", "HOST-HOME-SECRET");
        assertThat(r.out()).as("refused with its reason").contains("refused");
    }

    /** The target in the copy is walked the same way: a link in the copy never sends a mount (or a folder) outside. */
    @Test
    void aLinkInTheCopyNeverSendsADependencyMountOutside() throws Exception {
        Path source = Files.createDirectories(data.resolve("source2/deps/node_modules"));
        Files.writeString(source.resolve("dep.js"), "x");
        Path outside = Files.createDirectories(data.resolve("outside"));
        Path copy = Files.createDirectories(data.resolve("copy2"));
        Files.createSymbolicLink(copy.resolve("deps"), outside);
        List<String> argv = sandbox(false).argvOffline(copy, "ls deps", 60, data.resolve("source2"));
        assertThat(argv).anyMatch(a -> a.equals("deps/node_modules"));

        Result r = run(argv, 90);

        assertThat(outside.resolve("node_modules")).as(r.out()).doesNotExist();
        assertThat(r.out()).contains("refused");
    }

    /** What is mounted below a read-only system folder (a data disk under /usr, the VM's drivers) is read-only too. */
    @Test
    void aMountBelowASystemFolderIsReadOnlyToo() throws Exception {
        Path under = List.of(Path.of("/usr/games"), Path.of("/usr/src"), Path.of("/usr/local/src")).stream()
                .filter(Files::isDirectory)
                .findFirst()
                .orElse(null);
        assumeTrue(under != null, "no empty folder below /usr to plant a mount on");
        String plant = "mount -t tmpfs tmpfs " + under + " && exec \"$@\"";
        List<String> argv = new ArrayList<>(List.of("unshare", "-Urm", "sh", "-c", plant, "sh"));
        argv.addAll(sandbox(false)
                .argv(
                        Files.createDirectories(data.resolve("ro")),
                        "(: > " + under + "/f42) 2>/dev/null && echo LEAK writable-submount; echo probe-end",
                        60));

        Result r = run(argv, 90);

        assertThat(r.out()).contains("probe-end").doesNotContain("LEAK");
    }

    /** The helper that mounts toolchains: only the named folders, read-only, at their own path, nothing around them. */
    @Test
    void theToolchainHelperMountsOnlyTheNamedFoldersReadOnly() throws Exception {
        Path home = Files.createDirectories(data.resolve("home"));
        Path repo = Files.createDirectories(home.resolve(".m2/repository"));
        Files.writeString(repo.resolve("artifact.jar"), "jar");
        Files.writeString(home.resolve(".m2/settings.xml"), "<password>HOST-SECRET</password>");
        Files.writeString(home.resolve(".npmrc"), "//registry/:_authToken=HOST-SECRET");
        Path listing = Files.writeString(data.resolve("toolchain.list"), "dir " + repo + "\n");
        Path helper = Files.writeString(data.resolve("sandbox-mounts.py"), Sandbox.resource("sandbox-mounts.py"));
        Path root = Files.createDirectories(data.resolve("newroot"));
        Path stage = Files.createDirectories(data.resolve("stage"));
        String script = "mkdir -p " + repo + "/sub && mount -t tmpfs tmpfs " + repo + "/sub && mount -t tmpfs tmpfs "
                + root
                + " && python3 -I " + helper + " tools " + listing + " " + root
                + " " + stage
                + " && ls " + root + home + "/.m2 && (: > " + root + repo + "/f42) 2>/dev/null && echo LEAK writable;"
                + " (: > " + root + repo + "/sub/f42) 2>/dev/null && echo LEAK sub-writable;"
                + " cat " + root + repo + "/artifact.jar; echo probe-end";

        Result r = run(List.of("unshare", "-Urm", "sh", "-c", script), 60);

        assertThat(r.out()).contains("repository", "jar", "probe-end");
        assertThat(r.out()).doesNotContain("settings.xml", ".npmrc", "HOST-SECRET", "LEAK");
    }

    /** A mount that can be neither made read-only nor detached stops the command from starting. */
    @Test
    void theMountHelperFailsClosedWhenAMountCannotBeMadeReadOnly() throws Exception {
        Path helper = Files.writeString(data.resolve("sandbox-mounts.py"), Sandbox.resource("sandbox-mounts.py"));
        Path top = Files.createDirectories(data.resolve("ro-top"));
        String python = "import importlib.util\n"
                + "spec = importlib.util.spec_from_file_location('m', '" + helper + "')\n"
                + "m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)\n"
                + "class L:\n def mount(self, *a): return -1\n def umount2(self, *a): return -1\n"
                + "m.libc = L()\n"
                + "try:\n m.read_only('" + top + "'); print('NOT-REFUSED')\n"
                + "except SystemExit as e: print('REFUSED', e)\n";
        Path script = Files.writeString(data.resolve("failclosed.py"), python);
        String sh = "mkdir -p " + top + "/sub && mount -t tmpfs tmpfs " + top + "/sub && python3 -I " + script;

        Result r = run(List.of("unshare", "-Urm", "sh", "-c", sh), 60);

        assertThat(r.out()).contains("REFUSED").doesNotContain("NOT-REFUSED");
    }

    /** Runs the mount helper's "tools" mode in a mount namespace of its own: the root it builds, and what is in it. */
    private Result tools(String listing, Path root, Path stage, String then, java.util.Map<String, String> env)
            throws Exception {
        Path helper = Files.writeString(data.resolve("sandbox-mounts.py"), Sandbox.resource("sandbox-mounts.py"));
        Path list = Files.writeString(data.resolve("toolchain-" + root.getFileName() + ".list"), listing);
        String sh = "mount -t tmpfs tmpfs " + root + " && python3 -I " + helper + " tools " + list + " " + root + " "
                + stage + "; " + then;
        return run(List.of("unshare", "-Urm", "sh", "-c", sh), 60, env);
    }

    /** Credentials inside a mounted toolchain root are masked (defence in depth): empty and read-only. */
    @Test
    void credentialFilesInsideAToolchainRootAreMasked() throws Exception {
        Path tc = Files.createDirectories(data.resolve("tc/maven-3.9"));
        for (String secret : List.of(
                "etc/npmrc",
                "conf/settings.xml",
                "lib/pip.conf",
                "keys/server.pem",
                "ssh/id_rsa_work",
                ".docker/config.json",
                "deep/a/b/credentials",
                "cfg/gradle.properties",
                "x/.netrc")) {
            Files.createDirectories(tc.resolve(secret).getParent());
            Files.writeString(tc.resolve(secret), "HOST-SECRET");
        }
        Files.createDirectories(tc.resolve("bin"));
        Files.writeString(tc.resolve("bin/mvn"), "TOOL-OK");
        Path root = Files.createDirectories(data.resolve("root-mask"));
        String then = "cd " + root + tc + " && cat bin/mvn etc/npmrc conf/settings.xml lib/pip.conf keys/server.pem"
                + " ssh/id_rsa_work .docker/config.json deep/a/b/credentials cfg/gradle.properties x/.netrc;"
                + " (echo x >> etc/npmrc) 2>/dev/null && echo LEAK writable-mask; echo probe-end";

        Result r = tools(
                "dir " + tc + "\n",
                root,
                Files.createDirectories(data.resolve("stage-mask")),
                then,
                java.util.Map.of());

        assertThat(r.out()).contains("TOOL-OK", "probe-end").doesNotContain("HOST-SECRET", "LEAK");
    }

    /** Case does not matter, nor does a backup suffix, and the longer list of key and credential names is masked. */
    @Test
    void maskMatchingIgnoresCaseCoversBackupsAndTheLongerList() throws Exception {
        Path tc = Files.createDirectories(data.resolve("tc-case/node-20"));
        List<String> names = List.of(
                "SERVER.PEM",
                "Id_rsa",
                "NPMRC",
                "Settings.xml",
                ".NETRC",
                "x/Config.json",
                "id_ecdsa",
                "id_dsa.pub",
                "a.pfx",
                "b.ppk",
                ".pgpass",
                ".env",
                ".env.production",
                "auth.json",
                ".yarnrc.yml",
                ".condarc",
                "npmrc.bak",
                "settings.xml~",
                "id_rsa.old");
        for (String n : names) {
            Path f = tc.resolve(n.equals("x/Config.json") ? "x/.DOCKER/Config.json" : n);
            Files.createDirectories(f.getParent());
            Files.writeString(f, "HOST-SECRET");
        }
        Files.createDirectories(tc.resolve("bin"));
        Files.writeString(tc.resolve("bin/node"), "TOOL-OK");
        Path root = Files.createDirectories(data.resolve("root-case"));
        String cats = names.stream()
                .map(n -> n.equals("x/Config.json") ? "x/.DOCKER/Config.json" : n)
                .map(n -> "cat " + root + tc + "/" + n + ";")
                .reduce("", String::concat);

        Result r = tools(
                "dir " + tc + "\n",
                root,
                Files.createDirectories(data.resolve("stage-case")),
                cats + " cat " + root + tc + "/bin/node; echo probe-end",
                java.util.Map.of());

        assertThat(r.out()).contains("TOOL-OK", "probe-end").doesNotContain("HOST-SECRET");
    }

    /** A host mount that is strictatime and nodiratime (and noexec) must not make the read-only remount fail. */
    @Test
    void aStrictatimeNodiratimeHostMountDoesNotStopTheMounts() throws Exception {
        Path helper = Files.writeString(data.resolve("sandbox-mounts.py"), Sandbox.resource("sandbox-mounts.py"));
        Path host = Files.createDirectories(data.resolve("atime-home"));
        Path stage = Files.createDirectories(data.resolve("stage-at"));
        Path root = Files.createDirectories(data.resolve("root-at"));
        Path list = data.resolve("at.list");
        String inner = "mount -t tmpfs tmpfs " + root + " && python3 -I " + helper + " tools " + list + " " + root + " "
                + stage + " && cat " + root + host + "/tc/f";
        String outer =
                "mount -t tmpfs -o strictatime,nodiratime,nodev,nosuid,noexec tmpfs " + host + " && mkdir " + host
                        + "/tc && echo TOOL-OK > " + host + "/tc/f && echo 'dir " + host + "/tc' > " + list
                        + " && unshare -Urm sh -c '" + inner + "'";

        Result r = run(List.of("unshare", "-Urm", "sh", "-c", outer), 60);

        assertThat(r.out()).contains("TOOL-OK").doesNotContain("Operation not permitted", "Traceback");
    }

    /** An unreadable folder inside a root means that root is not shown, with the reason: never a crash of the helper. */
    @Test
    void anUnreadableFolderInAToolchainRootIsNotShownAndDoesNotStopTheOthers() throws Exception {
        Path bad = Files.createDirectories(data.resolve("tc-bad/jdk-1/inner"));
        Path good = Files.createDirectories(data.resolve("tc-good/jdk-2"));
        Files.writeString(good.resolve("f"), "GOOD-OK");
        Path helper = Files.writeString(data.resolve("sandbox-mounts.py"), Sandbox.resource("sandbox-mounts.py"));
        Path list = Files.writeString(data.resolve("bad.list"), "dir " + bad.getParent() + "\ndir " + good + "\n");
        Path root = Files.createDirectories(data.resolve("root-bad"));
        Path stage = Files.createDirectories(data.resolve("stage-bad"));
        // The folder is made unreadable by the helper's own view of it (a user namespace root can read any file it
        // owns).
        String python = "import importlib.util, os\n"
                + "spec = importlib.util.spec_from_file_location('m', '" + helper + "')\n"
                + "m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)\n"
                + "real = os.scandir\n"
                + "def scan(p):\n"
                + "    if str(p).endswith('inner'): raise PermissionError(13, 'Permission denied', str(p))\n"
                + "    return real(p)\n"
                + "os.scandir = scan\n"
                + "m.tools('" + list + "', '" + root + "', '" + stage + "')\n";
        Path script = Files.writeString(data.resolve("unreadable.py"), python);
        String sh = "mount -t tmpfs tmpfs " + root + " && python3 -I " + script + "; echo rc=$?; cat " + root + good
                + "/f; ls " + root + bad.getParent() + " 2>&1; echo probe-end";

        Result r = run(List.of("unshare", "-Urm", "sh", "-c", sh), 60);

        assertThat(r.out())
                .contains("rc=0", "GOOD-OK", "not shown", "cannot be read", "probe-end")
                .doesNotContain("Traceback");
    }

    /** A root with more entries than the cap is not shown at all, and the reason is printed. */
    @Test
    void aToolchainRootThatCannotBeWalkedWithinTheCapIsNotMounted() throws Exception {
        Path tc = Files.createDirectories(data.resolve("tc-big/jdk"));
        for (int i = 0; i < 12; i++) Files.writeString(tc.resolve("f" + i), "x");
        Path root = Files.createDirectories(data.resolve("root-big"));

        Result r = tools(
                "dir " + tc + "\n",
                root,
                Files.createDirectories(data.resolve("stage-big")),
                "ls " + root + tc + " 2>&1; echo probe-end",
                java.util.Map.of("F42_MASK_CAP", "5"));

        assertThat(r.out()).contains("too many files", "not shown", "probe-end").doesNotContain("f1\n");
        // The depth cap says so in its own words.
        Path deep = data.resolve("tc-deep/jdk");
        Files.createDirectories(deep.resolve("a/b/c/d/e/f/g/h/i/j/k/l/m/n/o/p/q/r"));
        Result d = tools(
                "dir " + deep + "\n",
                Files.createDirectories(data.resolve("root-deep")),
                Files.createDirectories(data.resolve("stage-deep")),
                "echo probe-end",
                java.util.Map.of());
        assertThat(d.out()).contains("too deep", "not shown").doesNotContain("too many files");
    }

    /** A home on a nodev,nosuid,noexec filesystem (flags locked in a nested user namespace) must not stop the mounts. */
    @Test
    void aToolchainOnANoexecFilesystemIsStillMountedReadOnly() throws Exception {
        Path helper = Files.writeString(data.resolve("sandbox-mounts.py"), Sandbox.resource("sandbox-mounts.py"));
        Path host = Files.createDirectories(data.resolve("noexec-home"));
        Path stage = Files.createDirectories(data.resolve("stage-nx"));
        Path root = Files.createDirectories(data.resolve("root-nx"));
        Path list = data.resolve("nx.list");
        String inner = "mount -t tmpfs tmpfs " + root + " && python3 -I " + helper + " tools " + list + " " + root + " "
                + stage + " && cat " + root + host + "/tc/f && (: > " + root + host
                + "/tc/new) 2>/dev/null && echo LEAK rw";
        String outer = "mount -t tmpfs -o nodev,nosuid,noexec tmpfs " + host + " && mkdir " + host
                + "/tc && echo TOOL-OK > " + host + "/tc/f && echo 'dir " + host + "/tc' > " + list
                + " && unshare -Urm sh -c '" + inner + "'";

        Result r = run(List.of("unshare", "-Urm", "sh", "-c", outer), 60);

        assertThat(r.out()).contains("TOOL-OK").doesNotContain("LEAK", "Operation not permitted");
    }

    private Path repository() throws Exception {
        Path work = Files.createDirectories(data.resolve("work"));
        for (List<String> git : List.of(List.of("init", "-q"), List.of("commit", "-q", "--allow-empty", "-m", "x"))) {
            List<String> cmd =
                    new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@t", "-C", work.toString()));
            cmd.addAll(git);
            assertThat(new ProcessBuilder(cmd).inheritIO().start().waitFor()).isZero();
        }
        return work;
    }

    /** The sandbox's argv, run from a mount namespace in which the host files above exist. */
    private static List<String> planted(List<String> sandboxArgv) {
        List<String> argv = new ArrayList<>(List.of("unshare", "-Urm", "sh", "-c", PLANT, "sh"));
        argv.addAll(sandboxArgv);
        return argv;
    }

    private Sandbox sandbox(boolean network) {
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
        s.timeoutSeconds = 120;
        s.maxProcesses = 2048;
        s.maxFileBytes = 4_294_967_296L;
        return s;
    }

    record Result(int code, String out) {}

    private static Result run(List<String> argv, int seconds) throws Exception {
        return run(argv, seconds, java.util.Map.of());
    }

    private static Result run(List<String> argv, int seconds, java.util.Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        pb.environment().putAll(env);
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
    }
}
