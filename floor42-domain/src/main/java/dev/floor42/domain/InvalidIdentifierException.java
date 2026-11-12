package dev.floor42.domain;

/** An identifier that is not one (see {@link Identifier#FORMAT}): it names nothing, so the API answers 404. */
public final class InvalidIdentifierException extends IllegalArgumentException {

    public InvalidIdentifierException(String message) {
        super(message);
    }
}
