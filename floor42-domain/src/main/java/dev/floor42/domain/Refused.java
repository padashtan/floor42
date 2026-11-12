package dev.floor42.domain;

import java.util.Optional;

/**
 * What a command would meet, as a value rather than thrown: its stable code (a {@link Refusal}'s, or {@code
 * MISSING_<FIELD>}) and the message the command itself would give. For the queries that tell in advance whether a
 * command would pass, such as the next steps of the end-of-mission report (ADR-0039).
 */
public record Refused(String code, String message) {

    /** Empty when the check passes; else what it refused. A check records nothing. */
    static Optional<Refused> by(Runnable check) {
        try {
            check.run();
            return Optional.empty();
        } catch (DomainException e) {
            // Handled: the refusal is this query's answer, as a value
            return Optional.of(new Refused(e.code(), e.getMessage()));
        }
    }
}
