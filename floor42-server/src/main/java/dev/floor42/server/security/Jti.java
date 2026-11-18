package dev.floor42.server.security;

import java.util.regex.Pattern;

/**
 * The shape of a token id (jti) as the identity provider issues it: a UUID, with an optional type prefix such as
 * {@code onrtac:}. A jti is an identifier, not a secret: {@link SecretMasker} would read it as a long run, so the places
 * that carry one on purpose (the broker's result line, the proof dossier) keep a value of this shape and nothing else.
 */
public final class Jti {

    public static final Pattern SHAPE = Pattern.compile("(?:[a-z]{2,10}:)?[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");

    private Jti() {}

    public static boolean isIssued(String s) {
        return s != null && SHAPE.matcher(s).matches();
    }
}
