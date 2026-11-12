package dev.floor42.server.api;

import dev.floor42.server.journal.Journal;

/** Read models of the journal. */
public final class JournalViews {

    private JournalViews() {}

    /** One journal row with its provenance; the payload stays server-side. */
    public record JournalEntryView(
            long sequence,
            String occurredAt,
            String origin,
            String actor,
            String mission,
            String type,
            String authorizationRef,
            String causationId) {
        static JournalEntryView of(Journal.Entry e) {
            var env = e.envelope();
            return new JournalEntryView(
                    e.sequence(),
                    env.occurredAt().toString(),
                    env.origin().name(),
                    env.actor().kind().name().toLowerCase() + ":" + env.actor().id(),
                    env.mission() == null ? null : env.mission().value(),
                    env.type(),
                    env.authorizationRef(),
                    env.causationId());
        }
    }
}
