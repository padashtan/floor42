package dev.floor42.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Who issues a command, in which mode, when, and in reaction to what. Every event recorded by the command inherits it.
 *
 * @param causationId id of the event or external signal (tool_use id, hook call) that led to this command; may be null
 */
public record CommandContext(Actor actor, Origin origin, Instant now, String causationId) {

    public CommandContext {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(now, "now");
    }
}
