package dev.floor42.server.api.event;

import static dev.floor42.server.api.event.EventLine.line;

import dev.floor42.domain.Company;
import dev.floor42.domain.QuestionId;
import dev.floor42.domain.event.DeskEvent;
import dev.floor42.domain.event.DeskEvent.ApprovalForgotten;
import dev.floor42.domain.event.DeskEvent.QuestionAnswered;
import dev.floor42.domain.event.DeskEvent.QuestionAsked;
import dev.floor42.domain.event.DeskEvent.QuestionWithdrawn;

/** The director's desk's lines: questions asked, answered or withdrawn, and approvals no longer remembered. */
final class DeskRenderer implements EventRenderer<DeskEvent> {

    @Override
    public EventLine render(Company c, DeskEvent event) {
        return switch (event) {
            case QuestionAsked e -> asked(e);
            case QuestionAnswered e ->
                question(null, e.question()).param("answer", e.answer()).build();
            case QuestionWithdrawn e ->
                question(null, e.question()).param("reason", e.reason()).build();
            case ApprovalForgotten e ->
                question(forgottenVariant(e.reason()), e.question())
                        .param("reason", e.reason())
                        .build();
        };
    }

    /** Forgotten by the director, lifted when the director asked to be asked again, or asked for one call only. */
    private static String forgottenVariant(String reason) {
        if (reason.startsWith("forgotten")) return "forgotten";
        return reason.startsWith("asked again") ? "askedAgain" : "once";
    }

    /** The question as Floor42 wrote it, and its phrase's code and values ({@code phrase.<name>}) for the screen. */
    private static EventLine asked(QuestionAsked e) {
        EventLine.Builder line = question(e.blocking() ? "blocking" : "open", e.question())
                .agent(e.askedBy())
                .ticket(e.ticket())
                .action(e.action())
                .param("mission", e.mission())
                .param("kind", e.kind())
                .param("askedBy", e.askedBy())
                .param("subject", e.subject())
                .param("context", e.context())
                .param("options", e.options())
                .param("recommendation", e.recommendation())
                .param("criticality", e.criticality())
                .param("blocking", e.blocking())
                .param("ticket", e.ticket())
                .param("action", e.action())
                .param("optionCodes", e.optionCodes());
        if (e.phrase() != null) {
            line.param("phrase", e.phrase().code());
            e.phrase().params().forEach((k, v) -> line.param("phrase." + k, v));
        }
        return line.build();
    }

    private static EventLine.Builder question(String variant, QuestionId question) {
        return line(variant).question(question).param("question", question);
    }
}
