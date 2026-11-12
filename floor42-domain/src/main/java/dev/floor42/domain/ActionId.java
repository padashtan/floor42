package dev.floor42.domain;

public record ActionId(String value) implements Identifier {
    public ActionId {
        Identifier.check(value, "action");
    }

    @Override
    public String toString() {
        return value;
    }
}
