package dev.floor42.server.api;

import dev.floor42.domain.Action;
import dev.floor42.domain.ActionId;
import dev.floor42.domain.Company;
import dev.floor42.domain.event.ActionEvent.ActionAuthorized;
import dev.floor42.domain.event.ActionEvent.ActionConfirmed;
import dev.floor42.domain.event.ActionEvent.ActionDenied;
import dev.floor42.domain.event.ActionEvent.ActionExecuted;
import dev.floor42.domain.event.ActionEvent.ActionFailed;
import dev.floor42.domain.event.ActionEvent.ActionProgressed;
import dev.floor42.domain.event.ActionEvent.ActionRequested;
import dev.floor42.domain.event.DeskEvent.QuestionAsked;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.server.api.event.EventLine;
import dev.floor42.server.api.event.EventRenderers;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One event as the UI receives it on the live stream: provenance, the line's code and values for the event log (the
 * screen writes it in the director's language, ADR-0021), and the ids the scene needs to react (which agent, which
 * action, which tool). Machines are animated only from {@code ran == true}.
 *
 * @param code the line's message code: the event type, plus a variant where the sentence changes with the event
 * @param params the values the line is written with
 */
public record EventView(
        String eventId,
        String occurredAt,
        String origin,
        String actor,
        String mission,
        String type,
        String code,
        Map<String, String> params,
        String agent,
        String ticket,
        String question,
        String action,
        String tool,
        String category,
        String summary,
        String stage,
        boolean ran) {
    public EventView {
        params = params == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    static EventView of(Company c, EventEnvelope e) {
        EventLine line = EventRenderers.render(c, e.event(), e.actor());
        Action a = line.action() == null
                ? null
                : c.actions().find(new ActionId(line.action())).orElse(null);
        return new EventView(
                e.eventId(),
                e.occurredAt().toString(),
                e.origin().name(),
                e.actor().kind().name().toLowerCase(Locale.ROOT) + ":"
                        + e.actor().id(),
                e.mission() == null ? null : e.mission().value(),
                e.type(),
                EventRenderers.code(e.event(), line),
                line.params(),
                line.agent() != null
                        ? line.agent()
                        : (a == null ? null : a.agent().value()),
                line.ticket() != null
                        ? line.ticket()
                        : (a == null || a.ticket() == null ? null : a.ticket().value()),
                line.question(),
                line.action(),
                a == null ? null : a.tool(),
                a == null ? null : a.category().name(),
                a == null ? null : a.summary(),
                a == null ? null : stageAfter(e),
                isExecution(e));
    }

    /** True only for the event that records a tool really running: what may light a machine. */
    private static boolean isExecution(EventEnvelope e) {
        return e.event() instanceof ActionExecuted || e.event() instanceof ActionFailed;
    }

    /**
     * The stage this very event put the action in, never the live entity's (it may already be further along): an
     * approval question is asked while its action waits (REQUESTED), progress is reported while it runs (AUTHORIZED),
     * and any other event moves no action (null).
     */
    private static String stageAfter(EventEnvelope e) {
        return switch (e.event()) {
            case ActionRequested ignored -> "REQUESTED";
            case ActionAuthorized ignored -> "AUTHORIZED";
            case ActionDenied ignored -> "DENIED";
            case ActionExecuted ignored -> "EXECUTED";
            case ActionFailed ignored -> "FAILED";
            case ActionConfirmed ignored -> "CONFIRMED";
            case ActionProgressed ignored -> "AUTHORIZED";
            case QuestionAsked ignored -> "REQUESTED";
            default -> null;
        };
    }
}
