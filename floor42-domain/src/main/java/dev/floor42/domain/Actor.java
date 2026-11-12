package dev.floor42.domain;

import java.util.Objects;

/** Who caused something: the human director, an agent, or Floor42 itself (runtime, scheduler). */
public record Actor(Kind kind, String id) {

    public enum Kind {
        DIRECTOR,
        AGENT,
        SYSTEM
    }

    public Actor {
        Objects.requireNonNull(kind, "kind");
        Identifier.check(id, "actor");
    }

    public static Actor director() {
        return new Actor(Kind.DIRECTOR, "director");
    }

    public static Actor agent(AgentId agent) {
        return new Actor(Kind.AGENT, agent.value());
    }

    public static Actor system() {
        return new Actor(Kind.SYSTEM, "floor42");
    }

    public boolean isDirector() {
        return kind == Kind.DIRECTOR;
    }

    public boolean is(AgentId agent) {
        return kind == Kind.AGENT && id.equals(agent.value());
    }
}
