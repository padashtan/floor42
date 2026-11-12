package dev.floor42.server.fixture.ownership;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A planted entity part for {@code DomainOwnershipRulesTest}: its own methods mirror the collections it wraps, beyond
 * add, remove, set and put. Only {@link LedgerOwner} may call them.
 */
public final class Ledger {

    final List<String> lines = new ArrayList<>();
    private final Map<String, Integer> counts = new HashMap<>();
    private final Deque<String> stack = new ArrayDeque<>();
    String label;

    public int size() {
        return lines.size();
    }

    public boolean removeIf(Predicate<String> which) {
        return lines.removeIf(which);
    }

    public Integer computeIfAbsent(String key) {
        return counts.computeIfAbsent(key, k -> 0);
    }

    public Integer merge(String key) {
        return counts.merge(key, 1, Integer::sum);
    }

    public void push(String line) {
        stack.push(line);
    }

    public String pop() {
        return stack.pop();
    }
}
