package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The argument lists the sandbox hands to its launcher, and what it remembers of its own measure. */
class SandboxArgvTest {

    @TempDir
    Path data;

    private Sandbox sandbox(String os, boolean network) {
        Sandbox s = SandboxProbeTest.probe(data).sandbox;
        s.osName = os;
        s.assumed = Optional.empty();
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
        return s;
    }

    private Path plainRepo() throws Exception {
        Path repo = Files.createDirectory(data.resolve("repo"));
        assertThat(GitProcess.git(repo, List.of("init", "-q", "-b", "main")).code())
                .isZero();
        return repo;
    }

    @Test
    void onLinuxTheListIsTheScriptsOwnArgumentsAndOnWindowsWslExeComesFirst() throws Exception {
        Path work = Files.createDirectory(data.resolve("work"));

        List<String> linux = sandbox("Linux", false).argv(work, "echo hi");
        assertThat(linux.getFirst()).isEqualTo("sh");
        assertThat(linux.get(1)).endsWith("/sandbox/run.sh");
        assertThat(linux.get(3)).isEqualTo(Sandbox.b64("echo hi"));
        assertThat(linux.get(4)).isEqualTo("off");

        List<String> windows = sandbox("Windows 11", false).argv(work, "echo hi");
        assertThat(windows.subList(0, 5)).containsExactly("wsl.exe", "-d", "Debian", "--exec", "sh");
    }

    @Test
    void theNetworkIsOnOnlyWhenTheDirectorOpenedItAndTheShellIsNotReadOnly() throws Exception {
        Path work = Files.createDirectory(data.resolve("work"));
        Sandbox open = sandbox("Linux", true);

        assertThat(open.argv(work, "x", 30, false).get(4)).isEqualTo("on");
        assertThat(open.argv(work, "x", 30, true).get(4)).isEqualTo("off");
        assertThat(open.argv(work, "x", 30, true).getLast()).isEqualTo("ro");
        assertThat(open.argv(work, "x", 30, false).getLast()).isEqualTo("rw");
        assertThat(open.argvOffline(work, "x", 30).get(4))
                .as("a proof is offline")
                .isEqualTo("off");
        assertThat(open.argvOffline(work, "x", 30).subList(5, 8))
                .as("nothing of git is shared with a proof")
                .containsExactly("none", "none", "none");
    }

    @Test
    void theLimitsAreAtLeastOneWhateverTheyAreSetTo() throws Exception {
        Path work = Files.createDirectory(data.resolve("work"));
        Sandbox s = sandbox("Linux", false);
        s.maxProcesses = 0;
        s.maxFileBytes = -5;

        List<String> argv = s.argv(work, "x", 0);

        assertThat(argv.subList(argv.size() - 4, argv.size() - 1)).containsExactly("1", "1", "1");
        s.maxProcesses = 512;
        s.maxFileBytes = 1_000_000;
        List<String> set = s.argv(work, "x", 45);
        assertThat(set.subList(set.size() - 4, set.size() - 1)).containsExactly("45", "512", "1000000");
    }

    @Test
    void aProofCopyOfAPlainRepositoryMountsTheSourcesGitFolderReadOnlyUnderTheNameDash() throws Exception {
        Path source = plainRepo();
        Path copy = Files.createDirectory(data.resolve("copy"));
        Sandbox s = sandbox("Linux", true);

        List<String> argv = s.argvOffline(copy, "make", 30, source);

        assertThat(argv).contains(Sandbox.linux(source.resolve(".git")), "-");
        assertThat(argv.get(4)).isEqualTo("off");
        assertThat(argv).contains("proof");

        Path noGit = Files.createDirectory(data.resolve("nogit"));
        List<String> without = s.argvOffline(copy, "make", 30, noGit);
        assertThat(without.subList(5, 7)).containsExactly("none", "none");
        assertThat(without).doesNotContain("-");
    }

    @Test
    void theDirectorsAutocrlfIsReadAsGitGivesItAndAnythingElseIsNone() throws Exception {
        Path repo = plainRepo();
        GitProcess.git(repo, List.of("config", "core.autocrlf", "TRUE"));
        assertThat(WorktreeGitDirs.autocrlf(repo)).isEqualTo("true");
        GitProcess.git(repo, List.of("config", "core.autocrlf", "false"));
        assertThat(WorktreeGitDirs.autocrlf(repo)).isEqualTo("false");
        assertThat(WorktreeGitDirs.autocrlf(data.resolve("does-not-exist"))).isEqualTo("none");
    }

    @Test
    void anInterruptedReadOfTheSettingKeepsTheFlagForTheCaller() throws Exception {
        Path repo = plainRepo();
        GitProcess.git(repo, List.of("config", "core.autocrlf", "true"));
        Thread.currentThread().interrupt();
        try {
            assertThat(WorktreeGitDirs.autocrlf(repo)).isEqualTo("none");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void theSandboxIsMeasuredOnceThenRememberedUntilRecheckedAndAnAssumptionIsTaken() {
        Sandbox s = sandbox("Mac OS X", false);
        assertThat(s.available()).as("no backend here").isFalse();
        s.setAvailable(true);
        assertThat(s.available()).isTrue();
        assertThat(s.recheck()).as("measured again").isFalse();
        assertThat(s.probeFailure()).isNull();

        s.assumed = Optional.of(true);
        assertThat(s.available()).as("a measure already made is kept").isFalse();
        assertThat(s.recheck()).isTrue();
        s.setAvailable(false);
        assertThat(s.available())
                .as("what was measured wins over an assumption")
                .isFalse();
    }

    @Test
    void aProbeThatCannotRunSaysWhyAndFailsClosed() throws Exception {
        Path notAFolder = Files.writeString(data.resolve("data-file"), "x");
        Sandbox s = sandbox("Linux", false);
        s.dataDir = notAFolder;

        assertThat(s.available()).isFalse();
        assertThat(s.probeFailure()).isNotBlank();
    }

    @Test
    void aProbeRunCountsOnlyWhenItExitedZeroInTimeWithTheExpectedWord() {
        String ok = "floor42-sandbox-ok\n";
        assertThat(Sandbox.works(new SandboxProbe.RunResult(ok, 0, false))).isTrue();
        assertThat(Sandbox.works(new SandboxProbe.RunResult(ok, 1, false))).isFalse();
        assertThat(Sandbox.works(new SandboxProbe.RunResult(ok, null, false))).isFalse();
        assertThat(Sandbox.works(new SandboxProbe.RunResult(ok, 0, true))).isFalse();
        assertThat(Sandbox.works(new SandboxProbe.RunResult("nope", 0, false))).isFalse();
    }
}
