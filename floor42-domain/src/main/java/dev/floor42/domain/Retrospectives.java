package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireDirector;
import static dev.floor42.domain.Rules.requireSystem;
import static dev.floor42.domain.Rules.requireText;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.RetroEvent;
import dev.floor42.domain.event.RetroEvent.LessonAccepted;
import dev.floor42.domain.event.RetroEvent.LessonProposed;
import dev.floor42.domain.event.RetroEvent.LessonRejected;
import dev.floor42.domain.event.RetroEvent.LessonRetired;
import dev.floor42.domain.event.RetroEvent.RetroConcluded;
import dev.floor42.domain.event.RetroEvent.RetroContributed;
import dev.floor42.domain.event.RetroEvent.RetroOpened;
import dev.floor42.domain.event.RetroEvent.RetroSkipped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Retrospectives of ended missions (ADR-0020) and the lessons they propose, binding once the director accepts them. */
final class Retrospectives {

    private final CompanyView view;
    private final Map<MissionId, Retro> retros = new LinkedHashMap<>();
    private final Map<String, Lesson> lessons = new LinkedHashMap<>();

    Retrospectives(CompanyView view) {
        this.view = view;
    }

    Optional<Retro> retro(MissionId mission) {
        return Optional.ofNullable(retros.get(mission));
    }

    List<Retro> openRetros() {
        return retros.values().stream().filter(r -> !r.concluded).toList();
    }

    List<Lesson> lessons() {
        return List.copyOf(lessons.values());
    }

    Optional<Lesson> lesson(String id) {
        return Optional.ofNullable(lessons.get(id));
    }

    List<Lesson> lessonsFor(WorkRole role) {
        return lessons.values().stream().filter(l -> l.reaches(role)).toList();
    }

    /** Only agents with a session on the mission take part: each speaks from what it did. */
    List<EventEnvelope> openRetro(
            CommandContext ctx, MissionId id, AgentId chair, List<AgentId> participants, String facts) {
        requireSystem(ctx, "Floor42 convenes the retrospective");
        Mission m = view.missionFor(ctx, id);
        require(m.status.isTerminal(), Refusal.MISSION_NOT_FINISHED, id + " has not ended");
        // Archived before Floor42 convened it (ADR-0054): the director put the mission away, no retrospective follows.
        require(!m.archived, Refusal.MISSION_ARCHIVED, id + " is archived");
        require(
                m.status != MissionStatus.CANCELLED,
                Refusal.MISSION_NOT_ACTIVE,
                id + " was cancelled: no retrospective");
        require(!retros.containsKey(id), Refusal.DUPLICATE_RETRO, id + " already had its retrospective");
        requireParticipants(id, chair, participants);
        requireText(facts, "facts");
        TicketId ticket = view.nextTicketId();
        List<String> who = participants.stream().map(AgentId::value).toList();
        return view.record(ctx, id)
                .emit(new RetroOpened(id, ticket, chair, who, facts))
                .events();
    }

    private void requireParticipants(MissionId id, AgentId chair, List<AgentId> participants) {
        require(participants != null && !participants.isEmpty(), Refusal.INVALID_ARGUMENT, "Nobody to take part");
        require(
                participants.stream().distinct().count() == participants.size(),
                Refusal.INVALID_ARGUMENT,
                "Participants are distinct");
        for (AgentId a : participants) {
            require(!view.existingAgent(a).dismissed, Refusal.AGENT_DISMISSED, a + " has left the company");
            require(
                    view.sessions(id).stream().anyMatch(s -> s.agent().equals(a)),
                    Refusal.NOT_A_PARTICIPANT,
                    a + " did not work on " + id);
        }
        require(participants.contains(chair), Refusal.INVALID_ARGUMENT, "The chair takes part");
    }

