package dev.floor42.server.app;

import dev.floor42.domain.ReviewClaim;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The reviewer's {@code RESERVATION} and {@code NOTE} lines, read as written (ADR-0053). Whether a reservation stands is
 * the domain's call ({@link dev.floor42.domain.ReviewCalibration}): this only reads. A line may be decorated (a list
 * number, a bullet, a quote mark, bold) and the key's case does not matter. A reservation's label may be {@code C2},
 * {@code (C2)} or a bare {@code 2}, followed by a colon or a dash; its {@code CHANGE:} may sit on the next line. A
 * reservation line with no separator is kept as one that cites nothing, never dropped. Lines inside fenced code blocks
 * are not read.
 */
final class ReviewClaims {

    /** A list number, a bullet or a quote mark, then emphasis, before the key. */
    private static final String DECORATION = "^[ \\t]*(?:>[ \\t]*)?(?:(?:[-*+\u2022]|\\d{1,3}[.)])[ \\t]+)?[*_]{0,3}";

    /** A separator: a colon, or a dash with a space on each side. */
    private static final String SEPARATOR = "(?::|[ \\t][-\u2013\u2014](?=[ \\t]))";

    /** Key, an optional label, a separator, then the value. */
    private static final Pattern LINE = Pattern.compile(
            DECORATION + "(RESERVATION|NOTE)\\b[*_]{0,3}[ \\t]*?([^:\\r\\n]{0,60}?)[*_]{0,3}[ \\t]*" + SEPARATOR
                    + "[*_]{0,3}[ \\t]*(.*?)[ \\t]*$",
            Pattern.CASE_INSENSITIVE);

    /** A reservation line with no separator at all. */
    private static final Pattern BARE =
            Pattern.compile(DECORATION + "RESERVATION\\b[*_]{0,3}[ \\t]+(.+?)[ \\t]*$", Pattern.CASE_INSENSITIVE);

    /** A {@code CHANGE:} line on its own, for the reservation just above it. */
    private static final Pattern CHANGE_LINE = Pattern.compile(
            DECORATION + "CHANGE[*_]{0,3}[ \\t]*:[*_]{0,3}[ \\t]*(.*?)[ \\t]*$", Pattern.CASE_INSENSITIVE);

    /** A label that names a criterion: {@code C2}, {@code (C2)}, {@code 2}, {@code criterion 2}. */
    private static final Pattern CRITERION = Pattern.compile("(?i)\\(?(?:C|criterion[ \\t]*)?#?\\d{1,3}\\)?");

    /** The label written after the separator instead of before it: {@code RESERVATION: C2: ...}. */
    private static final Pattern LEADING_LABEL =
            Pattern.compile("(?i)^\\(?(C\\d{1,3})\\)?(?:\\s*(?::|[-–—.)])|(?=\\s))\\s*(.*)$");

    /** A value that says there is nothing: {@code RESERVATION: none}. */
    private static final Pattern NOTHING = Pattern.compile("(?i)(none|n/a|-|nothing|no reservations?|no change)\\.?");

    /** The key itself; {@link #changeStart} extends a match back over the {@code |}, blanks and stars before it. */
    private static final Pattern CHANGE = Pattern.compile("(?i)(?<![A-Za-z0-9_])CHANGE\\**\\s*:\\s*");

    /** A run of blanks longer than this is cut to it: the line patterns backtrack across blank runs. */
    private static final int MAX_BLANKS = 32;

    private static final Pattern FENCE = Pattern.compile("^[ \\t]*(```|~~~)");

    private ReviewClaims() {}

    static List<ReviewClaim> of(String text) {
        List<ReviewClaim> out = new ArrayList<>();
        boolean fenced = false;
        // The claim a CHANGE line may complete: only the one read on the line just before (blank lines skipped).
        ReviewClaim open = null;
        for (String line : (text == null ? "" : text).split("\\R")) {
            if (FENCE.matcher(line).find()) {
                fenced = !fenced;
                open = null;
                continue;
            }
            if (fenced || line.isBlank()) continue;
            int before = out.size();
            read(squeeze(line), out, open);
            open = out.size() > before ? out.getLast() : null;
        }
        return out;
    }

