package dev.floor42.domain;

import java.util.Map;
import java.util.Objects;

/**
 * What Floor42 means by a sentence it wrote, so a screen can write it in its own language (ADR-0021, lot 3): a code and
 * the values that fill it. The English sentence stays beside it and is what any reader without the code sees.
 */
public record Phrase(String code, Map<String, String> params) {

    public Phrase {
        Objects.requireNonNull(code, "code");
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public static Phrase of(String code, String... keysAndValues) {
        if (keysAndValues.length % 2 != 0) throw new IllegalArgumentException("keys and values come in pairs");
        var params = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            params.put(keysAndValues[i], keysAndValues[i + 1] == null ? "" : keysAndValues[i + 1]);
        }
        return new Phrase(code, params);
    }
}