    List<EventEnvelope> contributeToRetro(
            CommandContext ctx, MissionId id, List<String> wentWell, List<String> wentBadly, String text) {
        require(ctx.actor().kind() == Actor.Kind.AGENT, Refusal.FORBIDDEN, "Participants speak for themselves");
        AgentId agent = new AgentId(ctx.actor().id());
        expected(running(ctx, id), agent);
        requireText(text, "text");
        return view.record(ctx, id)
                .emit(new RetroContributed(
                        id,
                        agent,
                        List.copyOf(wentWell == null ? List.of() : wentWell),
                        List.copyOf(wentBadly == null ? List.of() : wentBadly),
                        text))
                .events();
    }

    List<EventEnvelope> skipInRetro(CommandContext ctx, MissionId id, AgentId agent, String reason) {
        requireSystem(ctx, "Only Floor42 records a silence");
        expected(running(ctx, id), agent);
        requireText(reason, "reason");
        return view.record(ctx, id).emit(new RetroSkipped(id, agent, reason)).events();
    }

    private static void expected(Retro r, AgentId agent) {
        require(
                r.pending().contains(agent),
                Refusal.NOT_EXPECTED,
                agent + " is not expected to speak in the retrospective");
    }

    /** Each turn still due is recorded as not heard; the retrospective ends with no conclusion and no lesson. */
    List<EventEnvelope> closeRetroNow(CommandContext ctx, MissionId id) {
        requireDirector(ctx);
        Retro r = running(ctx, id);
        String why = "the director closed the retrospective before this turn";
        Recorder rec = view.record(ctx, id);
        r.pending().forEach(a -> rec.emit(new RetroSkipped(id, a, why)));
        return rec.emit(new RetroConcluded(id, List.of(), null, RecordedReason.retroClosedEarly()))
                .events();
    }

    /** By the chair, with its conclusions and lessons; or by Floor42 when the chair could not ({@code reason}). */
    List<EventEnvelope> concludeRetro(
            CommandContext ctx,
            MissionId id,
            List<String> conclusions,
            String text,
            String reason,
            List<Lesson.Proposal> proposals) {
        Retro r = running(ctx, id);
        require(r.pending().isEmpty(), Refusal.RETRO_NOT_READY, "Some participants of " + id + " have not spoken yet");
        List<Lesson.Proposal> proposed = proposals == null ? List.of() : List.copyOf(proposals);
        AgentId by = concluder(ctx, r, text, reason, proposed);
        require(
                proposed.size() <= RetrosFacade.MAX_LESSONS_PER_RETRO,
                Refusal.INVALID_ARGUMENT,
                "At most " + RetrosFacade.MAX_LESSONS_PER_RETRO + " lessons");
        proposed.forEach(l -> requireText(l.text(), "lesson"));
        Recorder rec = view.record(ctx, id)
                .emit(new RetroConcluded(id, List.copyOf(conclusions == null ? List.of() : conclusions), text, reason));
        int next = lessons.size() + 1;
        for (Lesson.Proposal l : proposed) {
            rec.emit(
                    new LessonProposed("L-" + next++, id, l.audience(), l.text().strip(), by));
        }
        return rec.events();
    }

    /** The chair, who writes the conclusion; or null for Floor42, which says why and draws no lesson itself. */
    private static AgentId concluder(
            CommandContext ctx, Retro r, String text, String reason, List<Lesson.Proposal> proposed) {
        if (ctx.actor().kind() == Actor.Kind.AGENT) {
            AgentId by = new AgentId(ctx.actor().id());
            require(by.equals(r.chair()), Refusal.FORBIDDEN, "The chair concludes the retrospective");
            requireText(text, "text");
            return by;
        }
        require(ctx.actor().kind() == Actor.Kind.SYSTEM, Refusal.FORBIDDEN, "The chair or Floor42 concludes");
        requireText(reason, "reason");
        require(proposed.isEmpty(), Refusal.INVALID_ARGUMENT, "Floor42 draws no lesson itself");
        return null;
    }

