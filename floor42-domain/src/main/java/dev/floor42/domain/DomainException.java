package dev.floor42.domain;

import java.util.Arrays;
import java.util.Optional;

/** A command was refused because it would break a business rule. The code is stable and meant for API clients. */
public final class DomainException extends RuntimeException {

    private final String code;

    public DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public DomainException(Refusal refusal, String message) {
        this(refusal.code(), message);
    }

    /** A refusal that comes from a lower failure (a parse, the disk): the cause stays for the log. */
    public DomainException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public DomainException(Refusal refusal, String message, Throwable cause) {
        this(refusal.code(), message, cause);
    }

    /** The refusal behind the code; empty for a missing field ({@code MISSING_<FIELD>}), which has no constant. */
    public Optional<Refusal> refusal() {
        return Arrays.stream(Refusal.values())
                .filter(r -> r.code().equals(code))
                .findFirst();
    }

    public String code() {
        return code;
    }

    static void require(boolean condition, Refusal refusal, String message) {
        if (!condition) {
            throw new DomainException(refusal, message);
        }
    }

    /** For the codes built from a field's name ({@code MISSING_TITLE}): every other code is a {@link Refusal}. */
    static void require(boolean condition, String code, String message) {
        if (!condition) {
            throw new DomainException(code, message);
        }
    }
}
