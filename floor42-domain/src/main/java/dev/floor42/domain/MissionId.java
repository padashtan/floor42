package dev.floor42.domain;

public record MissionId(String value) implements Identifier {
    public MissionId {
        Identifier.check(value, "mission");
    }

    @Override
    public String toString() {
        return value;
    }
}
