package dev.floor42.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What leaves the machine in a replay file: no token, no local path in any spelling, no secret, clipped outputs. */
class ReplaySanitizerTest {

    static ReplaySanitizer sanitizer() {
        Map<String, String> roots = new LinkedHashMap<>();
        roots.put("C:\\Users\\Ada Lovelace\\work\\garden", "<repository>");
        roots.put("C:\\Users\\Ada Lovelace\\.floor42\\workspaces\\m-1-chris", "<worktree:chris>");
        roots.put("C:\\Users\\Ada Lovelace\\.floor42", "<floor42-data>");
        roots.put("C:\\Users\\Ada Lovelace", "<home>");
        return new ReplaySanitizer("tok-123-secret-value", roots);
    }

    @Test
    void knownFoldersBecomeNamedPlaceholdersInEverySpelling() {
        ReplaySanitizer s = sanitizer();
        assertThat(s.text(1, "text", "read C:\\Users\\Ada Lovelace\\work\\garden\\README.md"))
                .isEqualTo("read <repository>\\README.md");
        assertThat(s.text(2, "text", "cd c:/users/ada lovelace/.floor42/workspaces/m-1-chris/src"))
                .isEqualTo("cd <worktree:chris>/src");
        assertThat(s.text(3, "text", "{\"p\":\"C:\\\\Users\\\\Ada Lovelace\\\\.floor42\\\\floor42.db\"}"))
                .isEqualTo("{\"p\":\"<floor42-data>\\\\floor42.db\"}");
        assertThat(s.text(4, "text", "ls /c/Users/Ada Lovelace/Desktop and /mnt/c/Users/Ada Lovelace/x"))
                .isEqualTo("ls <home>/Desktop and <home>/x");
        assertThat(s.counts()).containsEntry(ReplaySanitizer.Kind.PATH, 5);
    }

    @Test
    void anyOtherLocalAbsolutePathIsReplaced() {
        ReplaySanitizer s = sanitizer();
        String out = s.text(
                1,
                "output",
                "D:\\Users\\Bob Smith\\notes.txt, \\\\nas\\share\\plan.docx, /home/bob/.ssh/id_rsa,"
                        + " /Users/bob/Documents/x.md, /tmp/build.log and /mnt/d/data but not src/main/App.java");
        assertThat(out)
                .doesNotContain("Bob", "bob", "nas", "Smith")
                .contains("src/main/App.java")
                .isEqualTo("<path> <path> <path> <path> <path> and <path> but not src/main/App.java");
    }

    @Test
    void theTokenAndSecretsAreMaskedThenTheOutputIsClipped() {
        ReplaySanitizer s = sanitizer();
        String out =
                s.output(7, "output", "token tok-123-secret-value, key sk-ant-api03-abcdefgh " + "x y ".repeat(1000));
        assertThat(out).doesNotContain("tok-123", "sk-ant").contains("[masked]").endsWith("[clipped]");
        assertThat(out.length()).isLessThanOrEqualTo(ReplaySanitizer.OUTPUT_MAX + " [clipped]".length());
        assertThat(ReplaySanitizer.clipped(out)).isTrue();
        assertThat(s.counts())
                .containsEntry(ReplaySanitizer.Kind.TOKEN, 1)
                .containsEntry(ReplaySanitizer.Kind.OUTPUT_CLIPPED, 1);
        assertThat(s.details()).extracting(ReplaySanitizer.Masked::event).containsOnly(7);
    }

    @Test
    void theStreamParserAndTheReplayMaskTheSameWay() {
        String line = "use sk-ant-api03-abcdefghijkl now";
        assertThat(SecretMasker.maskText(line)).isEqualTo("use [masked] now");
        assertThat(sanitizer().text(1, "text", line)).isEqualTo("use [masked] now");
    }

    @Test
    void aHostileTextIsMaskedInLinearTime() {
        int big = 50_000;
        // The bugs this guards took tens of seconds; the gate runs under load.
        Duration budget = Duration.ofSeconds(10);
        Map<String, String> roots = Map.of("/home/ada/work", "<repository>", "C:\\Users\\Ada", "<home>");
        ReplaySanitizer s = new ReplaySanitizer("tok-123-secret-value", roots);
        for (String hostile : new String[] {
            "/".repeat(big),
            "\\/".repeat(big / 2),
            "/home/ada/" + "/".repeat(big),
            "c:" + "/".repeat(big),
            "c:/users/" + "a".repeat(big) + " ",
            "c:/".repeat(big / 3),
            "\\\\" + "a".repeat(big),
            "\\\\a".repeat(big / 3),
            " /home/a".repeat(big / 8),
            "/home/" + "a".repeat(big),
        }) {
            assertTimeoutPreemptively(budget, () -> s.text(1, "text", hostile));
        }
        assertThat(s.text(2, "text", "ls //home/ada/work/src and /home/ada/work"))
                .isEqualTo("ls <repository>/src and <repository>");
    }

