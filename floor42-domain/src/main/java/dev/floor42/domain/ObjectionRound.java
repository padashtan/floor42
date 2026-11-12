package dev.floor42.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One round of objections (ADR-0060), read from the events one {@code objections-<n>} cause recorded.
 *
 * @param targeted the tickets reopened with a directive
 * @param created the tickets made of new work
 */
public record ObjectionRound(int number, List<TicketId> targeted, List<TicketId> created) {
    public ObjectionRound {
        created = created == null ? null : Collections.unmodifiableList(new ArrayList<>(created));
        targeted = targeted == null ? null : Collections.unmodifiableList(new ArrayList<>(targeted));
    }

    ObjectionRound withTargeted(TicketId t) {
        List<TicketId> l = new ArrayList<>(targeted);
        l.add(t);
        return new ObjectionRound(number, List.copyOf(l), created);
    }

    ObjectionRound withCreated(TicketId t) {
        List<TicketId> l = new ArrayList<>(created);
        l.add(t);
        return new ObjectionRound(number, targeted, List.copyOf(l));
    }
}
