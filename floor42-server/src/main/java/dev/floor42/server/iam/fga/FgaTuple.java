package dev.floor42.server.iam.fga;

import java.util.Objects;

/** One relationship tuple: {@code user} has {@code relation} on {@code object}, e.g. agent:ada, granted, resource:jira. */
public record FgaTuple(String user, String relation, String object) {

    public FgaTuple {
        Objects.requireNonNull(user);
        Objects.requireNonNull(relation);
        Objects.requireNonNull(object);
    }
}
