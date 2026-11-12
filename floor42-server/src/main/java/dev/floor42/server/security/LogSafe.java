package dev.floor42.server.security;

/** A value as a log line may hold it: a path, a name or an id that came with a request never starts a line of its own. */
public final class LogSafe {

    private LogSafe() {}

    /**
     * {@code value} as text, each control character (line break, escape, backspace) and each Unicode line or
     * paragraph separator replaced with {@code ?}. Letters of every language stay: a folder may be named in French.
     */
    public static String of(Object value) {
        // "Everything but the harmless characters": the form CodeQL's java/log-injection reads as a sanitizer.
        return String.valueOf(value).replaceAll("[^\\P{Cc}&&\\P{Zl}&&\\P{Zp}]", "?");
    }
}
