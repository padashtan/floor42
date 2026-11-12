package dev.floor42.domain.event;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.ConsultationKind;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Priority;
import dev.floor42.domain.ReviewNote;
import dev.floor42.domain.ReviewReservation;
import dev.floor42.domain.ReviewVerdict;
import dev.floor42.domain.TicketId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A ticket's life, its review and the director's directives on it. */
public sealed interface TicketEvent extends DomainEvent {

    /**
     * @param kind what the ticket is for; absent (null) in a ticket journaled before 1.6.0, whose kind is then read from
     *     its title by the rule of that time (ADR-0009, amendment 2026-10-31)
     */
    record TicketCreated(
            TicketId ticket,
            MissionId mission,
            String title,
            String description,
            Priority priority,
            List<TicketId> dependsOn,
            List<String> acceptanceCriteria,
            dev.floor42.domain.TicketKind kind)
            implements TicketEvent {
        public TicketCreated {
            acceptanceCriteria = acceptanceCriteria == null
                    ? null
                    : Collections.unmodifiableList(new ArrayList<>(acceptanceCriteria));
            dependsOn = dependsOn == null ? null : Collections.unmodifiableList(new ArrayList<>(dependsOn));
        }
    }

    record TicketAssigned(TicketId ticket, AgentId agent) implements TicketEvent {}

    record TicketStarted(TicketId ticket) implements TicketEvent {}

    record TicketSubmittedForReview(TicketId ticket, String deliverable) implements TicketEvent {}

    record TicketReviewed(TicketId ticket, AgentId reviewer, ReviewVerdict verdict, String findings)
            implements TicketEvent {}

    /**
     * What Floor42 kept of the review just recorded (ADR-0053), in the same command right after its {@link
     * TicketReviewed}: reservations cite a criterion of the ticket and say what to change, everything else is a note.
     * A review journaled before 1.13.0 has none: its verdict keeps the meaning it had.
     *
     * @param declared the verdict the reviewer wrote; the {@link TicketReviewed} holds the one recorded
     */
    record ReviewItemsRecorded(
            TicketId ticket,
            AgentId reviewer,
            ReviewVerdict declared,
            List<ReviewReservation> reservations,
            List<ReviewNote> notes)
            implements TicketEvent {
        public ReviewItemsRecorded {
            notes = notes == null ? null : Collections.unmodifiableList(new ArrayList<>(notes));
            reservations = reservations == null ? null : Collections.unmodifiableList(new ArrayList<>(reservations));
        }
    }

    record TicketCompleted(TicketId ticket) implements TicketEvent {}

    record TicketReopened(TicketId ticket, String reason) implements TicketEvent {}

    /** A ticket in progress whose assignee has left goes back to the queue, unassigned; its changes stay on that branch. */
    record TicketReleased(TicketId ticket, AgentId agent) implements TicketEvent {}

    record TicketCancelled(TicketId ticket, String reason) implements TicketEvent {}

    record ConsultationRequested(TicketId ticket, AgentId reviewer, ConsultationKind kind, String role, String question)
            implements TicketEvent {}

    /** @param by null when Floor42 answered itself (the research was not allowed by the mission's table) */
    record ConsultationAnswered(TicketId ticket, AgentId by, String answer) implements TicketEvent {}

    record TicketApproved(TicketId ticket) implements TicketEvent {}

    record TicketEdited(TicketId ticket, String title, String description, List<String> acceptanceCriteria)
            implements TicketEvent {
        public TicketEdited {
            acceptanceCriteria = acceptanceCriteria == null
                    ? null
                    : Collections.unmodifiableList(new ArrayList<>(acceptanceCriteria));
        }
    }

    record DirectiveGiven(MissionId mission, TicketId ticket, String directive) implements TicketEvent {}
}
