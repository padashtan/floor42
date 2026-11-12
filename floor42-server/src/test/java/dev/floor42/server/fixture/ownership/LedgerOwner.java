package dev.floor42.server.fixture.ownership;

/** The planted owner of {@link Ledger}: everything it does is allowed. */
public final class LedgerOwner {

    private final Ledger ledger = new Ledger();

    public void keep(String line) {
        ledger.lines.add(line);
        ledger.label = line;
        ledger.push(line);
        ledger.merge(line);
        ledger.computeIfAbsent(line);
        ledger.removeIf(String::isBlank);
        ledger.pop();
    }
}
