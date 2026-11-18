package dev.floor42.server.security;

/**
 * Whether a long run of characters is a file path and no secret, for {@link SecretMasker}: a path is kept readable when
 * every segment of it looks like the name of a file or a folder.
 */
final class SecretPaths {

    private SecretPaths() {}

    /**
     * Whether the run {@code [start, end)} is a file path and no secret. A rooted path (Unix, WSL, {@code ~/},
     * {@code C:/}) has two slashes or more, a relative one too, and one after a Windows drive ({@code C:\\}) is read as
     * such. Every segment must look like a name ({@link #nameLike}). A webhook or a download link, whose last segment is
     * the secret, is not. The path token around the run is read across dots (a dot ends the run's character class), and
     * the memo {@code st}: {@code st[2]} is the end of an accepted path, {@code st[3]} the end of a token judged not to
     * be one.
     */
    static boolean pathRun(String s, int start, int end, int[] st) {
        boolean windows = start > 0 && s.charAt(start - 1) == '\\' || end < s.length() && s.charAt(end) == '\\';
        if (windows) {
            boolean path = afterDrive(s, start) && nameLike(s, start, end, false);
            if (path) st[2] = end;
            return path;
        }
        if (start < st[3]) return false;
        for (int i = start; i < end; i++) if (!tokenChar(s.charAt(i))) return false;
        int ts = start;
        while (ts > 0 && tokenChar(s.charAt(ts - 1))) ts--;
        int te = end;
        while (te < s.length() && tokenChar(s.charAt(te))) te++;
        // The scheme's // is no part of the path: a host then reads as a first, relative segment.
        if (ts > 0 && s.charAt(ts - 1) == ':' && s.startsWith("//", ts)) ts += 2;
        boolean rooted = s.charAt(ts) == '/' || s.charAt(ts) == '~' && ts + 1 < te && s.charAt(ts + 1) == '/';
        int slashes = 0;
        for (int i = ts; i < te && slashes < 2; i++) if (s.charAt(i) == '/') slashes++;
        boolean path = slashes >= 2 && nameLike(s, ts, te, !rooted);
        if (path) st[2] = te;
        else st[3] = te;
        return path;
    }

    /** A character a file path is made of (no {@code +} or {@code =}: a run holding one is not a path). */
    private static boolean tokenChar(char c) {
        return word(c) || c == '.' || c == '-' || c == '/' || c == '~';
    }

    /** Whether a drive or a UNC prefix opens the path token the run belongs to (a JSON escape such as {@code \n} does not). */
    private static boolean afterDrive(String s, int start) {
        int i = start;
        // A bound that keeps the walk back linear over a text of runs joined by backslashes.
        while (i > 0 && start - i < 260 && winChar(s.charAt(i - 1))) i--;
        return start - i >= 3 && s.charAt(i + 1) == ':' && s.charAt(i + 2) == '\\' && Character.isLetter(s.charAt(i))
                || start - i >= 2 && s.charAt(i) == '\\' && s.charAt(i + 1) == '\\';
    }

    private static boolean winChar(char c) {
        return word(c) || c == '\\' || c == ':' || c == '.' || c == '-' || c == '$' || c == '~';
    }

    private static boolean word(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_';
    }

    /**
     * Whether each segment of {@code [from, to)}, between slashes, is short or looks like a file or folder name. In a
     * relative path every segment of 5 characters or more must read as words, so a base64 key with slashes in it is not
     * taken for a path.
     */
    private static boolean nameLike(String s, int from, int to, boolean relative) {
        int i = from;
        while (i <= to) {
            int j = i;
            while (j < to && s.charAt(j) != '/') j++;
            if (!segmentOk(s, i, j, relative)) return false;
            i = j + 1;
        }
        return true;
    }

    /**
     * A segment over 12 characters passes with a dot (an extension), made of letters, as an identifier with a separator
     * (but not a hex-like one: a UUID is a capability) or as words ({@link #wordLike}).
     */
    private static boolean segmentOk(String s, int i, int j, boolean relative) {
        boolean dot = false;
        boolean letters = true;
        boolean identifier = true;
        boolean separator = false;
        for (int k = i; k < j; k++) {
            char c = s.charAt(k);
            dot |= c == '.';
            letters &= c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
            identifier &= c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_';
            separator |= c == '-' || c == '_';
        }
        int len = j - i;
        boolean hexLike = hexLike(s, i, j);
        if (len > 12 && !dot && !letters && !(identifier && separator && !hexLike) && !wordLike(s, i, j, true))
            return false;
        return !relative || len <= 4 || !hexLike && wordLike(s, i, j, false);
    }

    /**
     * Whether 70% of a segment of 24 characters or more is made of chunks (split at {@code -} and {@code _}) of 4 or
     * more hex digits: a hash, a UUID. A chunk of 4 characters or more that is not hex ({@code boot-<uuid>}) names a
     * folder, not a capability.
     */
    private static boolean hexLike(String s, int from, int to) {
        if (to - from < 24) return false;
        int hex = 0;
        int i = from;
        while (i <= to) {
            int j = i;
            boolean all = true;
            while (j < to && s.charAt(j) != '-' && s.charAt(j) != '_') {
                char c = s.charAt(j++);
                all &= c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
            }
            if (j - i >= 4 && !all) return false;
            if (all && j - i >= 4) hex += j - i;
            i = j + 1;
        }
        return hex * 10 >= (to - from) * 7;
    }

    /**
     * Whether the segment reads as words: letters in chunks of an optional capital and 2 or more lower-case letters
     * ({@code Http2ConnectionHandlerTest}, {@code floor42-server}) cover at least 80% of its letters, and its digits are
     * few when {@code few} is set. Random text has lower-case runs too short to pass.
     */
    private static boolean wordLike(String s, int from, int to, boolean few) {
        int letters = 0;
        int covered = 0;
        int digits = 0;
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            boolean upper = c >= 'A' && c <= 'Z';
            if (c >= '0' && c <= '9') digits++;
            if (!upper && !(c >= 'a' && c <= 'z')) {
                i++;
                continue;
            }
            int low = upper ? i + 1 : i;
            while (low < to && s.charAt(low) >= 'a' && s.charAt(low) <= 'z') low++;
            int chunk = low - i;
            boolean words = chunk - (upper ? 1 : 0) >= 2;
            letters += words ? chunk : 1;
            covered += words ? chunk : 0;
            i = words ? low : i + 1;
        }
        return letters > 0 && covered * 10 >= letters * 8 && (!few || digits <= 3);
    }
}
