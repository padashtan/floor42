package dev.floor42.server.api.event;

import static dev.floor42.server.api.event.EventLine.line;

import dev.floor42.domain.Company;
import dev.floor42.domain.event.RetroEvent;
import dev.floor42.domain.event.RetroEvent.LessonAccepted;
import dev.floor42.domain.event.RetroEvent.LessonProposed;
import dev.floor42.domain.event.RetroEvent.LessonRejected;
import dev.floor42.domain.event.RetroEvent.LessonRetired;
import dev.floor42.domain.event.RetroEvent.RetroConcluded;
import dev.floor42.domain.event.RetroEvent.RetroContributed;
import dev.floor42.domain.event.RetroEvent.RetroOpened;
import dev.floor42.domain.event.RetroEvent.RetroSkipped;

/** Retrospectives' lines and the lessons they propose. The facts Floor42 gave the table stay out. */
final class RetroRenderer implements EventRenderer<RetroEvent> {

    @Override
    public EventLine render(Company c, RetroEvent event) {
        return switch (event) {
            case RetroOpened e ->
                line().ticket(e.ticket())
                        .param("mission", e.mission())
                        .param("ticket", e.ticket())
                        .param("chair", e.chair())
                        .param("participants", e.participants())
                        .build();
            case RetroContributed e -> contributed(e);
            case RetroSkipped e ->
                line().agent(e.agent())
                        .param("mission", e.mission())
                        .param("agent", e.agent())
                        .param("reason", e.reason())
                        .build();
            case RetroConcluded e -> concluded(e);
            case LessonProposed e -> proposed(e);
            case LessonAccepted e ->
                line().param("lesson", e.lesson()).param("text", e.text()).build();
            case LessonRejected e ->
                line().param("lesson", e.lesson()).param("reason", e.reason()).build();
            case LessonRetired e ->
                line().param("lesson", e.lesson()).param("reason", e.reason()).build();
        };
    }

    /** A participant's say, with how many lines went well and badly. */
    private static EventLine contributed(RetroContributed e) {
        return line().agent(e.agent())
                .param("mission", e.mission())
                .param("agent", e.agent())
                .param("wentWell", e.wentWell())
                .param("wentBadly", e.wentBadly())
                .param("well", e.wentWell().size())
                .param("badly", e.wentBadly().size())
                .param("text", e.text())
                .build();
    }

    /** Closed without the chair's conclusion, concluded, or concluded with a first conclusion to show. */
    private static EventLine concluded(RetroConcluded e) {
        String variant = e.text() == null ? "none" : e.conclusions().isEmpty() ? "plain" : "first";
        return line(variant)
                .param("mission", e.mission())
                .param("conclusions", e.conclusions())
                .param("first", e.conclusions().isEmpty() ? "" : e.conclusions().getFirst())
                .param("text", e.text())
                .param("reason", e.reason())
                .build();
    }

    private static EventLine proposed(LessonProposed e) {
        return line(e.audience() == null ? "all" : "audience")
                .agent(e.proposedBy())
                .param("lesson", e.lesson())
                .param("mission", e.mission())
                .param("audience", e.audience())
                .param("text", e.text())
                .param("proposedBy", e.proposedBy())
                .build();
    }
}
