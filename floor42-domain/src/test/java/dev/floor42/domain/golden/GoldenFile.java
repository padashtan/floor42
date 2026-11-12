package dev.floor42.domain.golden;

import dev.floor42.domain.event.EventEnvelope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A golden master kept under {@code src/test/resources/golden}: recorded once from the code as it was, compared on every
 * run. {@code -Dfloor42.golden.record=true} records it again; a mismatch writes what was produced under
 * {@code target/golden-actual} for a diff.
 */
public final class GoldenFile {

    public static final String RECORD = "floor42.golden.record";

    private GoldenFile() {}

    public static boolean recording() {
        return Boolean.getBoolean(RECORD);
    }

    /**
     * Records or compares.
     *
     * @return null when the text matches (or was just recorded), else why not
     */
    public static String check(String name, String actual) {
        Path expected = Path.of("src", "test", "resources", "golden").resolve(name);
        try {
            if (recording()) {
                Files.createDirectories(expected.getParent());
                Files.writeString(expected, actual, StandardCharsets.UTF_8);
                return null;
            }
            if (!Files.exists(expected))
                return "no golden master " + expected + ": record it with -D" + RECORD + "=true";
            String wanted = Files.readString(expected, StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (wanted.equals(actual)) return null;
            Path written = Path.of("target", "golden-actual").resolve(name);
            Files.createDirectories(written.getParent());
            Files.writeString(written, actual, StandardCharsets.UTF_8);
            return name + " differs from its golden master: first difference at line " + firstDifference(wanted, actual)
                    + "; produced " + written.toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int firstDifference(String a, String b) {
        String[] x = a.split("\n", -1);
        String[] y = b.split("\n", -1);
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            if (!x[i].equals(y[i])) return i + 1;
        }
        return Math.min(x.length, y.length) + 1;
    }

    /**
     * One event per line, its id replaced by its position in the journal ({@code first} for the first of these): ids
     * are random, positions are not.
     */
    public static String events(List<EventEnvelope> events, int first) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < events.size(); i++) {
            EventEnvelope e = events.get(i);
            out.append("  #")
                    .append(first + i)
                    .append(' ')
                    .append(e.occurredAt())
                    .append(' ')
                    .append(e.origin())
                    .append(' ')
                    .append(e.actor().kind())
                    .append(':')
                    .append(e.actor().id())
                    .append(" mission=")
                    .append(e.mission() == null ? "-" : e.mission().value())
                    .append(" auth=")
                    .append(e.authorizationRef())
                    .append(" cause=")
                    .append(e.causationId())
                    .append(' ')
                    .append(Canonical.of(e.event()))
                    .append('\n');
        }
        return out.toString();
    }
}
