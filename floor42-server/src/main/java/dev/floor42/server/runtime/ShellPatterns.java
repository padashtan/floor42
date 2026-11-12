package dev.floor42.server.runtime;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text tests of {@link ShellGuard}, read from agent-controlled command lines, so each one runs in linear time on
 * any input: the command is cut into blank-separated words and read once from the end (no nested quantifier, no
 * backtracking), which a ReDoS (a stalled or overflowing guard) made unsafe. {@link #gitPush} and {@link
 * #claudeConfig} accept what the regular expressions they replace accepted (and, as a fuzz run found, a line where a
 * combining mark follows the verb, which the old word boundary read as part of the word), and {@link #stopsJava} all
 * of it and a little more ({@code cmd /ctaskkill}, the PowerShell aliases {@code gps} and {@code spps}): {@code
 * ShellPatternsDifferentialTest} holds the old expressions and compares. Like {@link ShellGuard}, this is a tripwire
 * for plain commands, not a boundary.
 */
final class ShellPatterns {

    private static final Pattern BLANKS = Pattern.compile("\\s+");

    /** What may stand right before a program name inside one word: {@code ;git}, {@code /usr/bin/git}, {@code "git}. */
    private static final String DELIMITERS = ";|&(`'\"/";

    /**
     * {@code \b} written as ASCII look-arounds: the JDK's {@code \b} reads back over a whole run of combining marks at
     * each position, which is quadratic on a command made of marks (20000 of them took 40 s).
     */
    private static final String START = "(?<![A-Za-z0-9_])";

    private static final String END = "(?![A-Za-z0-9_])";

    private record Kill(Pattern head, Pattern tail) {}

    /** A program that stops processes, then (on the same line) the Java process it names. */
    private static final List<Kill> JAVA_KILLS = List.of(
            // No leading boundary on taskkill: {@code cmd /ctaskkill /im java.exe} is one word to cmd.
            kill("taskkill" + END, START + "java"),
            kill(START + "(?:stop-process|spps)" + END, "-name\\s+['\"]?java"),
            kill(START + "pkill" + END, START + "java"),
            kill(START + "killall" + END, START + "java"),
            kill(START + "(?:get-process|gps)\\s+['\"]?java" + END, "\\|\\s*(?:stop-process|spps|kill)" + END));

    private ShellPatterns() {}

    private static Kill kill(String head, String tail) {
        return new Kill(Pattern.compile(head), Pattern.compile(tail));
    }

    /** {@code git}, any options (a {@code -c} takes the next word as its value), then {@code push}. */
    static boolean gitPush(String command) {
        String[] words = BLANKS.split(command);
        boolean[] reach = reach(words, w -> verb(w, "push"), true);
        for (int h = 0; h < words.length; h++) if (reach[h + 1] && gitWord(words[h])) return true;
        return false;
    }

    /** {@code claude}, any options, then {@code plugin}, {@code plugins} or {@code mcp}. */
    static boolean claudeConfig(String command) {
        String[] words = BLANKS.split(command);
        boolean[] reach = reach(words, w -> verb(w, "plugins") || verb(w, "plugin") || verb(w, "mcp"), false);
        for (int h = 0; h < words.length; h++) if (reach[h + 1] && claudeWord(words[h])) return true;
        return false;
    }

    /** A process killer followed, on the same line, by Java. */
    static boolean stopsJava(String command) {
        for (Kill k : JAVA_KILLS) if (followed(command, k)) return true;
        return false;
    }

    /**
     * Linear: a head whose line holds no tail leaves no tail for a later head on that line either, and the first tail
     * from one head on is still the first one from a later head.
     */
    private static boolean followed(String s, Kill k) {
        Matcher head = k.head().matcher(s);
        Matcher tail = k.tail().matcher(s);
        int tailAt = -1;
        int from = 0;
        while (from <= s.length() && head.find(from)) {
            int end = head.end();
            if (tailAt < end) tailAt = tail.find(end) ? tail.start() : Integer.MAX_VALUE;
            if (tailAt == Integer.MAX_VALUE) return false;
            int eol = lineEnd(s, end);
            if (tailAt <= eol) return true;
            from = eol;
        }
        return false;
    }

    private static int lineEnd(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\u0085' || c == ' ' || c == ' ') return i;
        }
        return s.length();
    }

    /** {@code reach[i]}: starting at word i, options (and a {@code -c} with its value) lead to a verb. */
    private static boolean[] reach(String[] words, Predicate<String> verb, boolean dashCTakesValue) {
        boolean[] reach = new boolean[words.length + 2];
        for (int i = words.length - 1; i >= 0; i--) {
            boolean option = option(words[i]);
            boolean pair = dashCTakesValue && (words[i].equals("-c") || words[i].equals("-C"));
            reach[i] = verb.test(words[i]) || option && (reach[i + 1] || pair && reach[i + 2]);
        }
        return reach;
    }

    /** {@code -x}, {@code --long-name}, optionally {@code =value}. */
    private static boolean option(String w) {
        if (w.length() < 2 || w.charAt(0) != '-') return false;
        int i = 1;
        while (i < w.length() && (word(w.charAt(i)) || w.charAt(i) == '-')) i++;
        if (i == 1) return false;
        return i == w.length() || w.charAt(i) == '=' && i + 1 < w.length();
    }

    /** The word starts with the verb and ends it there (a regex word boundary). */
    private static boolean verb(String w, String verb) {
        return w.startsWith(verb) && (w.length() == verb.length() || !word(w.charAt(verb.length())));
    }

    private static boolean word(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_';
    }

    /** {@code git} or {@code git.exe}, alone or after a delimiter in the same word. */
    private static boolean gitWord(String w) {
        return programWord(w.endsWith(".exe") ? w.substring(0, w.length() - 4) : w, "git");
    }

    /** {@code claude}, {@code claude.exe} or {@code claude.cmd}, with an optional closing quote. */
    private static boolean claudeWord(String w) {
        if (w.endsWith("'") || w.endsWith("\"")) w = w.substring(0, w.length() - 1);
        if (w.endsWith(".exe") || w.endsWith(".cmd")) w = w.substring(0, w.length() - 4);
        return programWord(w, "claude");
    }

    private static boolean programWord(String w, String program) {
        int e = w.length() - program.length();
        return e >= 0 && w.endsWith(program) && (e == 0 || DELIMITERS.indexOf(w.charAt(e - 1)) >= 0);
    }
}
