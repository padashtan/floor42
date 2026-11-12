package dev.floor42.domain;

public record IncidentId(String value) implements Identifier {
    public IncidentId {
        Identifier.check(value, "incident");
    }

    @Override
    public String toString() {
        return value;
    }
}
