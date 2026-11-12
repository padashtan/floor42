package dev.floor42.server.journal;

import dev.floor42.domain.event.EventEnvelope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Append-only store of events, the source of truth of the company. Rows this version cannot read are left out of every
 * read and listed by {@link #replay()} (ADR-0042); they are never changed or deleted.
 */
public interface Journal {

    record Entry(long sequence, EventEnvelope envelope) {}

    /** The whole journal in order: the rows read, and those left out, both by sequence. */
    record Read(List<Entry> entries, List<Unreadable> unreadable) {
        public Read {
            entries = entries == null ? null : Collections.unmodifiableList(new ArrayList<>(entries));
            unreadable = unreadable == null ? null : Collections.unmodifiableList(new ArrayList<>(unreadable));
        }
    }

    /** Stores all events of one command atomically: either all of them are stored, or none. */
    void append(List<EventEnvelope> events);

    /** Every readable event, in order. */
    List<EventEnvelope> readAll();

    /** Every row, read or left out. */
    default Read replay() {
        List<Entry> entries = new ArrayList<>();
        long sequence = 0;
        for (EventEnvelope e : readAll()) entries.add(new Entry(++sequence, e));
        return new Read(entries, List.of());
    }

    /** The most recent entries, newest last. */
    List<Entry> latest(int limit);

    /**
     * The entries after this sequence, oldest first, at most {@code limit} of them: an indexed read from that point, not
     * the whole journal.
     */
    List<Entry> since(long sequence, int limit);
}