    /** Plain words of exactly {@code n} characters (a long run of letters would be masked as a secret). */
    private static String words(int n) {
        return "ab ".repeat(n).substring(0, n);
    }

    @Test
    void aTextIsClippedOnlyAboveItsLimitAndTheOutputLimitIsLower() {
        ReplaySanitizer s = new ReplaySanitizer(null, Map.of());
        String atLimit = words(ReplaySanitizer.TEXT_MAX);
        assertThat(s.text(1, "text", atLimit)).isEqualTo(atLimit);
        assertThat(s.text(1, "text", atLimit + "z")).isEqualTo(atLimit + " [clipped]");
        assertThat(s.output(1, "output", words(ReplaySanitizer.OUTPUT_MAX))).doesNotContain("[clipped]");
        assertThat(s.output(1, "output", words(ReplaySanitizer.OUTPUT_MAX + 1))).endsWith(" [clipped]");
        assertThat(s.counts())
                .containsEntry(ReplaySanitizer.Kind.TEXT_CLIPPED, 1)
                .containsEntry(ReplaySanitizer.Kind.OUTPUT_CLIPPED, 1);
    }

    @Test
    void nothingToCleanIsReturnedAsItCameAndNothingIsCounted() {
        ReplaySanitizer s = new ReplaySanitizer("", Map.of());
        assertThat(s.text(1, "text", null)).isNull();
        assertThat(s.text(1, "text", "")).isEmpty();
        assertThat(s.text(1, "text", "plain words")).isEqualTo("plain words");
        assertThat(ReplaySanitizer.clipped(null)).isFalse();
        assertThat(ReplaySanitizer.clipped("plain words")).isFalse();
        assertThat(ReplaySanitizer.clipped("cut [clipped]")).isTrue();
        assertThat(s.counts()).isEmpty();
        assertThat(s.total()).isZero();
        assertThat(s.details()).isEmpty();
    }

    @Test
    void secretsAreCountedAndWhatTheCallerWithheldIsRecorded() {
        ReplaySanitizer s = new ReplaySanitizer("tok-123-secret-value", Map.of());
        s.text(3, "text", "tok-123-secret-value and tok-123-secret-value, key sk-ant-api03-abcdefghijkl");
        s.withheld(4, "diff", ReplaySanitizer.Kind.OUTPUT_CLIPPED);

        assertThat(s.counts())
                .containsEntry(ReplaySanitizer.Kind.TOKEN, 2)
                .containsEntry(ReplaySanitizer.Kind.SECRET, 1)
                .containsEntry(ReplaySanitizer.Kind.OUTPUT_CLIPPED, 1);
        assertThat(s.total()).isEqualTo(4);
        assertThat(s.details())
                .containsExactly(
                        new ReplaySanitizer.Masked(3, "text", ReplaySanitizer.Kind.TOKEN),
                        new ReplaySanitizer.Masked(3, "text", ReplaySanitizer.Kind.SECRET),
                        new ReplaySanitizer.Masked(4, "diff", ReplaySanitizer.Kind.OUTPUT_CLIPPED));
    }

    @Test
    void theDetailsKeepTheFirstOnesOnlyWhileTheCountsKeepGoing() {
        ReplaySanitizer s = new ReplaySanitizer(null, Map.of());
        for (int i = 0; i < ReplaySanitizer.DETAILS_MAX + 5; i++) s.withheld(i, "f", ReplaySanitizer.Kind.TEXT_CLIPPED);

        assertThat(s.details()).hasSize(ReplaySanitizer.DETAILS_MAX);
        assertThat(s.details().getLast().event()).isEqualTo(ReplaySanitizer.DETAILS_MAX - 1);
        assertThat(s.total()).isEqualTo(ReplaySanitizer.DETAILS_MAX + 5);
    }

    @Test
    void theLongestRootWinsWhateverTheOrderItWasGiven() {
        Map<String, String> roots = new LinkedHashMap<>();
        roots.put("/srv/data", "<data>");
        roots.put("/srv/data/work/repo", "<repository>");
        assertThat(new ReplaySanitizer(null, roots).text(1, "t", "at /srv/data/work/repo/a.txt"))
                .isEqualTo("at <repository>/a.txt");
    }

    @Test
    void aBlankOrMissingRootIsIgnored() {
        Map<String, String> roots = new java.util.HashMap<>();
        roots.put(null, "<null>");
        roots.put("  ", "<blank>");
        roots.put("", "<empty>");
        assertThat(new ReplaySanitizer(null, roots).text(1, "t", "keep this words as they are"))
                .isEqualTo("keep this words as they are");
    }
}
