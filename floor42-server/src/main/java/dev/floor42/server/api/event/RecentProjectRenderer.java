package dev.floor42.server.api.event;

import static dev.floor42.server.api.event.EventLine.line;

import dev.floor42.domain.Company;
import dev.floor42.domain.event.RecentProjectEvent;
import dev.floor42.domain.event.RecentProjectEvent.ProjectForgotten;
import dev.floor42.domain.event.RecentProjectEvent.ProjectPinned;

/** The lines of the director's marks on the recent projects list. */
final class RecentProjectRenderer implements EventRenderer<RecentProjectEvent> {

    @Override
    public EventLine render(Company c, RecentProjectEvent event) {
        return switch (event) {
            case ProjectPinned e ->
                line(e.pinned() ? "pinned" : "unpinned")
                        .param("path", e.path())
                        .param("pinned", e.pinned())
                        .build();
            case ProjectForgotten e -> line().param("path", e.path()).build();
        };
    }
}
