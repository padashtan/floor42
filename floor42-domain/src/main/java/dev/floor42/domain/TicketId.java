package dev.floor42.domain;

public record TicketId(String value) implements Identifier {
    public TicketId {
        Identifier.check(value, "ticket");
    }

    @Override
    public String toString() {
        return value;
    }
}
