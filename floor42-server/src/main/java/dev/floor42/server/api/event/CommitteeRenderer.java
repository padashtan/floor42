package dev.floor42.server.api.event;

import static dev.floor42.server.api.event.EventLine.line;

import dev.floor42.domain.Company;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.event.CommitteeEvent;
import dev.floor42.domain.event.CommitteeEvent.CommitteeAdvanced;
import dev.floor42.domain.event.CommitteeEvent.CommitteeArbitrated;
import dev.floor42.domain.event.CommitteeEvent.CommitteeClarificationOpened;
import dev.floor42.domain.event.CommitteeEvent.CommitteeClarificationWritten;
import dev.floor42.domain.event.CommitteeEvent.CommitteeClarified;
import dev.floor42.domain.event.CommitteeEvent.CommitteeContributed;
import dev.floor42.domain.event.CommitteeEvent.CommitteeConvened;
import dev.floor42.domain.event.CommitteeEvent.CommitteeSentBack;
import java.util.Locale;

/** A committee's lines: who sits, who said what, how the deliberation moves on, and the director's ruling. */
final class CommitteeRenderer implements EventRenderer<CommitteeEvent> {

    @Override
    public EventLine render(Company c, CommitteeEvent event) {
        return switch (event) {
            case CommitteeConvened e -> convened(e);
            case CommitteeContributed e -> contributed(e);
            case CommitteeClarificationOpened e ->
                committee(null, e.mission())
                        .agent(e.analyst())
                        .param("analyst", e.analyst())
                        .param("round", e.round())
                        .build();
            case CommitteeClarificationWritten e -> written(e);
            case CommitteeClarified e ->
                committee(e.answers().isEmpty() ? "none" : "answers", e.mission())
                        .param("answers", e.answers())
                        .build();
            case CommitteeAdvanced e ->
                committee(e.stage().name().toLowerCase(Locale.ROOT), e.mission())
                        .param("stage", e.stage())
                        .param("round", e.round())
                        .build();
            case CommitteeSentBack e ->
                committee(null, e.mission()).param("constraint", e.constraint()).build();
            case CommitteeArbitrated e -> arbitrated(e);
        };
    }

    private static EventLine convened(CommitteeConvened e) {
        return committee(null, e.mission())
                .ticket(e.ticket())
                .param("ticket", e.ticket())
                .param("topic", e.topic())
                .param("members", e.members())
                .param("roles", e.roles())
                .param("panel", e.panel())
                .param("moderated", e.moderated())
                .param("maxDebateRounds", e.maxDebateRounds())
                .param("directorSeat", e.directorSeat())
                .build();
    }

    /** A member's say; the director's own, in the seat of their double, is a sentence of its own. */
    private static EventLine contributed(CommitteeContributed e) {
        String variant =
                e.kind().name().toLowerCase(Locale.ROOT) + (Boolean.TRUE.equals(e.byDirector()) ? ".director" : "");
        return committee(variant, e.mission())
                .agent(e.member())
                .param("member", e.member())
                .param("round", e.round())
                .param("kind", e.kind())
                .param("text", e.text())
                .param("proposal", e.proposal())
                .param("position", e.position())
                .param("criticalObjection", e.criticalObjection())
                .param("byDirector", e.byDirector())
                .param("questions", e.questions())
                .build();
    }

    /** The business analyst's note, and how many questions it asks the director. */
    private static EventLine written(CommitteeClarificationWritten e) {
        return committee(null, e.mission())
                .agent(e.analyst())
                .param("analyst", e.analyst())
                .param("text", e.text())
                .param("questions", e.questions())
                .build();
    }

    private static EventLine arbitrated(CommitteeArbitrated e) {
        return committee(e.adopted() ? "adopted" : "rejected", e.mission())
                .param("adopted", e.adopted())
                .param("implement", e.implement())
                .param("overCriticalObjection", e.overCriticalObjection())
                .build();
    }

    private static EventLine.Builder committee(String variant, MissionId mission) {
        return line(variant).param("mission", mission);
    }
}
