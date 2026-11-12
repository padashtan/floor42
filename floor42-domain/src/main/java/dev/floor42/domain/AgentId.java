package dev.floor42.domain;

public record AgentId(String value) implements Identifier {
    public AgentId {
        Identifier.check(value, "agent");
    }

    @Override
    public String toString() {
        return value;
    }
}
