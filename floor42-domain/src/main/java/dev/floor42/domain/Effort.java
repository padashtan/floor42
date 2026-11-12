package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * How hard a model thinks, as a command names it. Only at command input: agents and events keep the plain string, so a
 * value journaled before this type existed (or a free one) still replays.
 */
enum Effort {
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX;

    /** The word journaled and shown. */
    String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Empty for no effort (blank); refused when the word is none of the known ones, matched exactly. */
    static Optional<Effort> parse(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String word = text.strip();
        Optional<Effort> effort =
                Arrays.stream(values()).filter(e -> e.value().equals(word)).findFirst();
        require(effort.isPresent(), Refusal.INVALID_ARGUMENT, "Effort is low, medium, high, xhigh or max");
        return effort;
    }
}
