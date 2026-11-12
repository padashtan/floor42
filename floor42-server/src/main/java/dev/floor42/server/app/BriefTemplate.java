package dev.floor42.server.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fixed prose of a brief, kept in {@code src/main/resources/briefs/<name>.txt} so a change of wording reads as a
 * change of text in review. A placeholder is {@code {{name}}}; every one must be given a value and every value must be
 * used, so a template and its code cannot drift apart silently. Values are inserted as they are: a value holding
 * {@code {{x}}} (an agent's deliverable may) is never read as a placeholder.
 */
final class BriefTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z]+)}}");
    private static final Map<String, String> LOADED = new ConcurrentHashMap<>();

    private final String name;
    private final Map<String, String> values = new HashMap<>();

    private BriefTemplate(String name) {
        this.name = name;
    }

    /** The template of this name, to fill with {@link #with} then {@link #render}. */
    static BriefTemplate named(String name) {
        return new BriefTemplate(name);
    }

    /** A template with no placeholder, as it is. */
    static String text(String name) {
        return named(name).render();
    }

    /**
     * A placeholder's value, written as {@link String#valueOf(Object)} writes it, {@code null} included, as {@code
     * String.formatted} did before the templates.
     */
    BriefTemplate with(String placeholder, Object value) {
        if (values.put(placeholder, String.valueOf(value)) != null)
            throw new IllegalArgumentException("Brief " + name + ": {{" + placeholder + "}} given twice");
        return this;
    }

    /** The template filled in, in one pass over the template. */
    String render() {
        String template = LOADED.computeIfAbsent(name, BriefTemplate::load);
        Set<String> unused = new LinkedHashSet<>(values.keySet());
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() * 2);
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null)
                throw new IllegalStateException("Brief " + name + ": no value for {{" + m.group(1) + "}}");
            unused.remove(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        if (!unused.isEmpty()) throw new IllegalStateException("Brief " + name + ": unused values " + unused);
        return out.toString();
    }

    /** Line ends are read as LF whatever the checkout did: the agents get the same bytes on every machine. */
    private static String load(String name) {
        String path = "/briefs/" + name + ".txt";
        try (InputStream in = BriefTemplate.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException(path + " is missing from the build");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
