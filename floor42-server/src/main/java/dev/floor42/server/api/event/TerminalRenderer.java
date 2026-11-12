package dev.floor42.server.api.event;

import static dev.floor42.server.api.event.EventLine.line;

import dev.floor42.domain.Company;
import dev.floor42.domain.event.TerminalEvent;
import dev.floor42.domain.event.TerminalEvent.ApiTokenIssued;
import dev.floor42.domain.event.TerminalEvent.ApiTokenRevoked;
import dev.floor42.domain.event.TerminalEvent.TerminalClosed;
import dev.floor42.domain.event.TerminalEvent.TerminalOpened;

/** The lines of the director's own terminals and script tokens. */
final class TerminalRenderer implements EventRenderer<TerminalEvent> {

    @Override
    public EventLine render(Company c, TerminalEvent event) {
        return switch (event) {
            case TerminalOpened e ->
                line().param("terminal", e.terminal())
                        .param("project", e.project())
                        .build();
            case TerminalClosed e ->
                line(e.reason() + (e.exitCode() == null ? "" : ".code"))
                        .param("terminal", e.terminal())
                        .param("project", e.project())
                        .param("reason", e.reason())
                        .param("exitCode", e.exitCode())
                        .build();
            case ApiTokenIssued e -> issued(e);
            case ApiTokenRevoked e ->
                line(e.reason())
                        .param("token", e.token())
                        .param("sha256", e.sha256())
                        .param("reason", e.reason())
                        .build();
        };
    }

    private static EventLine issued(ApiTokenIssued e) {
        return line().param("token", e.token())
                .param("sha256", e.sha256())
                .param("scope", e.scope())
                .param("expiresAt", e.expiresAt())
                .param("label", e.label())
                .build();
    }
}
