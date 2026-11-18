package dev.floor42.server.security;

import java.util.regex.Pattern;

/** The words after which a value is masked whatever it looks like (the bare-key-word rule of {@link SecretMasker}). */
final class SecretWords {

    private SecretWords() {}

    /** Names that unambiguously name a secret: a value after one is masked whatever it looks like. */
    private static final Pattern EXPLICIT = Pattern.compile(
            "(?i)[A-Za-z0-9_-]*(?:password|passphrase|api[-_]?key|private[-_]?key|access[-_]?key|secret[-_]?key|client[-_]?secret)"
                    + "\\W*");

    /** The same words (and bare secret, token) as a command line flag: the flag makes them unambiguous. */
    private static final Pattern FLAG =
            Pattern.compile("(?i)--(?:password|passphrase|secret|token|api[-_]?key|client[-_]?secret)\\W*");

    /** Words that follow "password" in prose ("password reset flow"): the value is not one of them. */
    private static final Pattern PROSE = Pattern.compile(
            "(?i)(?:reset|flow|policy|manager|field|change|hash|hashing|is|was|must|should|and|or|in|for|to|of|with|the"
                    + "|a|an|required|expired|strength|rules|length|input|page|form|prompt|check|validation|complexity"
                    + "|hint|file|store|vault)(?![A-Za-z0-9_])");

    /** Whether the head at {@code start} is an unambiguous form: a flag, or one of the explicit names. */
    static boolean explicit(String s, int start, int from, int end) {
        int b = start;
        while (b > 0
                && (Character.isLetterOrDigit(s.charAt(b - 1))
                        || s.charAt(b - 1) == (char) 95
                        || s.charAt(b - 1) == '-')) b--;
        if (FLAG.matcher(s).region(b, from).matches()) return true;
        return EXPLICIT.matcher(s).region(b, from).matches()
                && !PROSE.matcher(s).region(Math.min(from, end), end).lookingAt();
    }
}
