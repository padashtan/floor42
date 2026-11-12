package dev.floor42.domain.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The committee seats the director defines. */
public sealed interface CommitteeSeatEvent extends DomainEvent {

    /** The director defines a committee seat of their own, or changes one (same key): a specialist who only reads. */
    record CommitteeSeatDefined(
            String key, String name, String title, String model, String effort, String lens, List<String> panels)
            implements CommitteeSeatEvent {
        public CommitteeSeatDefined {
            panels = panels == null ? null : Collections.unmodifiableList(new ArrayList<>(panels));
        }
    }
}
