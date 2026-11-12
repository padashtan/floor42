package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.workflow.Workflow;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The missions, as the director runs them: created, planned, approved, suspended, completed or cancelled, with their
 * permission table. Reached by {@link Company#missions()}; the rules live in the mission book.
 */
public final class MissionsFacade {

    private final MissionBook missionBook;
    private final Objections objections;

    MissionsFacade(MissionBook missionBook, Objections objections) {
        this.missionBook = missionBook;
        this.objections = objections;
    }

    /**
     * The director objects at acceptance (ADR-0060): 1 to 10 objections, each about a done work ticket (a directive
     * that reopens it, in its own worktree) or new work (a ticket of the director's, under the mission's table). One
     * command, one cause {@code objections-<n>} for the round. Refused unless the mission waits for the acceptance.
     */
    public List<EventEnvelope> object(CommandContext ctx, MissionId id, List<Objection> items) {
        return objections.object(ctx, id, items);
    }

    /** The rounds of objections the director made at acceptance, oldest first. */
    public List<ObjectionRound> objectionRounds(MissionId id) {
        return objections.rounds(id);
    }

    public Collection<Mission> all() {
        return missionBook.missions();
    }

    public Optional<Mission> find(MissionId id) {
        return missionBook.mission(id);
    }

    /** A simulated mission (the demo, ADR-0045): no repository, no pin, Floor42's own workflow. */
    public List<EventEnvelope> create(CommandContext ctx, String title, String objective) {
        return missionBook.create(ctx, title, objective, null, null);
    }

    /**
     * A real mission that pins its workflow in the same command, right after its creation and before any ticket
     * (ADR-0050): it follows that workflow for good.
     */
    public List<EventEnvelope> create(
            CommandContext ctx, String title, String objective, String workspace, Workflow workflow) {
        java.util.Objects.requireNonNull(workflow, "workflow");
        return missionBook.create(ctx, title, objective, workspace, workflow);
    }

    /** The guided first mission: simulated, on Floor42's sample repository (ADR-0045). */
    public List<EventEnvelope> createRehearsal(CommandContext ctx, String title, String objective, String sample) {
        return missionBook.createRehearsal(ctx, title, objective, sample);
    }

    public List<EventEnvelope> submitPlan(CommandContext ctx, MissionId id) {
        return missionBook.submitPlan(ctx, id);
    }

    public List<EventEnvelope> approvePlan(CommandContext ctx, MissionId id) {
        return missionBook.approvePlan(ctx, id);
    }

    public List<EventEnvelope> rejectPlan(CommandContext ctx, MissionId id, String reason) {
        return missionBook.rejectPlan(ctx, id, reason);
    }

    /**
     * The director suspends a mission. No route offers it today: in production a mission is suspended by the domain
     * itself, on an unauthorized execution ({@code ActionLog}), and resumed from the desk. Kept public on purpose: the
     * golden scenario journals it, and the dispatcher's tests set up a suspended mission with it (a forged unauthorized
     * execution would also raise an incident they do not test).
     */
    public List<EventEnvelope> suspend(CommandContext ctx, MissionId id, String reason) {
        return missionBook.suspend(ctx, id, reason);
    }

    public List<EventEnvelope> resume(CommandContext ctx, MissionId id) {
        return missionBook.resume(ctx, id);
    }

    /**
     * A mission number held by a journal row this version could not read (written by a newer version, or damaged): no
     * new mission will take it, so a rollback never gives two missions one id (ADR-0051). Called after the replay.
     */
    public void reserve(MissionId id) {
        missionBook.reserve(id);
    }

    /** A mission completes only when all its tickets are closed, no blocking question is open and no tool is running. */
    public List<EventEnvelope> complete(CommandContext ctx, MissionId id, String summary) {
        return missionBook.complete(ctx, id, summary);
    }

    /**
     * Test seam (package-private, the golden scenario): what {@link #complete} would refuse, thrown, without recording
     * anything. Callers outside the domain read {@link #refusalToComplete}, the same check as a value.
     */
    void checkComplete(CommandContext ctx, MissionId id) {
        missionBook.checkComplete(ctx, id);
    }

    /** What {@link #complete} would refuse now, as a value; empty when the mission can close. Records nothing. */
    public Optional<Refused> refusalToComplete(CommandContext ctx, MissionId id) {
        return Refused.by(() -> missionBook.checkComplete(ctx, id));
    }

    /** Cancelling closes everything that depends on the mission: open questions are withdrawn, pending actions denied. */
    public List<EventEnvelope> cancel(CommandContext ctx, MissionId id, String reason) {
        return missionBook.cancel(ctx, id, reason);
    }

    /**
     * The director puts a closed mission away (ADR-0054): completed or cancelled, no session of it running, its
     * retrospective over. It leaves the lists; its journal and worktrees stay. Archiving it again records nothing.
     */
    public List<EventEnvelope> archive(CommandContext ctx, MissionId id) {
        return missionBook.archive(ctx, id);
    }

    /** What {@link #archive} would refuse now, as a value; empty when the mission can be archived. Records nothing. */
    public Optional<Refused> refusalToArchive(CommandContext ctx, MissionId id) {
        return Refused.by(() -> missionBook.checkArchive(ctx, id));
    }

    /** Brings an archived mission back to the lists, unchanged otherwise; one not archived records nothing. */
    public List<EventEnvelope> unarchive(CommandContext ctx, MissionId id) {
        return missionBook.unarchive(ctx, id);
    }

    /** Agents can never change permissions, including their own: only the director can. */
    public List<EventEnvelope> addPermission(CommandContext ctx, MissionId id, PermissionRule rule) {
        return missionBook.addPermission(ctx, id, rule);
    }

    /**
     * The director's permission table: one mission-wide setting per tool. Replaces the previous setting of that tool
     * atomically; rules for a specific agent or command are left untouched and stay more specific.
     */
    public List<EventEnvelope> setToolPermission(
            CommandContext ctx, MissionId id, String tool, ActionCategory category, Decision decision) {
        return missionBook.setToolPermission(ctx, id, tool, category, decision);
    }

    public List<EventEnvelope> removePermission(CommandContext ctx, MissionId id, PermissionRule rule) {
        return missionBook.removePermission(ctx, id, rule);
    }

    /** From now on, tickets the director does not create wait for the director's approval (ADR-0017). */
    public List<EventEnvelope> enableTicketValidation(CommandContext ctx, MissionId id) {
        return missionBook.enableTicketValidation(ctx, id);
    }
}
