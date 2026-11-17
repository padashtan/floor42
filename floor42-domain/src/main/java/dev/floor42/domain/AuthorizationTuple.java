package dev.floor42.domain;

import java.util.Objects;

/** One relationship tuple of the broker authorization: {@code user} has {@code relation} on {@code object}, ids only. */
public record AuthorizationTuple(String user, String relation, String object) {

    public AuthorizationTuple {
        Objects.requireNonNull(user);
        Objects.requireNonNull(relation);
        Objects.requireNonNull(object);
    }
}
