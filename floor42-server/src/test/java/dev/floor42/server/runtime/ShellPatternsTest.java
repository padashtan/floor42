package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The guard reads command lines an agent wrote: a hostile line must neither stall it (ReDoS) nor overflow its stack,
 * and it must still refuse everything it refused before.
 */
class ShellPatternsTest {

    private static final int BIG = 50_000;
    // The bugs this guards took tens of seconds; the gate runs under load.
    private static final Duration BUDGET = Duration.ofSeconds(10);

    private static <T> T within(Supplier<T> call) {
        return assertTimeoutPreemptively(BUDGET, call::get);
    }

    @Test
    void aLongRunOfGitOptionsIsReadInLinearTime() {
        assertThat(within(() -> ShellPatterns.gitPush("git" + " -c".repeat(BIG / 3) + " x")))
                .isFalse();
        assertThat(within(() -> ShellPatterns.gitPush("git" + " -a".repeat(BIG / 3) + " x")))
                .isFalse();
        assertThat(within(() -> ShellPatterns.gitPush("git -c -c ".repeat(BIG / 10) + "x")))
                .isFalse();
        assertThat(within(() -> ShellPatterns.gitPush("git -a" + " ".repeat(BIG) + "x")))
                .isFalse();
    }

    @Test
    void aPushAfterALongRunOfOptionsIsStillRefused() {
        assertThat(within(() -> ShellPatterns.gitPush("git" + " -c".repeat(BIG / 3) + " push")))
                .isTrue();
        assertThat(within(() -> ShellPatterns.gitPush("git" + " -c a".repeat(BIG / 5) + " push origin")))
                .isTrue();
        assertThat(within(() -> ShellPatterns.gitPush("echo ok\n" + "git -a ".repeat(BIG / 7) + "\ngit  push")))
                .isTrue();
    }

    @Test
    void gitPushIsRefusedWhateverOptionsOrWrapperComeFirst() {
        assertThat(ShellPatterns.gitPush("git push origin main")).isTrue();
        assertThat(ShellPatterns.gitPush("cd x && git -C repo push")).isTrue();
        assertThat(ShellPatterns.gitPush("git -c user.name=x push")).isTrue();
        assertThat(ShellPatterns.gitPush("git -c push")).isTrue();
        assertThat(ShellPatterns.gitPush("git -c a -c b --no-pager push")).isTrue();
        assertThat(ShellPatterns.gitPush("(git.exe --bare=x push)")).isTrue();
        assertThat(ShellPatterns.gitPush("/usr/bin/git push")).isTrue();
        assertThat(ShellPatterns.gitPush("git status")).isFalse();
        assertThat(ShellPatterns.gitPush("git log pushed")).isFalse();
        assertThat(ShellPatterns.gitPush("legit push")).isFalse();
    }

    @Test
    void claudeConfigIsReadInLinearTimeAndStillRefused() {
        assertThat(within(() -> ShellPatterns.claudeConfig("claude" + " -a".repeat(BIG / 3) + " x")))
                .isFalse();
        assertThat(within(() -> ShellPatterns.claudeConfig("--a=/claude ".repeat(BIG / 12))))
                .isFalse();
        assertThat(within(() -> ShellPatterns.claudeConfig("claude" + " -a".repeat(BIG / 3) + " plugin install x")))
                .isTrue();
        assertThat(ShellPatterns.claudeConfig("claude mcp add x")).isTrue();
        assertThat(ShellPatterns.claudeConfig("claude.cmd --debug plugins list"))
                .isTrue();
        assertThat(ShellPatterns.claudeConfig("claude -p hi")).isFalse();
    }

    @Test
    void killingJavaIsReadInLinearTimeAndStillRefused() {
        assertThat(within(() -> ShellPatterns.stopsJava("taskkill ".repeat(BIG / 9))))
                .isFalse();
        assertThat(within(() -> ShellPatterns.stopsJava("get-process java ".repeat(BIG / 16))))
                .isFalse();
        assertThat(within(() -> ShellPatterns.stopsJava("stop-process ".repeat(BIG / 13))))
                .isFalse();
        assertThat(within(() -> ShellPatterns.stopsJava("taskkill ".repeat(BIG / 9) + "/im java.exe")))
                .isTrue();
        assertThat(ShellPatterns.stopsJava("taskkill /f /im java.exe")).isTrue();
        assertThat(ShellPatterns.stopsJava("get-process java | stop-process")).isTrue();
        assertThat(ShellPatterns.stopsJava("stop-process -name java")).isTrue();
        assertThat(ShellPatterns.stopsJava("taskkill /pid 5\necho java")).isFalse();
    }
}
