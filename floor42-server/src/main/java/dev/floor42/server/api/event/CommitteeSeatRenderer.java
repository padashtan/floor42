package dev.floor42.server.api.event;

import static dev.floor42.server.api.event.EventLine.line;

import dev.floor42.domain.Company;
import dev.floor42.domain.event.CommitteeSeatEvent;
import dev.floor42.domain.event.CommitteeSeatEvent.CommitteeSeatDefined;

/** The lines of the committee seats the director defines. */
final class CommitteeSeatRenderer implements EventRenderer<CommitteeSeatEvent> {

    @Override
    public EventLine render(Company c, CommitteeSeatEvent event) {
        return switch (event) {
            case CommitteeSeatDefined e ->
                line().param("key", e.key())
                        .param("name", e.name())
                        .param("title", e.title())
                        .param("model", e.model())
                        .param("effort", e.effort())
                        .param("lens", e.lens())
                        .param("panels", e.panels())
                        .build();
        };
    }
}
