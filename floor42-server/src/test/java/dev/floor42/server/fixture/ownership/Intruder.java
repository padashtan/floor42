package dev.floor42.server.fixture.ownership;

/** A planted violation for {@code DomainOwnershipRulesTest}: another class changes {@link Ledger}. */
public final class Intruder {

    private Intruder() {}

    public static void removeIf(Ledger ledger) {
        ledger.removeIf(l -> true);
    }

    public static void merge(Ledger ledger) {
        ledger.merge("x");
    }

    public static void push(Ledger ledger) {
        ledger.push("x");
        ledger.pop();
    }

    public static void computeIfAbsent(Ledger ledger) {
        ledger.computeIfAbsent("x");
    }

    public static void reachIntoTheCollection(Ledger ledger) {
        ledger.lines.clear();
    }

    public static void writeAField(Ledger ledger) {
        ledger.label = "forged";
    }
}