    /**
     * A line as the patterns read it (each run of spaces and tabs cut to {@link #MAX_BLANKS}, tabs read as spaces) and
     * where each of its characters stands in the original: what is stored is cut from the original, never from the copy.
     */
    record Squeezed(String original, String text, int[] at) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Squeezed p
                    && java.util.Objects.equals(original, p.original)
                    && java.util.Objects.equals(text, p.text)
                    && java.util.Arrays.equals(at, p.at);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(original, text, java.util.Arrays.hashCode(at));
        }

        @Override
        public String toString() {
            return "Squeezed[" + "original=" + original + ", text=" + text + "]";
        }

        String group(Matcher m, int g) {
            return original.substring(at[m.start(g)], at[m.end(g)]);
        }
    }

    static Squeezed squeeze(String line) {
        StringBuilder b = new StringBuilder(line.length());
        int[] at = new int[line.length() + 1];
        int run = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            run = c == ' ' || c == '\t' ? run + 1 : 0;
            if (run > MAX_BLANKS) continue;
            at[b.length()] = i;
            b.append(run > 0 ? ' ' : c);
        }
        at[b.length()] = line.length();
        return new Squeezed(line, b.toString(), at);
    }

    /** Where a match of {@link #CHANGE} starts once the optional {@code |}, blanks and stars before it are counted. */
    private static int changeStart(String value, int coreStart) {
        int i = coreStart;
        while (i > 0 && value.charAt(i - 1) == '*') i--;
        while (i > 0 && isRegexSpace(value.charAt(i - 1))) i--;
        if (i > 0 && value.charAt(i - 1) == '|') i--;
        return i;
    }

    private static boolean isRegexSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r';
    }

    private static void read(Squeezed line, List<ReviewClaim> out, ReviewClaim open) {
        Matcher m = LINE.matcher(line.text());
        if (m.matches()) {
            String value = line.group(m, 3).replace("**", "").strip();
            String label = line.group(m, 2).replace("*", "").replace("_", "").strip();
            if (!label.isEmpty() && !CRITERION.matcher(label).matches()) {
                value = value.isEmpty() ? label : label + ": " + value;
                label = "";
            }
            if (value.isEmpty() || NOTHING.matcher(value).matches()) return;
            if (m.group(1).toUpperCase(Locale.ROOT).equals("NOTE")) out.add(ReviewClaim.note(value));
            else out.add(reservation(clean(label), value));
            return;
        }
        Matcher bare = BARE.matcher(line.text());
        if (bare.matches()) {
            out.add(reservation("", line.group(bare, 1).replace("**", "").strip()));
            return;
        }
        Matcher change = CHANGE_LINE.matcher(line.text());
        String what = change.matches() ? line.group(change, 1).replace("**", "").strip() : "";
        if (open != null
                && open == out.getLast()
                && pendingChange(open)
                && !what.isEmpty()
                && !NOTHING.matcher(what).matches()) {
            out.removeLast();
            out.add(ReviewClaim.reservation(open.criterion(), open.text(), what));
        }
    }

    private static boolean pendingChange(ReviewClaim c) {
        return c.reservation() && (c.change() == null || c.change().isBlank());
    }

    /** A reservation: its label ({@code 2} reads as {@code C2}), what falls short, what to change. */
    private static ReviewClaim reservation(String label, String value) {
        if (label.isEmpty()) {
            Matcher lead = LEADING_LABEL.matcher(value);
            if (lead.matches()) {
                label = lead.group(1);
                value = lead.group(2);
            }
        }
        Matcher change = CHANGE.matcher(value);
        String gap = value;
        String what = null;
        if (change.find()) {
            gap = value.substring(0, changeStart(value, change.start())).strip();
            what = value.substring(change.end()).strip();
        }
        return ReviewClaim.reservation(label.isEmpty() ? null : label.toUpperCase(Locale.ROOT), gap, what);
    }

    /** {@code (C2)}, {@code criterion 2} and {@code 2} all read as {@code C2}. */
    private static String clean(String label) {
        if (label.isEmpty()) return "";
        String l =
                label.replaceAll("[()\\[\\]#\\s]", "").toUpperCase(Locale.ROOT).replace("CRITERION", "C");
        return l.startsWith("C") ? l : "C" + l;
    }
}
