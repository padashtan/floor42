package dev.floor42.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Who issues a command, in which mode, when, and in reaction to what. Every event recorded by the command inherits it.
 *
 * @param causationId id of the event or external signal (tool_use id, hook call) that led to this command; may be null
 * @param assured true when the daemon runs where a stronger proof exists (IAM mode, ADR-0081): a risky act of a real
 *     mission then needs the signatures {@link AssurancePolicy} asks for, or the domain refuses it
 */
public record CommandContext(Actor actor, Origin origin, Instant now, String causationId, boolean assured) {

    public CommandContext {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(now, "now");
    }

    public CommandContext(Actor actor, Origin origin, Instant now, String causationId) {
        this(actor, origin, now, causationId, false);
    }

    public CommandContext withAssurance() {
        return new CommandContext(actor, origin, now, causationId, true);
    }
}
