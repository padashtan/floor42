package dev.floor42.domain;

import java.util.ArrayList;
import java.util.List;

public final class Ticket {

    /**
     * A review. {@code items} is null for a review journaled before 1.13.0 (ADR-0053): its verdict, reservations
     * included, keeps the meaning it had.
     */
    public record Review(AgentId reviewer, ReviewVerdict verdict, String findings, ReviewItems items) {

        public Review(AgentId reviewer, ReviewVerdict verdict, String findings) {
            this(reviewer, verdict, findings, null);
        }

        /**
         * Whether this review counts as "validated with reservations" in the counters (ADR-0053): a validating verdict
         * with at least one kept reservation, or whose declared reservations could not be read; before 1.13.0, its
         * verdict as recorded.
         */
        public boolean reservationsStand() {
            if (items == null) return verdict == ReviewVerdict.VALIDATED_WITH_RESERVATIONS;
            boolean validating =
                    verdict == ReviewVerdict.VALIDATED || verdict == ReviewVerdict.VALIDATED_WITH_RESERVATIONS;
            boolean unread = items.notes().stream().anyMatch(n -> n.why() == ReviewNote.Why.UNPARSED);
            return validating && (!items.reservations().isEmpty() || unread);
        }

        /** Whether Floor42 sorted its items into reservations and notes (false before 1.13.0). */
        public boolean calibrated() {
            return items != null;
        }
    }

    /**
     * Help a reviewer asked for before concluding: a peer's advice or a web research. {@code round} is the number of
     * reviews the ticket had when it was asked; {@code answer} is null while pending, {@code answeredBy} null when Floor42
     * itself answered (the research was not allowed).
     */
    public record Consultation(
            int round,
            AgentId reviewer,
            ConsultationKind kind,
            String role,
            String question,
            String answer,
            AgentId answeredBy) {

        public boolean pending() {
            return answer == null;
        }
    }

    private final TicketId id;
    private final MissionId mission;
    String title;
    String description;
    private final Priority priority;
    private final List<TicketId> dependsOn;
    List<String> acceptanceCriteria;
    final List<Review> reviews = new ArrayList<>();
    final List<String> directives = new ArrayList<>();
    final List<Consultation> consultations = new ArrayList<>();
    TicketStatus status = TicketStatus.TODO;
    AgentId assignee;
    String deliverable;
    // When the journal recorded it: created, first started, and done or dropped (null again if reopened).
    java.time.Instant createdAt;
    java.time.Instant startedAt;
    java.time.Instant endedAt;
    /** Recorded at creation; for a ticket journaled before kinds existed, read once from its first title. */
    TicketKind kind = TicketKind.WORK;

    Ticket(
            TicketId id,
            MissionId mission,
            String title,
            String description,
            Priority priority,
            List<TicketId> dependsOn,
            List<String> acceptanceCriteria) {
        this.id = id;
        this.mission = mission;
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.dependsOn = List.copyOf(dependsOn);
        this.acceptanceCriteria = List.copyOf(acceptanceCriteria);
    }

    /** A retrospective's own ticket, in progress and chaired, before the retrospective itself is recorded. */
    static Ticket retrospective(TicketId id, MissionId mission, AgentId chair) {
        Ticket t = new Ticket(
                id,
                mission,
                "Retrospective",
                "What went well, what did not, and what to do better next time.",
                Priority.NORMAL,
                List.of(),
                List.of(
                        "Every participant speaks from the facts of the journal",
                        "The chair proposes lessons; only the director makes them binding"));
        t.status = TicketStatus.IN_PROGRESS;
        t.assignee = chair;
        return t;
    }

    /** Projection only: an open ticket reads cancelled once its mission is (older journals never wrote it). */
    void cancelledWithMission(java.time.Instant at) {
        if (status.isTerminal()) return;
        status = TicketStatus.CANCELLED;
        endedAt = at;
    }

    public TicketKind kind() {
        return kind;
    }

    public TicketId id() {
        return id;
    }

    public MissionId mission() {
        return mission;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public Priority priority() {
        return priority;
    }

    public List<TicketId> dependsOn() {
        return dependsOn;
    }

    public List<String> acceptanceCriteria() {
        return acceptanceCriteria;
    }

    public TicketStatus status() {
        return status;
    }

    /** Null until assigned. */
    public AgentId assignee() {
        return assignee;
    }

    /** What the assignee submitted for review; null before the first submission. */
    public String deliverable() {
        return deliverable;
    }

    /** The step its last submission says the environment kept it from doing; empty when it says none. */
    public java.util.Optional<EnvironmentBlocker> environmentBlocker() {
        return EnvironmentBlocker.in(deliverable);
    }

    /** When the ticket was created, as journaled. */
    public java.time.Instant createdAt() {
        return createdAt;
    }

    /** When work first started on it; null while it has not started. */
    public java.time.Instant startedAt() {
        return startedAt;
    }

    /** When it was done or dropped; null while it is open (a reopened ticket is open again). */
    public java.time.Instant endedAt() {
        return endedAt;
    }

    public List<Review> reviews() {
        return List.copyOf(reviews);
    }

    /** How many of its reviews rejected it. */
    public long rejections() {
        return reviews.stream()
                .filter(r -> r.verdict() == ReviewVerdict.REJECTED)
                .count();
    }

    /** What the director asked for on this ticket from the workshop, oldest first. */
    public List<String> directives() {
        return List.copyOf(directives);
    }

    /** A review's items go with the last review, by the same reviewer; anything else stops the replay (ADR-0053). */
    void attach(AgentId reviewer, ReviewItems items) {
        Review last = reviews.isEmpty() ? null : reviews.getLast();
        if (last == null || !last.reviewer().equals(reviewer) || last.calibrated()) {
            throw new IllegalStateException(id + " has no review by " + reviewer + " to attach items to");
        }
        reviews.set(reviews.size() - 1, new Review(last.reviewer(), last.verdict(), last.findings(), items));
    }

    /** Help reviewers asked for, oldest first. */
    public List<Consultation> consultations() {
        return List.copyOf(consultations);
    }

    /** The help asked for during the current review round, if any. */
    public java.util.Optional<Consultation> consultationThisRound() {
        return consultations.stream().filter(c -> c.round() == reviews.size()).findFirst();
    }
}
