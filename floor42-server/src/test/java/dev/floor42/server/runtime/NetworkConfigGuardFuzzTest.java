package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;

/**
 * {@link NetworkConfigGuard#risky} on {@code git config --list -z --show-scope} output built from parts, so that the
 * truth is known: the first local or worktree record whose key is in a refused family is reported, and nothing else is.
 * The oracle names the families with plain string tests, not the guard's expression.
 */
@Tag("fuzz")
class NetworkConfigGuardFuzzTest {

    private static final List<String> SCOPES = List.of("local", "worktree", "global", "system", "command", "Local", "");
    private static final List<String> SECTIONS = List.of(
            "core",
            "credential",
            "http",
            "https",
            "protocol",
            "gpg",
            "remote",
            "url",
            "include",
            "includeIf",
            "user",
            "alias",
            "branch",
            "safe",
            "HTTP",
            "Remote");
    private static final List<String> NAMES = List.of(
            "sshCommand",
            "gitProxy",
            "askPass",
            "hooksPath",
            "helper",
            "pushurl",
            "insteadOf",
            "receivepack",
            "program",
            "proxy",
            "vcs",
            "receive-pack",
            "upload-pack",
            "uploadpack",
            "proxyAuthMethod",
            "name",
            "path",
            "x",
            "");
    private static final Set<String> CORE =
            Set.of("core.sshcommand", "core.gitproxy", "core.askpass", "core.hookspath");
    private static final List<String> FAMILIES =
            List.of("credential.", "http.", "https.", "protocol.", "url.", "include.", "includeif.");
    private static final List<String> REMOTE_SUFFIXES = List.of(
            "receivepack", "uploadpack", "pushurl", "proxy", "proxyauthmethod", "vcs", "receive-pack", "upload-pack");
    private static final String[] TRIPS = {
        "=", ".", ":", "/", "\\", "https://x/y", "\"", "\n", "=\n", ".pushurl", "\"=x\n"
    };

    /** Subsection or value: free text, with the characters a parser is likely to trip on, and no NUL (git has none). */
    private static String text(FuzzedDataProvider d) {
        String s = d.consumeString(24);
        if (d.consumeBoolean()) s += d.pickValue(TRIPS);
        return s.replace("\u0000", "");
    }

    static boolean refusedFamily(String lowerKey) {
        if (CORE.contains(lowerKey)) return true;
        for (String f : FAMILIES) if (lowerKey.startsWith(f)) return true;
        if (lowerKey.startsWith("gpg.")) return true;
        if (lowerKey.startsWith("remote.")) {
            String rest = lowerKey.substring("remote.".length());
            // A remote named like a URL is what `git push <url>` picks: any setting of it is refused.
            if (rest.indexOf(':') >= 0 || rest.indexOf('/') >= 0 || rest.indexOf('\\') >= 0) return true;
            for (String s : REMOTE_SUFFIXES) if (rest.endsWith("." + s)) return true;
        }
        return false;
    }

    @FuzzTest
    void reportsExactlyTheFirstRefusedLocalOrWorktreeKey(FuzzedDataProvider d) {
        StringBuilder list = new StringBuilder();
        Optional<String> expected = Optional.empty();
        int records = d.consumeInt(0, 6);
        for (int i = 0; i < records; i++) {
            String scope = d.consumeBoolean()
                    ? d.pickValue(SCOPES)
                    : d.consumeString(8).replace("\u0000", "");
            StringBuilder key = new StringBuilder(d.pickValue(SECTIONS));
            if (d.consumeBoolean()) key.append('.').append(text(d));
            key.append('.').append(d.pickValue(NAMES));
            String value = text(d);
            list.append(scope)
                    .append('\u0000')
                    .append(key)
                    .append('\n')
                    .append(value)
                    .append('\u0000');
            if (expected.isEmpty() && (scope.equals("local") || scope.equals("worktree"))) {
                // A key ends at its first line feed (git forbids one in a name, the parser must still not read past
                // it).
                String k = key.toString();
                int lf = k.indexOf('\n');
                String lower = (lf < 0 ? k : k.substring(0, lf)).toLowerCase(Locale.ROOT);
                if (refusedFamily(lower)) expected = Optional.of(lower);
            }
        }
        assertThat(NetworkConfigGuard.risky(list.toString())).isEqualTo(expected);
    }
}