    List<EventEnvelope> acceptLesson(CommandContext ctx, String id, String text) {
        requireDirector(ctx);
        Lesson l = existingLesson(id, Lesson.Status.PROPOSED);
        String wording = text == null || text.isBlank() ? l.text() : text.strip();
        require(wording.length() <= 500, Refusal.INVALID_ARGUMENT, "A lesson is at most 500 characters");
        return view.record(ctx, l.mission())
                .emit(new LessonAccepted(id, wording))
                .events();
    }

    List<EventEnvelope> rejectLesson(CommandContext ctx, String id, String reason) {
        requireDirector(ctx);
        Lesson l = existingLesson(id, Lesson.Status.PROPOSED);
        return view.record(ctx, l.mission())
                .emit(new LessonRejected(id, reason == null || reason.isBlank() ? "rejected by the director" : reason))
                .events();
    }

    List<EventEnvelope> retireLesson(CommandContext ctx, String id, String reason) {
        requireDirector(ctx);
        Lesson l = existingLesson(id, Lesson.Status.ACCEPTED);
        return view.record(ctx, l.mission())
                .emit(new LessonRetired(id, reason == null || reason.isBlank() ? "retired by the director" : reason))
                .events();
    }

    private Retro running(CommandContext ctx, MissionId id) {
        view.missionFor(ctx, id);
        Retro r = retros.get(id);
        require(r != null, Refusal.NO_RETRO, id + " has no retrospective");
        require(!r.concluded, Refusal.RETRO_CONCLUDED, "The retrospective of " + id + " has ended");
        return r;
    }

    private Lesson existingLesson(String id, Lesson.Status expected) {
        Lesson l = lessons.get(id);
        require(l != null, Refusal.UNKNOWN_LESSON, "No lesson " + id);
        require(l.status == expected, Refusal.INVALID_TRANSITION, id + " is " + l.status);
        return l;
    }

    /** The only way this area's state changes. */
    void apply(RetroEvent event, EventEnvelope envelope) {
        Instant at = envelope.occurredAt();
        switch (event) {
            case RetroOpened e -> on(e);
            case RetroContributed e -> on(e, at);
            case RetroSkipped e -> on(e, at);
            case RetroConcluded e -> on(e);
            case LessonProposed e -> on(e, at);
            case LessonAccepted e -> on(e);
            case LessonRejected e -> on(e);
            case LessonRetired e -> on(e);
        }
    }

    /** The retrospective's part; its ticket, created just before, is the ticket board's. */
    void on(RetroOpened e) {
        retros.put(
                e.mission(),
                new Retro(
                        e.mission(),
                        e.ticket(),
                        e.chair(),
                        e.participants().stream().map(AgentId::new).toList(),
                        e.facts()));
    }

    void on(RetroContributed e, Instant at) {
        retros.get(e.mission())
                .contributions
                .put(e.agent(), new Retro.Contribution(e.agent(), e.wentWell(), e.wentBadly(), e.text(), null, at));
    }

    void on(RetroSkipped e, Instant at) {
        retros.get(e.mission())
                .contributions
                .put(e.agent(), new Retro.Contribution(e.agent(), List.of(), List.of(), null, e.reason(), at));
    }

    /** The retrospective that ended; its ticket is done, which is the ticket board's part. */
    Retro on(RetroConcluded e) {
        Retro r = retros.get(e.mission());
        r.concluded = true;
        r.conclusions = new ArrayList<>(e.conclusions());
        r.text = e.text();
        r.reason = e.reason();
        return r;
    }

    void on(LessonProposed e, Instant at) {
        lessons.put(e.lesson(), new Lesson(e.lesson(), e.mission(), e.audience(), e.text(), e.proposedBy(), at));
    }

    void on(LessonAccepted e) {
        Lesson l = lessons.get(e.lesson());
        l.status = Lesson.Status.ACCEPTED;
        l.text = e.text();
    }

    void on(LessonRejected e) {
        Lesson l = lessons.get(e.lesson());
        l.status = Lesson.Status.REJECTED;
        l.reason = e.reason();
    }

    void on(LessonRetired e) {
        Lesson l = lessons.get(e.lesson());
        l.status = Lesson.Status.RETIRED;
        l.reason = e.reason();
    }
}
