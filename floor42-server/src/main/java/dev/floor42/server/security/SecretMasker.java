package dev.floor42.server.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What looks like a secret, replaced by {@value #MASK}: the one set of patterns Floor42 uses wherever text leaves the
 * place it was recorded in. The stream parser masks the unknown lines it reports with it; the replay export masks
 * every text it writes into the file with it. A false positive (a long hash, a long path without spaces) is masked
 * too: hiding too much is the safe side.
 */
public final class SecretMasker {

    public static final String MASK = "[masked]";

    /** Keys whose value is never kept, whatever it looks like. */
    static final Pattern SECRET_KEY = Pattern.compile(
            "(?i).*(token|secret|password|passwd|api[-_]?key|access[-_]?key|private[-_]?key|credential|auth|cookie)"
                    + ".*");

    /**
     * Where a credential may start. Plain parts are whole regular expressions (a prefix, a scheme word and its token, a
     * run of key characters). The parts whose value has no fixed shape only match their head, and {@link #scan} reads the
     * value in one pass and fails closed: a quote is closed by the same quote (a backslash escapes), an open quote runs to
     * the end of the line, an unquoted value to the next blank. No {@code \b} (the JDK's reads back over a run of
     * combining marks), no bound that a long value can pass, and a head never rescans what a failed head already read.
     */
    private static final Pattern HEAD = Pattern.compile("(?<pem>-----BEGIN [A-Z ]*PRIVATE KEY-----)"
            + "|(?<jwt>eyJ)"
            + "|(?<prefix>(?:sk-ant-|sk_live_|sk_test_|rk_live_|sk-|ghp_|gho_|ghs_|github_pat_|glpat-|npm_|xox[abprs]-"
            + "|AKIA|ASIA|AIza)[A-Za-z0-9_\\-]{6,})"
            + "|(?<scheme>(?i:bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,})"
            + "|(?<url>://[^:@/\\s]+:)"
            // A key (its quote may close first), a separator, then the value.
            + "|(?<key>(?i:pass(?:word)?|passwd|pwd|secret|token|api[-_]?key)[\"']?(?:\\s*[=:]\\s*|\\s+))"
            // These words are common in plain text: a = or : must follow, and for authorization a scheme word or a long
            // token.
            + "|(?<authz>(?i:authorization)[\"']?\\s*[=:]\\s*(?=[\"']?(?:(?i:bearer|basic|token|digest|negotiate|ntlm"
            + "|api[-_]?key|aws4-hmac-sha256)\\s|\\S{16})))"
            + "|(?<auth>(?i:auth|credentials?)[\"']?\\s*[=:]\\s*)"
            // A secret query parameter: the head is the empty place right after its name.
            + "|(?<query>(?i:(?<=(?:[?&]|&amp;)(?:token|auth|code|key|secret|password|access_token|refresh_token|id_token"
            + "|client_secret|api[-_]?key)=)))"
            + "|(?<hex>(?<![A-Za-z0-9_])[0-9a-fA-F]{24,}(?![A-Za-z0-9_]))"
            + "|(?<run>[A-Za-z0-9+/_=-]{32,})");

    /** A scheme word that may stand between a key and its value. */
    private static final Pattern SCHEME =
            Pattern.compile("(?i)(?:bearer|basic|token|digest|api[-_]?key|negotiate|ntlm)\\s+");

    private SecretMasker() {}

    /** Whether a JSON key names a secret: its value is hidden whole. */
    public static boolean isSecretKey(String key) {
        return SECRET_KEY.matcher(key).matches();
    }

    /** The text with every value that looks like a credential replaced by {@value #MASK}. */
    public static String maskText(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());
        scan(text, out);
        return out.toString();
    }

    /** How many values of the text {@link #maskText} replaces. */
    public static int count(String text) {
        if (text == null || text.isEmpty()) return 0;
        return scan(text, null);
    }

    private static int scan(String s, StringBuilder out) {
        Matcher m = HEAD.matcher(s);
        int n = 0;
        int last = 0;
        int pos = 0;
        // Heads before these stand inside a run a failed head already read to its end: they fail the same way.
        int[] noUntil = new int[2];
        while (pos <= s.length() && m.find(pos)) {
            int start = m.start();
            int end = headEnd(m, s, noUntil);
            if (end > start) {
                n++;
                if (out != null) out.append(s, last, start).append(MASK);
                last = end;
                pos = end;
            } else {
                pos = start + 1;
            }
        }
        if (out != null) out.append(s, last, s.length());
        return n;
    }

    /** The end of the credential whose head the matcher found, or -1 when it is none (the memos are in noUntil). */
    private static int headEnd(Matcher m, String s, int[] noUntil) {
        int start = m.start();
        int end = -1;
        if (m.group("pem") != null) {
            end = pemEnd(s, m.end());
        } else if (m.group("jwt") != null) {
            if (start >= noUntil[1]) {
                int[] stop = new int[1];
                end = jwtEnd(s, start + 3, stop);
                if (end < 0) noUntil[1] = stop[0];
            }
        } else if (m.group("url") != null) {
            if (start >= noUntil[0]) {
                end = userInfoEnd(s, m.end());
                if (end < 0) noUntil[0] = atStop(s, m.end());
            }
        } else if (m.group("key") != null || m.group("auth") != null) {
            end = valueEnd(s, m.end(), true, false);
        } else if (m.group("authz") != null) {
            end = valueEnd(s, m.end(), false, true);
        } else if (m.group("query") != null) {
            int i = m.end();
            while (i < s.length() && s.charAt(i) != '&' && s.charAt(i) != '#' && !blank(s.charAt(i))) i++;
            if (i > m.end()) end = i;
        } else {
            end = m.end();
        }
        return end;
    }

    /** The end of a private key block: past its END line, or the end of the text when it has none. */
    private static int pemEnd(String s, int from) {
        int e = s.indexOf("-----END", from);
        if (e < 0) return s.length();
        int end = e + 8;
        while (end < s.length() && s.charAt(end) != '-') end++;
        while (end < s.length() && s.charAt(end) == '-') end++;
        return end;
    }

    /** Where the read of a URL's password stops: at its {@code @}, or at the first blank (or the end). */
    private static int atStop(String s, int from) {
        int i = from;
        while (i < s.length() && s.charAt(i) != '@' && !blank(s.charAt(i))) i++;
        return i;
    }

    /** The end of {@code user:password@} (the password starts at {@code from}), or -1 when there is no {@code @}. */
    private static int userInfoEnd(String s, int from) {
        int i = atStop(s, from);
        return i < s.length() && s.charAt(i) == '@' ? i + 1 : -1;
    }

    /** End of {@code eyJ...} {@code .} {@code ...} {@code .} {@code ...}, or -1 (and where the failed read stopped). */
    private static int jwtEnd(String s, int from, int[] stop) {
        int i = from;
        for (int part = 0; part < 3; part++) {
            int j = i;
            while (j < s.length() && (word(s.charAt(j)) || s.charAt(j) == '-')) j++;
            stop[0] = j;
            if (j == i) return -1;
            if (part < 2) {
                if (j >= s.length() || s.charAt(j) != '.') return -1;
                i = j + 1;
            } else {
                i = j;
            }
        }
        return i;
    }

    /** The end of the value that starts at {@code from} (after an optional scheme word), or -1 when there is none. */
    private static int valueEnd(String s, int from, boolean scheme, boolean toLineEnd) {
        int v = from;
        if (scheme) {
            Matcher sch = SCHEME.matcher(s).region(from, s.length());
            if (sch.lookingAt() && sch.end() < s.length() && !blank(s.charAt(sch.end()))) v = sch.end();
        }
        if (v >= s.length()) return -1;
        char c = s.charAt(v);
        int i = v;
        if (c == '"' || c == '\'') {
            i = v + 1;
            while (i < s.length() && s.charAt(i) != '\n' && s.charAt(i) != '\r') {
                char x = s.charAt(i);
                if (x == '\\') i += 2;
                else if (x == c) return i + 1;
                else i++;
            }
            return Math.min(i, s.length());
        }
        while (i < s.length() && (toLineEnd ? s.charAt(i) != '\n' && s.charAt(i) != '\r' : !blank(s.charAt(i)))) i++;
        return i > v ? i : -1;
    }

    private static boolean blank(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    private static boolean word(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_';
    }

    /**
     * A copy of the tree with what looks like a secret masked: a secret key hides all it holds, an object included;
     * token counts (numbers under a {@code _tokens} key) and an {@code author} stay.
     */
    public static JsonNode mask(JsonNode node) {
        return maskInPlace(node.deepCopy());
    }

    private static JsonNode maskInPlace(JsonNode node) {
        if (node instanceof ObjectNode o) {
            for (var e : o.properties()) {
                if (isSecretKey(e.getKey())
                        && !e.getValue().isNull()
                        && !(e.getKey().endsWith("_tokens") && e.getValue().isNumber())
                        && !e.getKey().equals("author")) {
                    e.setValue(TextNode.valueOf(MASK));
                } else {
                    e.setValue(maskInPlace(e.getValue()));
                }
            }
            return o;
        }
        if (node instanceof ArrayNode a) {
            for (int i = 0; i < a.size(); i++) a.set(i, maskInPlace(a.get(i)));
            return a;
        }
        if (node.isTextual()) return TextNode.valueOf(maskText(node.asText()));
        return node;
    }
}
