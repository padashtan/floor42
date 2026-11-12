package dev.floor42.server.api.event;

import static dev.floor42.server.api.event.EventLine.line;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.ConsultationKind;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.TicketEvent;
import dev.floor42.domain.event.TicketEvent.ConsultationAnswered;
import dev.floor42.domain.event.TicketEvent.ConsultationRequested;
import dev.floor42.domain.event.TicketEvent.DirectiveGiven;
import dev.floor42.domain.event.TicketEvent.ReviewItemsRecorded;
import dev.floor42.domain.event.TicketEvent.TicketApproved;
import dev.floor42.domain.event.TicketEvent.TicketAssigned;
import dev.floor42.domain.event.TicketEvent.TicketCancelled;
import dev.floor42.domain.event.TicketEvent.TicketCompleted;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.event.TicketEvent.TicketEdited;
import dev.floor42.domain.event.TicketEvent.TicketReleased;
import dev.floor42.domain.event.TicketEvent.TicketReopened;
import dev.floor42.domain.event.TicketEvent.TicketReviewed;
import dev.floor42.domain.event.TicketEvent.TicketStarted;
import dev.floor42.domain.event.TicketEvent.TicketSubmittedForReview;

/** A ticket's lines: its life, its review, its consultations and the director's directives. Deliverables stay out. */
final class TicketRenderer implements EventRenderer<TicketEvent> {

    @Override
    public EventLine render(Company c, TicketEvent event) {
        return render(c, event, Actor.director());
    }

    /** Floor42 tidies a node's description itself (the planner's notes leave it); only the director otherwise edits. */
    @Override
    public EventLine render(Company c, TicketEvent event, Actor actor) {
        return switch (event) {
            case TicketCreated e -> created(e);
            case TicketAssigned e -> by(e.ticket(), e.agent(), "agent").build();
            case TicketStarted e -> worked(c, e.ticket());
            case TicketSubmittedForReview e -> worked(c, e.ticket());
            case TicketReviewed e ->
                by(e.ticket(), e.reviewer(), "reviewer")
                        .param("verdict", e.verdict())
                        .build();
            case ReviewItemsRecorded e -> items(e);
            case TicketCompleted e -> ticket(null, e.ticket()).build();
            case TicketReopened e -> because(e.ticket(), e.reason());
            case TicketReleased e -> by(e.ticket(), e.agent(), "agent").build();
            case TicketCancelled e -> because(e.ticket(), e.reason());
            case ConsultationRequested e -> consulted(e);
            case ConsultationAnswered e -> answered(e);
            case TicketApproved e -> ticket(null, e.ticket()).build();
            case TicketEdited e -> edited(e, actor.kind() == Actor.Kind.SYSTEM);
            case DirectiveGiven e -> directive(e);
        };
    }

    private static EventLine created(TicketCreated e) {
        return ticket(null, e.ticket())
                .param("mission", e.mission())
                .param("title", e.title())
                .param("description", e.description())
                .param("priority", e.priority())
                .param("dependsOn", e.dependsOn())
                .param("acceptanceCriteria", e.acceptanceCriteria())
                .param("kind", e.kind())
                .build();
    }

    /** How many reservations Floor42 kept and how many notes (ADR-0053); the verdict is the declared one. */
    private static EventLine items(ReviewItemsRecorded e) {
        return ticket(null, e.ticket())
                .agent(e.reviewer())
                .param("reviewer", e.reviewer())
                .param("declared", e.declared())
                .param("reservations", e.reservations().size())
                .param("notes", e.notes().size())
                .build();
    }

    private static EventLine edited(TicketEdited e, boolean byFloor42) {
        return ticket(byFloor42 ? "floor42" : null, e.ticket())
                .param("title", e.title())
                .param("description", e.description())
                .param("acceptanceCriteria", e.acceptanceCriteria())
                .build();
    }

    private static EventLine consulted(ConsultationRequested e) {
        return ticket(e.kind() == ConsultationKind.CONSULT ? "consult" : "research", e.ticket())
                .agent(e.reviewer())
                .param("reviewer", e.reviewer())
                .param("kind", e.kind())
                .param("role", e.role())
                .param("question", e.question())
                .build();
    }

    /** Floor42 answered itself when the mission's table did not allow the research. */
    private static EventLine answered(ConsultationAnswered e) {
        return ticket(e.by() == null ? "floor42" : "agent", e.ticket())
                .agent(e.by())
                .param("by", e.by())
                .param("answer", e.answer())
                .build();
    }

    private static EventLine directive(DirectiveGiven e) {
        return ticket(null, e.ticket())
                .param("mission", e.mission())
                .param("directive", e.directive())
                .build();
    }

    /** A line about an agent on the ticket, its field named as the event names it: the scene points at both. */
    private static EventLine.Builder by(TicketId ticket, AgentId agent, String field) {
        return ticket(null, ticket).agent(agent).param(field, agent);
    }

    /** A line about the ticket's work: the scene points at whoever works it now. */
    private static EventLine worked(Company c, TicketId ticket) {
        String assignee = Lookups.assignee(c, ticket);
        return ticket(null, ticket).agent(assignee).param("assignee", assignee).build();
    }

    private static EventLine because(TicketId ticket, String reason) {
        return ticket(null, ticket).param("reason", reason).build();
    }

    private static EventLine.Builder ticket(String variant, TicketId ticket) {
        return line(variant).ticket(ticket).param("ticket", ticket);
    }
}
