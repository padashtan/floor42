package dev.floor42.domain.event;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** The concrete event types, found through the sealed hierarchy however deep it goes. */
public final class DomainEvents {

    private DomainEvents() {}

    /**
     * Every concrete event type by simple name, the name the journal stores. Two types with one simple name could not
     * be told apart in the journal: that is refused.
     */
    public static Map<String, Class<? extends DomainEvent>> types() {
        Map<String, Class<? extends DomainEvent>> types = new TreeMap<>();
        collect(DomainEvent.class, types);
        return Collections.unmodifiableMap(types);
    }

    private static void collect(Class<?> type, Map<String, Class<? extends DomainEvent>> into) {
        for (Class<?> c : type.getPermittedSubclasses()) {
            if (c.isSealed()) {
                collect(c, into);
                continue;
            }
            Class<?> previous = into.put(c.getSimpleName(), c.asSubclass(DomainEvent.class));
            if (previous != null) throw new IllegalStateException("Two event types named " + c.getSimpleName());
        }
    }
}
