package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import java.util.List;
import java.util.Optional;

/**
 * The retrospectives of ended missions and the lessons they propose, which only the director makes binding. Reached
 * by {@link Company#retros()}; the rules live in the retrospectives area.
 */
public final class RetrosFacade {

    /** Most lessons one retrospective may propose: more is a list the director cannot weigh one by one. */
    public static final int MAX_LESSONS_PER_RETRO = 5;

    private final Retrospectives retrospectives;

    RetrosFacade(Retrospectives retrospectives) {
        this.retrospectives = retrospectives;
    }

    public Optional<Retro> find(MissionId mission) {
        return retrospectives.retro(mission);
    }

    /** Retrospectives still under way, oldest first. */
    public List<Retro> openRetros() {
        return retrospectives.openRetros();
    }

    /** Every lesson ever proposed, oldest first, whatever its status. */
    public List<Lesson> lessons() {
        return retrospectives.lessons();
    }

    public Optional<Lesson> lesson(String id) {
        return retrospectives.lesson(id);
    }

    /** The accepted lessons for an agent working in this role, oldest first. */
    public List<Lesson> lessonsFor(WorkRole role) {
        return retrospectives.lessonsFor(role);
    }

    /**
     * Floor42 convenes the agents who worked on an ended mission. Only agents with a session on the mission take part:
     * each speaks from what it did. The retrospective gets its own ticket; its sessions are recorded on it.
     */
    public List<EventEnvelope> open(
            CommandContext ctx, MissionId id, AgentId chair, List<AgentId> participants, String facts) {
        return retrospectives.openRetro(ctx, id, chair, participants, facts);
    }

    /** A participant speaks, once. */
    public List<EventEnvelope> contribute(
            CommandContext ctx, MissionId id, List<String> wentWell, List<String> wentBadly, String text) {
        return retrospectives.contributeToRetro(ctx, id, wentWell, wentBadly, text);
    }

    /** Floor42 records that a participant could not speak, and why. */
    public List<EventEnvelope> skip(CommandContext ctx, MissionId id, AgentId agent, String reason) {
        return retrospectives.skipInRetro(ctx, id, agent, reason);
    }

    /**
     * The director closes a retrospective without waiting for the turns still due (they may wait hours for the plan's
     * usage window): each one is recorded as not heard, and the retrospective ends with no conclusion and no lesson.
     */
    public List<EventEnvelope> closeNow(CommandContext ctx, MissionId id) {
        return retrospectives.closeRetroNow(ctx, id);
    }

    /**
     * The retrospective ends once everyone has spoken: by the chair, with its conclusions and the lessons it proposes,
     * or by Floor42 when the chair could not conclude ({@code text} null, {@code reason} says why, no lesson).
     */
    public List<EventEnvelope> conclude(
            CommandContext ctx,
            MissionId id,
            List<String> conclusions,
            String text,
            String reason,
            List<Lesson.Proposal> proposals) {
        return retrospectives.concludeRetro(ctx, id, conclusions, text, reason, proposals);
    }

    /** Only the director makes a lesson binding; {@code text} edits it, blank keeps the proposed wording. */
    public List<EventEnvelope> acceptLesson(CommandContext ctx, String id, String text) {
        return retrospectives.acceptLesson(ctx, id, text);
    }

    public List<EventEnvelope> rejectLesson(CommandContext ctx, String id, String reason) {
        return retrospectives.rejectLesson(ctx, id, reason);
    }

    public List<EventEnvelope> retireLesson(CommandContext ctx, String id, String reason) {
        return retrospectives.retireLesson(ctx, id, reason);
    }
}
