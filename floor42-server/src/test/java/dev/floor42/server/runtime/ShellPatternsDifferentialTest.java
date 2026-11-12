package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@link ShellPatterns} against the regular expressions it replaced (frozen below, copied verbatim from the release
 * before): the git push and claude plugin tests refuse exactly the same lines, the Java kill test at least those.
 * Lines are lower case, as {@code ShellGuard} normalises them.
 */
class ShellPatternsDifferentialTest {

    static final Pattern OLD_GIT_PUSH =
            Pattern.compile("(^|[\\s;|&(`'\"/])git(\\.exe)?(\\s+(-[cC]\\s+\\S+|--?[\\w-]+(=\\S+)?))*\\s+push\\b");
    static final Pattern OLD_CLAUDE_CONFIG = Pattern.compile(
            "(^|[\\s;|&(`'\"/])claude(\\.exe|\\.cmd)?['\"]?(\\s+--?[\\w-]+(=\\S+)?)*\\s+(plugins?|mcp)\\b"
                    + "|claude_config_dir|claude_code_plugin");
    static final Pattern OLD_STOP_JAVA = Pattern.compile(
            "\\b(taskkill\\b.*\\bjava|stop-process\\b.*-name\\s+['\"]?java|pkill\\b.*\\bjava|killall\\b.*\\bjava"
                    + "|get-process\\s+['\"]?java\\b.*\\|\\s*stop-process)");

    private static final List<String> ADVERSARIAL = List.of(
            "git push",
            "git push origin main",
            "'git' push",
            "\"git\" push",
            "g\\it push",
            "git${IFS}push",
            "git$IFS'push'",
            "$'git' push",
            "env git push",
            "env -i git push",
            "command git push",
            "xargs git push",
            "sh -c 'git push'",
            "sh -c \"git push origin\"",
            "bash -lc 'git push'",
            "bash -lc \"cd x && git push\"",
            "eval git push",
            "eval \"git push\"",
            "/usr/bin/git push",
            "/usr/bin/git -C repo push",
            "git -C repo push",
            "git -c core.x=1 push",
            "git -c push",
            "git -C push push",
            "git -c a -c b push",
            "git --git-dir=.git push",
            "git --git-dir .git push",
            "git -=x push",
            "git -x= push",
            "git - push",
            "git -- push",
            "$(git push)",
            "`git push`",
            "echo $(git -C x push)",
            "a;git push",
            "a&&git push",
            "a||git push",
            "a|git push",
            "(git push)",
            "git.exe push",
            "git.exe -C x push",
            "git\tpush",
            "git\t-C\tx\tpush",
            "git\npush",
            "git \u0085 push",
            "git push",
            "git push\u0085",
            "git pushx",
            "git push-x",
            "git status; git push",
            "legit push",
            "git log --oneline",
            "claude plugin install x",
            "claude plugins list",
            "claude mcp add x",
            "claude --debug plugin install x",
            "claude -p mcp",
            "claude.exe mcp",
            "claude.cmd plugin",
            "'claude' plugin",
            "claude' plugin",
            "claude\" mcp",
            "env claude plugin install",
            "sh -c 'claude plugin install x'",
            "bash -lc \"claude mcp add\"",
            "/usr/local/bin/claude plugin",
            "claude --x=y --z plugin",
            "claude -=x plugin",
            "claude\tplugin",
            "claude\u0085plugin",
            "claude plugin_x",
            "claude pluginsx",
            "echo claude_config_dir",
            "export CLAUDE_CODE_PLUGIN_X=1".toLowerCase(),
            "claude -p hi",
            "taskkill /f /im java.exe",
            "taskkill java",
            "taskkill\njava",
            "cmd /c taskkill /im java.exe",
            "stop-process -name java",
            "stop-process -name 'java'",
            "stop-process -name\njava",
            "get-process java | stop-process",
            "get-process 'java' | stop-process",
            "pkill -f java",
            "killall java",
            "pkill java\u0085x",
            "echo javascript; taskkill /pid 5");

    private static void same(String line) {
        assertThat(ShellPatterns.gitPush(line))
                .as("git push: %s", line.replace("\n", "\\n"))
                .isEqualTo(OLD_GIT_PUSH.matcher(line).find());
        boolean claude = ShellPatterns.claudeConfig(line)
                || line.contains("claude_config_dir")
                || line.contains("claude_code_plugin");
        assertThat(claude)
                .as("claude: %s", line)
                .isEqualTo(OLD_CLAUDE_CONFIG.matcher(line).find());
        if (OLD_STOP_JAVA.matcher(line).find())
            assertThat(ShellPatterns.stopsJava(line)).as("java: %s", line).isTrue();
    }

    @Test
    void adversarialLinesAreReadAsBefore() {
        ADVERSARIAL.forEach(ShellPatternsDifferentialTest::same);
    }

    @Test
    void aSeededRandomCorpusIsReadAsBefore() {
        String[] words = {
            "git",
            "git.exe",
            "claude",
            "claude.exe",
            "claude.cmd",
            "push",
            "pushx",
            "push-y",
            "plugin",
            "plugins",
            "pluginsx",
            "mcp",
            "mcp_x",
            "-c",
            "-C",
            "-x",
            "--foo",
            "--foo=bar",
            "--foo=",
            "-",
            "--",
            "-a=b",
            "-=x",
            "x",
            "/",
            "'",
            "\"",
            ";",
            "|",
            "&",
            "(",
            "`",
            " ",
            " ",
            "\t",
            "\n",
            "\u0085",
            " ",
            "taskkill",
            "java",
            "javac",
            "stop-process",
            "-name",
            "pkill",
            "killall",
            "get-process",
            "\\|",
            "  ",
            "/usr/bin/git",
            "'git",
            "\"claude\"",
            "claude'",
            "-c=x",
            "a/git",
            "a=git",
            "--p",
            "env",
            "sh",
            "-c",
            "eval",
            "$(",
            ")",
            "$ifs"
        };
        Random r = new Random(20261109);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 300_000; i++) {
            StringBuilder sb = new StringBuilder();
            int k = 1 + r.nextInt(9);
            for (int j = 0; j < k; j++) {
                sb.append(words[r.nextInt(words.length)]);
                if (r.nextInt(3) > 0) sb.append(r.nextBoolean() ? " " : "");
            }
            lines.add(sb.toString());
        }
        lines.forEach(ShellPatternsDifferentialTest::same);
    }

    @Test
    void theKillTestAlsoCatchesCmdCAndThePowerShellAliases() {
        assertThat(ShellPatterns.stopsJava("cmd /ctaskkill /im java.exe")).isTrue();
        assertThat(ShellPatterns.stopsJava("gps java | spps")).isTrue();
        assertThat(ShellPatterns.stopsJava("gps 'java' | kill")).isTrue();
        assertThat(ShellPatterns.stopsJava("spps -name java")).isTrue();
        assertThat(ShellPatterns.stopsJava("gps node | spps")).isFalse();
    }

    @Test
    void anOptionNeedsACharacterAfterTheDash() {
        assertThat(ShellPatterns.gitPush("git -=x push")).isFalse();
        assertThat(ShellPatterns.claudeConfig("claude -=x plugin")).isFalse();
        assertThat(ShellPatterns.gitPush("git -x=y push")).isTrue();
    }
}
