package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.floor42.server.FuzzBudget;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.Tag;

/**
 * {@link ShellPatterns} against the frozen old regular expressions (short lines: they backtrack, which is why they
 * were replaced), then on long lines against a time budget (linear time, so a long agent command cannot stall the guard).
 */
@Tag("fuzz")
class ShellPatternsFuzzTest {

    private static final String[] WORDS = {
        "git",
        "git.exe",
        "claude",
        "claude.exe",
        "claude.cmd",
        "push",
        "pushx",
        "plugin",
        "plugins",
        "mcp",
        "-c",
        "-C",
        "-x",
        "--foo",
        "--foo=bar",
        "--",
        "-",
        "taskkill",
        "java",
        "stop-process",
        "-name",
        "pkill",
        "killall",
        "get-process",
        "gps",
        "spps",
        "|",
        ";",
        "&&",
        "(",
        "`",
        "'",
        "\"",
        "/usr/bin/git",
        "'git",
        "claude_config_dir",
        "claude_code_plugin",
        " ",
        "\t",
        "\n",
        "\u0085",
        " ",
        " "
    };
    private static final String[] GAPS = {" ", "  ", "\t", "\n", ""};

    static String line(FuzzedDataProvider d, int maxWords) {
        StringBuilder b = new StringBuilder();
        int n = d.consumeInt(0, maxWords);
        for (int i = 0; i < n && d.remainingBytes() > 0; i++) {
            b.append(d.consumeInt(0, 3) == 0 ? d.consumeString(5) : d.pickValue(WORDS));
            if (d.consumeBoolean()) b.append(d.pickValue(GAPS));
        }
        // ShellGuard hands the patterns a lower case line.
        return b.toString().toLowerCase(Locale.ROOT);
    }

    /** Never fewer refusals than the old expression; more only on a line with a non-ASCII character (combining marks). */
    private static void asRefusingAsBefore(String what, String s, boolean now, boolean before) {
        if (before) assertThat(now).as("%s lost: %s", what, s).isTrue();
        else if (now)
            assertThat(s.chars().anyMatch(c -> c > 127))
                    .as("%s gained on ASCII: %s", what, s)
                    .isTrue();
    }

    @FuzzTest
    void readsALineAsTheOldExpressionsDid(FuzzedDataProvider d) {
        String s = line(d, 14);
        asRefusingAsBefore(
                "git push",
                s,
                ShellPatterns.gitPush(s),
                ShellPatternsDifferentialTest.OLD_GIT_PUSH.matcher(s).find());
        boolean claude =
                ShellPatterns.claudeConfig(s) || s.contains("claude_config_dir") || s.contains("claude_code_plugin");
        asRefusingAsBefore(
                "claude",
                s,
                claude,
                ShellPatternsDifferentialTest.OLD_CLAUDE_CONFIG.matcher(s).find());
        if (ShellPatternsDifferentialTest.OLD_STOP_JAVA.matcher(s).find())
            assertThat(ShellPatterns.stopsJava(s)).as("java: %s", s).isTrue();
    }

    @FuzzTest
    void staysLinearOnALongLine(FuzzedDataProvider d) {
        String s = line(d, 4000);
        // Repeating the line makes the input long without needing a long seed.
        String longer = s.repeat(Math.max(1, 20000 / Math.max(1, s.length())));
        FuzzBudget.within(Duration.ofSeconds(10), () -> {
            ShellPatterns.gitPush(longer);
            ShellPatterns.claudeConfig(longer);
            ShellPatterns.stopsJava(longer);
        });
    }
}
