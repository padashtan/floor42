package dev.floor42.server.api.event;

import static dev.floor42.server.api.event.EventLine.line;

import dev.floor42.domain.ActionId;
import dev.floor42.domain.Company;
import dev.floor42.domain.event.ActionEvent;
import dev.floor42.domain.event.ActionEvent.ActionAuthorized;
import dev.floor42.domain.event.ActionEvent.ActionConfirmed;
import dev.floor42.domain.event.ActionEvent.ActionDenied;
import dev.floor42.domain.event.ActionEvent.ActionExecuted;
import dev.floor42.domain.event.ActionEvent.ActionFailed;
import dev.floor42.domain.event.ActionEvent.ActionProgressed;
import dev.floor42.domain.event.ActionEvent.ActionRequested;
import dev.floor42.domain.event.ActionEvent.IncidentRaised;

/** Tool calls' lines, each naming the call, and the incidents of calls that ran unauthorized. Outputs stay out. */
final class ActionRenderer implements EventRenderer<ActionEvent> {

    @Override
    public EventLine render(Company c, ActionEvent event) {
        return switch (event) {
            case ActionRequested e -> requested(e);
            case ActionAuthorized e ->
                call(null, c, e.action()).param("basis", e.basis()).build();
            case ActionDenied e ->
                call(null, c, e.action())
                        .param("reason", e.reason())
                        .param("detail", e.detail())
                        .build();
            case ActionExecuted e ->
                call(e.authorized() ? "authorized" : "unauthorized", c, e.action())
                        .param("authorized", e.authorized())
                        .build();
            case ActionFailed e ->
                call(null, c, e.action()).param("error", e.error()).build();
            case ActionConfirmed e ->
                call(null, c, e.action()).param("evidence", e.evidence()).build();
            case ActionProgressed e ->
                call(null, c, e.action()).param("seconds", e.seconds()).build();
            case IncidentRaised e -> incident(e);
        };
    }

    private static EventLine requested(ActionRequested e) {
        return line().agent(e.agent())
                .action(e.action())
                .param("action", e.action())
                .param("mission", e.mission())
                .param("ticket", e.ticket())
                .param("agent", e.agent())
                .param("category", e.category())
                .param("tool", e.tool())
                .param("summary", e.summary())
                .build();
    }

    private static EventLine incident(IncidentRaised e) {
        return line().action(e.action())
                .param("incident", e.incident())
                .param("mission", e.mission())
                .param("criticality", e.criticality())
                .param("description", e.description())
                .param("action", e.action())
                .build();
    }

    /** A line about a call: the scene finds the agent, the ticket and the tool through the action. */
    private static EventLine.Builder call(String variant, Company c, ActionId action) {
        return line(variant).action(action).param("action", action).param("call", Lookups.call(c, action));
    }
}
