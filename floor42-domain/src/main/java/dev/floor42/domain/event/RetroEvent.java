package dev.floor42.domain.event;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.WorkRole;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Retrospectives and the lessons they propose. */
public sealed interface RetroEvent extends DomainEvent {

    /**
     * Floor42 convenes the agents who worked on an ended mission.
     *
     * @param participants agent ids, in the order they speak; the chair concludes once everyone has spoken
     * @param facts what Floor42 gave every participant, composed from the journal
     */
    record RetroOpened(MissionId mission, TicketId ticket, AgentId chair, List<String> participants, String facts)
            implements RetroEvent {
        public RetroOpened {
            participants = participants == null ? null : Collections.unmodifiableList(new ArrayList<>(participants));
        }
    }

    /** A participant's say, with the lines it marked as going well or badly. */
    record RetroContributed(
            MissionId mission, AgentId agent, List<String> wentWell, List<String> wentBadly, String text)
            implements RetroEvent {
        public RetroContributed {
            wentBadly = wentBadly == null ? null : Collections.unmodifiableList(new ArrayList<>(wentBadly));
            wentWell = wentWell == null ? null : Collections.unmodifiableList(new ArrayList<>(wentWell));
        }
    }

    /** A participant said nothing: why, in Floor42's words (its session failed, for instance). */
    record RetroSkipped(MissionId mission, AgentId agent, String reason) implements RetroEvent {}

    /**
     * The retrospective ends.
     *
     * @param text nullable: the chair's full conclusion, absent when the chair could not conclude
     * @param reason nullable: why Floor42 closed it without the chair's conclusion
     */
    record RetroConcluded(MissionId mission, List<String> conclusions, String text, String reason)
            implements RetroEvent {
        public RetroConcluded {
            conclusions = conclusions == null ? null : Collections.unmodifiableList(new ArrayList<>(conclusions));
        }
    }

    /**
     * @param audience nullable: every role
     * @param proposedBy nullable: Floor42
     */
    record LessonProposed(String lesson, MissionId mission, WorkRole audience, String text, AgentId proposedBy)
            implements RetroEvent {}

    /** @param text the lesson as the director accepted it, edited or not */
    record LessonAccepted(String lesson, String text) implements RetroEvent {}

    record LessonRejected(String lesson, String reason) implements RetroEvent {}

    /** An accepted lesson the director withdraws: later briefs no longer carry it. */
    record LessonRetired(String lesson, String reason) implements RetroEvent {}
}
