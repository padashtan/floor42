package dev.floor42.server.app.office.standup;

import dev.floor42.server.journal.Journal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Reads the journal and composes the standup (ADR-0078).
 * shortcut: the whole journal is read on each call, as the proof dossier does; use an indexed read from the previous
 * day when it gets slow.
 */
@ApplicationScoped
public class Standups {

    @Inject
    Journal journal;

    public Standup now(ZoneId zone) {
        return StandupComposer.compose(journal.readAll(), Instant.now(), zone);
    }
}
