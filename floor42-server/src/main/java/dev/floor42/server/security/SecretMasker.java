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
            "(?i).*(token|secret|password|passwd|passphrase|api[-_]?key|access[-_]?key|private[-_]?key|credential|auth|cookie)"
                    + ".*");

    /**
     * Where a credential may start. Plain parts are whole regular expressions (a prefix, a scheme word and its token, a
     * run of key characters). The parts whose value has no fixed shape only match their head, and {@link #scan} reads the
     * value in one pass and fails closed: a quote is closed by the same quote (a backslash escapes), an open quote runs to
     * the end of the line, an unquoted value to the next blank. No {@code \b} (the JDK's reads back over a run of
     * combining marks), no bound that a long value can pass, and a head never rescans what a failed head already read.
     */
    private static final String BASE = "(?<pem>-----BEGIN [A-Z ]*PRIVATE KEY-----)"
            + "|(?<jwt>eyJ)"
            + "|(?<prefix>(?:sk-ant-|sk_live_|sk_test_|rk_live_|sk-|ghp_|gho_|ghs_|github_pat_|glpat-|npm_|xox[abprs]-"
            + "|AKIA|ASIA|AIza)[A-Za-z0-9_\\-]{6,})"
            + "|(?<scheme>(?i:bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,})"
            + "|(?<url>://[^:@/\\s]+:)"
            // A key (its quote may close first), a separator, then the value.
            // Its name may be URL-encoded, any character of it, a quote as %22, "=" and ":" as %3D and %3A (D29).
            + "|(?<key>(?i:"
            + encoded(
                    "password",
                    "pass",
                    "passwd",
                    "pwd",
                    "passphrase",
                    "secret",
                    "secret_key",
                    "secret-key",
                    "secretkey",
                    "privatekey",
                    "accesskey",
                    "clientsecret",
                    "private_key",
                    "private-key",
                    "access_key",
                    "access-key",
                    "token",
                    "api_key",
                    "api-key",
                    "apikey")
            + ")(?:[\"']|%22)?(?:\\s*[=:]\\s*|\\s+|%3[dDaA](?:%22)?))"
            // These words are common in plain text: a = or : must follow, and for authorization a scheme word or a long
            // token.
            + "|(?<authz>(?i:authorization)[\"']?\\s*[=:]\\s*(?=[\"']?(?:(?i:bearer|basic|token|digest|negotiate|ntlm"
            + "|api[-_]?key|aws4-hmac-sha256)\\s|\\S{16})))"
            + "|(?<auth>(?i:auth|credentials?)[\"']?\\s*[=:]\\s*)"
            // A cookie header: the rest of its line, every cookie of it.
            + "|(?<cookie>(?i:(?:set-)?cookie)[\"']?\\s*:\\s*)"
            // A secret query parameter: the head is the empty place right after its name. A URL encoded inside another
            // writes "?", "&" and "=" as %3F, %26 and %3D.
            + "|(?<query>(?i:(?<=(?:[?&]|&amp;|%3[fF]|%26)"
            + encoded(
                    "token",
                    "auth",
                    "code",
                    "key",
                    "secret",
                    "password",
                    "access_token",
                    "refresh_token",
                    "id_token",
                    "client_secret",
                    "api_key",
                    "api-key",
                    "apikey",
                    "sig",
                    "signature",
                    "x-amz-signature",
                    "x-goog-signature")
            + "(?:=|%3[dD]))))";

    private static final Pattern HEAD = Pattern.compile(
            BASE + "|(?<hex>(?<![A-Za-z0-9_])[0-9a-fA-F]{24,}(?![A-Za-z0-9_]))" + "|(?<run>[A-Za-z0-9+/_=-]{32,})");

    /** The heads inside a file path: all but the two that read a bare run (a path is not a secret by itself). */
    private static final Pattern INNER = Pattern.compile(BASE);

    /**
     * The names as alternatives, each character also URL-encoded ({@code %74oken} is {@code token}). Read in a
     * case-insensitive group, so {@code %6f} and {@code %6F} are both an encoded {@code o}.
     */
    private static String encoded(String... names) {
        StringBuilder p = new StringBuilder("(?:");
        for (int n = 0; n < names.length; n++) {
            if (n > 0) p.append('|');
            for (char c : names[n].toCharArray()) {
                p.append("(?:").append(c == '-' ? "\\-" : String.valueOf(c));
                p.append(String.format("|%%%02x", (int) Character.toLowerCase(c)));
                if (Character.isLetter(c)) p.append(String.format("|%%%02x", (int) Character.toUpperCase(c)));
                p.append(')');
            }
        }
        return p.append(')').toString();
    }

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
        Matcher inner = INNER.matcher(s).useTransparentBounds(true).useAnchoringBounds(false);
        int n = 0;
        int last = 0;
        int pos = 0;
        // Heads before these stand inside a run a failed head already read to its end: they fail the same way. The
        // third is the end of the file path being read, the fourth the end of a path token judged not to be one.
        int[] noUntil = new int[4];
        Matcher cur;
        while (pos <= s.length() && (cur = nextHead(m, inner, s, pos, noUntil)) != null) {
            int start = cur.start();
            int end = headEnd(cur, s, noUntil);
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

    /**
     * The next head from {@code pos}. A file path is not a secret by itself: inside one only the heads of known forms are
     * read, within the path, so it is not rescanned as runs.
     */
    private static Matcher nextHead(Matcher m, Matcher inner, String s, int pos, int[] st) {
        int p = pos;
        while (true) {
            if (p < st[2]) {
                if (inner.region(p, st[2]).find()) return inner;
                p = st[2];
            } else if (!m.find(p)) {
                return null;
            } else if (m.group("run") != null && SecretPaths.pathRun(s, m.start(), m.end(), st)) {
                p = m.start();
            } else {
                return m;
            }
        }
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
            end = keyEnd(s, start, m.end(), m.group("key") != null);
        } else if (m.group("authz") != null || m.group("cookie") != null) {
            end = headerValueEnd(s, start, m.end());
        } else if (m.group("query") != null) {
            int i = queryValueEnd(s, m.end());
            if (i > m.end()) end = i;
        } else {
            end = m.end();
        }
        return end;
    }

    private static boolean delimited(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c == '=' || c == ':' || c == '%' && i + 1 < to && s.charAt(i + 1) == '3') return true;
        }
        return false;
    }

    /** Whether a value read after a bare key word looks like a secret: opens with a quote, has a digit, 8 characters or both cases. */
    private static boolean secretLike(String s, int from, int to) {
        if (to - from >= 8 || quote(s.charAt(from))) return true;
        boolean upper = false;
        boolean lower = false;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') return true;
            upper |= c >= 'A' && c <= 'Z';
            lower |= c >= 'a' && c <= 'z';
        }
        return upper && lower;
    }

    /** Whether a query string's {@code &} (or {@code &amp;}, {@code %26}, {@code ?}, {@code %3F}) stands right before {@code at}. */
    private static boolean queryMark(String s, int at) {
        if (at <= 0) return false;
        char p = s.charAt(at - 1);
        return p == '?'
                || p == '&'
                || at >= 5 && s.regionMatches(true, at - 5, "&amp;", 0, 5)
                || at >= 3
                        && (s.regionMatches(true, at - 3, "%26", 0, 3) || s.regionMatches(true, at - 3, "%3f", 0, 3));
    }

    /** A key's value; in a query string ({@code ?token=...}) it may hold blanks. */
    private static int keyEnd(String s, int start, int from, boolean key) {
        int end = valueEnd(s, from, true, false);
        // "pass in", "token expired": after a bare key word (no = or :) the value must look like a secret.
        if (end > 0
                && key
                && !delimited(s, start, from)
                && !SecretWords.explicit(s, start, from, end)
                && !secretLike(s, from, end)) return -1;
        if (end > 0 && queryMark(s, start) && !quote(s.charAt(from))) end = queryValueEnd(s, end);
        return end;
    }

    /** A header's value: the rest of the line, or up to the closing quote of a header quoted on a command line. */
    private static int headerValueEnd(String s, int start, int from) {
        int end = valueEnd(s, from, false, true);
        // A quoted value ({"Cookie": "sid=..."}) already ends after its closing quote.
        boolean quotedValue = from < s.length() && quote(s.charAt(from));
        if (end > 0 && !quotedValue && start > 0 && quote(s.charAt(start - 1)))
            return headerEnd(s, from, s.charAt(start - 1), end);
        // A value opening with the other quote (-H 'Cookie: "v1"; sid=...'): to the outer quote or the line end, the
        // longer.
        if (end > 0
                && quotedValue
                && start > 0
                && quote(s.charAt(start - 1))
                && s.charAt(start - 1) != s.charAt(from)
                && noQuote(s, start, from)) {
            int lineEnd = from;
            while (lineEnd < s.length() && s.charAt(lineEnd) != '\n' && s.charAt(lineEnd) != '\r') lineEnd++;
            return Math.max(end, headerEnd(s, from, s.charAt(start - 1), lineEnd));
        }
        return end;
    }

    /**
     * The end of a query value: at {@code &}, {@code #} or a blank, then on over the blank-separated words that follow
     * while each is made only of URL characters ({@link #urlSafe}). When in doubt, mask: a blank inside the value
     * ({@code token=abc1 2xyz}) is not told from the end of the URL by its shape, so prose after a token-bearing URL goes
     * too. It stops at a line end, at a quote, {@code &}, {@code #}, {@code <} or {@code >}, and before a word with any
     * other character.
     */
    private static int queryValueEnd(String s, int from) {
        int i = from;
        while (true) {
            while (i < s.length() && s.charAt(i) != '&' && s.charAt(i) != '#' && !blank(s.charAt(i))) i++;
            int j = i;
            while (j < s.length() && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) j++;
            int k = j;
            while (k < s.length() && urlSafe(s.charAt(k))) k++;
            boolean wordEnds = k == s.length() || "&#<>\"'".indexOf(s.charAt(k)) >= 0 || blank(s.charAt(k));
            if (j == i || k == j || !wordEnds) return i;
            i = k;
        }
    }

    private static boolean noQuote(String s, int from, int to) {
        for (int i = from; i < to; i++) if (quote(s.charAt(i))) return false;
        return true;
    }

    /** A header quoted on a command line ({@code -H 'Cookie: a=b'}) ends at its closing quote, not at the line's end. */
    private static int headerEnd(String s, int from, char q, int lineEnd) {
        for (int i = from; i < lineEnd; i++) {
            if (s.charAt(i) == (char) 92) i++;
            else if (s.charAt(i) == q) return i;
        }
        return lineEnd;
    }

    private static boolean quote(char c) {
        return c == '"' || c == '\'';
    }

    private static boolean urlSafe(char c) {
        return word(c) || c == '-' || c == '.' || c == '~' || c == '%' || c == '+' || c == '/' || c == '=';
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

    /** Where the read of a URL's password stops: at its last {@code @}, or at the first blank (or the end). */
    private static int atStop(String s, int from) {
        int i = from;
        int at = -1;
        // The last @ before the host's path: a password may hold an @.
        while (i < s.length() && !blank(s.charAt(i)) && !(at >= 0 && s.charAt(i) == '/')) {
            if (s.charAt(i) == '@') at = i;
            i++;
        }
        return at >= 0 ? at : i;
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
