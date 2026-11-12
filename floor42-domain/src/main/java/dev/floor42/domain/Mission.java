package dev.floor42.domain;

import java.time.Instant;
import java.util.List;

public final class Mission {

    private final MissionId id;
    private final String title;
    private final String objective;
    private final Origin origin;
    private final Instant createdAt;
    private final String workspace;
    private final PermissionPolicy policy = new PermissionPolicy();
    MissionStatus status = MissionStatus.PLANNING;
    String outcome;
    /** When the mission was completed or cancelled; set from the closing event's time. */
    transient Instant closedAt;
    /** When the mission was last suspended (the envelope's instant); null if never. */
    transient Instant suspendedAt;

    String checkCommand;
    boolean checksAutomatic;
    Double budgetUsd;
    /** True once the director set a budget or chose none, by a recorded decision. */
    boolean capDecided;
    /** Tickets the director did not create start as proposals (ADR-0017). */
    boolean ticketValidation;
    /** Put away by the director once closed (ADR-0054): out of the lists, kept in the journal, restorable. */
    boolean archived;
    /**
     * True once a plan was submitted. Before it, the mission is being created by Floor42: its permission lines are
     * Floor42's, whatever actor journals written before 1.14.0 recorded (they named the director).
     */
    boolean planSubmitted;

    Mission(MissionId id, String title, String objective, Origin origin, Instant createdAt, String workspace) {
        this.workspace = workspace;
        this.id = id;
        this.title = title;
        this.objective = objective;
        this.origin = origin;
        this.createdAt = createdAt;
    }

    /**
     * Whether the director already owns this line: recorded by the director once the mission had a plan. Before the
     * first plan every line reads as Floor42's, and a click of the director then changes nothing more.
     */
    boolean directorsOwn(PermissionRule rule) {
        return !planSubmitted || policy.author(rule).map(Actor::isDirector).orElse(false);
    }

    /**
     * The lines a mission-wide setting of {@code tool} replaces: its mission-wide line, and when the director sets it,
     * the tickets' lines of that tool too (they would outrank it unseen).
     */
    List<PermissionRule> toolLines(String tool, boolean director) {
        return policy.rules().stream()
                .filter(r -> (r.coversWholeTool() || (director && r.coversToolOnTicket()))
                        && r.tool().equals(tool))
                .toList();
    }

    public MissionId id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String objective() {
        return objective;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** Simulated and real missions never mix: every event of a mission carries the mission's origin. */
    public Origin origin() {
        return origin;
    }

    /** The repository a real mission works on; null for simulated missions. */
    public String workspace() {
        return workspace;
    }

    public MissionStatus status() {
        return status;
    }

    public PermissionPolicy policy() {
        return policy;
    }

    /** The command Floor42 runs to check an agent's work (build, tests); null when the director set none. */
    public String checkCommand() {
        return checkCommand;
    }

    /** Whether the checks run after every delivery, before the independent review. */
    public boolean checksAutomatic() {
        return checksAutomatic;
    }

    /** The mission's budget in USD, compared with the runtime's cost estimates; null for no limit. */
    public Double budgetUsd() {
        return budgetUsd;
    }

    /**
     * Whether the cap was decided: a budget set, or "no cap" chosen explicitly (both journaled as {@code BudgetSet}).
     * A real mission's plan is not approved before.
     */
    public boolean capDecided() {
        return capDecided;
    }

    /** When the latest {@code MissionSuspended} was recorded; null if never suspended. */
    public Instant suspendedAt() {
        return suspendedAt;
    }

    /** When the mission was completed or cancelled; null while it runs. */
    public Instant closedAt() {
        return closedAt;
    }

    /** Completion summary or cancellation reason; null while the mission runs. */
    public String outcome() {
        return outcome;
    }

    /** True when tickets not created by the director wait for the director's approval. */
    public boolean ticketValidation() {
        return ticketValidation;
    }

    /** True while the director keeps the closed mission in the archives (ADR-0054): hidden from the lists. */
    public boolean archived() {
        return archived;
    }
}
