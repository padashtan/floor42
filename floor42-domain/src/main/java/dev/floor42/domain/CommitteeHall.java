package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.blankToNull;
import static dev.floor42.domain.Rules.requireDirector;
import static dev.floor42.domain.Rules.requireText;

import dev.floor42.domain.event.CommitteeEvent;
import dev.floor42.domain.event.CommitteeEvent.CommitteeAdvanced;
import dev.floor42.domain.event.CommitteeEvent.CommitteeArbitrated;
import dev.floor42.domain.event.CommitteeEvent.CommitteeClarificationOpened;
import dev.floor42.domain.event.CommitteeEvent.CommitteeClarificationWritten;
import dev.floor42.domain.event.CommitteeEvent.CommitteeClarified;
import dev.floor42.domain.event.CommitteeEvent.CommitteeContributed;
import dev.floor42.domain.event.CommitteeEvent.CommitteeConvened;
import dev.floor42.domain.event.CommitteeEvent.CommitteeSentBack;
import dev.floor42.domain.event.DeskEvent.QuestionAsked;
import dev.floor42.domain.event.EventEnvelope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Committees (spec section 10): members analyse, debate, the chair writes the record, a business analyst takes their
 * questions to the director, and the director rules. A mission holds its committees in the order they were convened,
 * numbered from 1 (ADR-0046).
 */
final class CommitteeHall {

    private final CompanyView view;
    private final Arbitration arbitration;
    private final CommitteeConvening convening;
    private final Map<MissionId, List<Committee>> committees = new LinkedHashMap<>();

    CommitteeHall(CompanyView view) {
        this.view = view;
        this.arbitration = new Arbitration(view);
        this.convening = new CommitteeConvening(view);
    }

    Optional<Committee> committee(CommitteeRef ref) {
        List<Committee> held = committees.getOrDefault(ref.mission(), List.of());
        return ref.number() <= held.size() ? Optional.of(held.get(ref.number() - 1)) : Optional.empty();
    }

    /** The mission's committees, oldest first. */
    List<Committee> committees(MissionId mission) {
        return List.copyOf(committees.getOrDefault(mission, List.of()));
    }

    Collection<Committee> committeesView() {
        return committees.values().stream().flatMap(List::stream).toList();
    }

    /** Part of a plan: the committee meets once the director approves it. The chair is the decision ticket's assignee. */
    List<EventEnvelope> convene(
            CommandContext ctx,
            MissionId id,
            TicketId ticket,
            String topic,
            List<AgentId> members,
            List<String> roles,
            Committee.Format format) {
        CommitteeConvened convened = convening.check(ctx, id, ticket, topic, members, roles, format);
        return view.record(ctx, id).emit(convened).events();
    }

    /** When the last expected contribution of a step arrives, Floor42 moves the committee on. */
    List<EventEnvelope> contribute(
            CommandContext ctx,
            CommitteeRef ref,
            AgentId member,
            Committee.Kind kind,
            String text,
            String proposal,
            String position,
            String criticalObjection,
            List<String> questions) {
        Committee c = expected(ctx, ref, member, kind);
        requireText(text, "text");
        Recorder r = view.record(ctx, ref.mission())
                .emit(new CommitteeContributed(
                        ref.mission(),
                        member,
                        c.round,
                        kind,
                        text,
                        blankToNull(proposal),
                        blankToNull(position),
                        blankToNull(criticalObjection),
                        null,
                        raised(questions),
                        ref.number()));
        moveOn(r, c);
        return r.events();
    }

    /** The committee, waiting for this member's contribution of this kind now. */
    private Committee expected(CommandContext ctx, CommitteeRef ref, AgentId member, Committee.Kind kind) {
        Mission m = view.missionFor(ctx, ref.mission());
        Committee c = existing(ref);
        require(ctx.actor().is(member), Refusal.FORBIDDEN, "A contribution is made by its member");
        require(m.status == MissionStatus.IN_PROGRESS, Refusal.MISSION_NOT_ACTIVE, m.id() + " is " + m.status);
        require(c.pending().contains(member), Refusal.NOT_EXPECTED, member + " owes no contribution now");
        Committee.Kind expected = expectedKind(c.stage);
        require(kind == expected, Refusal.INVALID_ARGUMENT, "Expected a " + expected + " now");
        return c;
    }

    /** A member's QUESTIONS line: what it could not settle without the director, at most ten. */
    private static List<String> raised(List<String> questions) {
        return questions == null
                ? List.of()
                : questions.stream()
                        .filter(q -> q != null && !q.isBlank())
                        .map(String::strip)
                        .limit(10)
                        .toList();
    }

    private static Committee.Kind expectedKind(Committee.Stage stage) {
        return switch (stage) {
            case ANALYSIS -> Committee.Kind.ANALYSIS;
            case DEBATE -> Committee.Kind.REBUTTAL;
            case SYNTHESIS, CLARIFICATION, AWAITING_ANSWERS, AWAITING_DIRECTOR, ADOPTED, REJECTED ->
                Committee.Kind.SYNTHESIS;
        };
    }

    /**
     * The director speaks in person in the seat of their double, in the current analysis or debate round: said after
     * the double in the same round, it replaces it as the seat's position; said before, the double does not speak.
     */
    List<EventEnvelope> contributeAsDirector(
            CommandContext ctx,
            CommitteeRef ref,
            String text,
            String proposal,
            String position,
            String criticalObjection) {
        Committee c = directorsTurn(ctx, ref);
        requireText(text, "text");
        Recorder r = view.record(ctx, ref.mission())
                .emit(new CommitteeContributed(
                        ref.mission(),
                        c.format().directorSeat(),
                        c.round,
                        c.stage == Committee.Stage.ANALYSIS ? Committee.Kind.ANALYSIS : Committee.Kind.REBUTTAL,
                        text.strip(),
                        blankToNull(proposal),
                        blankToNull(position),
                        blankToNull(criticalObjection),
                        true,
                        List.of(),
                        ref.number()));
        moveOn(r, c);
        return r.events();
    }

    private Committee directorsTurn(CommandContext ctx, CommitteeRef ref) {
        requireDirector(ctx);
        Mission m = view.missionFor(ctx, ref.mission());
        Committee c = existing(ref);
        require(
                c.format().directorSeat() != null,
                Refusal.NO_DIRECTOR_SEAT,
                "This committee has no seat for the director");
        require(m.status == MissionStatus.IN_PROGRESS, Refusal.MISSION_NOT_ACTIVE, m.id() + " is " + m.status);
        require(
                c.stage == Committee.Stage.ANALYSIS || c.stage == Committee.Stage.DEBATE,
                Refusal.INVALID_TRANSITION,
                "The committee is not analysing or debating now");
        return c;
    }

    /** Analysis to debate; debate to the record, or to another round while members disagree; record to the director. */
    private void moveOn(Recorder r, Committee c) {
        if (!c.pending().isEmpty()) return;
        Recorder office = r.as(Actor.system());
        MissionId id = c.mission();
        int no = c.number();
        switch (c.stage) {
            case ANALYSIS -> office.emit(new CommitteeAdvanced(id, Committee.Stage.DEBATE, c.round + 1, no));
            case DEBATE -> {
                boolean again = c.agreement().isEmpty()
                        && c.debateRoundsSinceRecord() < c.format().maxDebateRounds();
                office.emit(
                        again
                                ? new CommitteeAdvanced(id, Committee.Stage.DEBATE, c.round + 1, no)
                                : new CommitteeAdvanced(id, Committee.Stage.SYNTHESIS, c.round, no));
            }
            case SYNTHESIS, CLARIFICATION, AWAITING_ANSWERS, AWAITING_DIRECTOR, ADOPTED, REJECTED ->
                toDirector(office, c);
        }
    }

    /** Through the business analyst when members raised new questions, else straight to the director. */
    private void toDirector(Recorder office, Committee c) {
        AgentId analyst = clarifier(c);
        if (analyst != null) {
            office.emit(new CommitteeClarificationOpened(c.mission(), analyst, c.round, c.number()));
        } else {
            office.emit(new CommitteeAdvanced(c.mission(), Committee.Stage.AWAITING_DIRECTOR, c.round, c.number()));
            arbitration.ask(office, c);
        }
    }

    /** Null when no question was raised, the committee went through it enough already, or no analyst works here. */
    private AgentId clarifier(Committee c) {
        if (c.clarifications >= Committee.MAX_CLARIFICATIONS
                || c.unclarifiedQuestions().isEmpty()) return null;
        return view.agents().stream()
                .filter(a -> a.workRole == WorkRole.ANALYST && !a.dismissed && !a.suspended)
                .map(Agent::id)
                .findFirst()
                .orElse(null);
    }

    /** Once every clarification question is closed, the answers go back to the committee (none: to the director). */
    void clarificationAnswered(Recorder r, Question q) {
        Committee c = askedBy(q).orElse(null);
        if (c == null || c.stage != Committee.Stage.AWAITING_ANSWERS) return;
        List<Question> asked = c.clarificationQuestions.stream()
                .map(x -> view.question(x).orElse(null))
                .toList();
        if (!asked.stream().allMatch(x -> x.status() != QuestionStatus.OPEN)) return;
        List<String> answers = asked.stream()
                .filter(x -> x.status() == QuestionStatus.ANSWERED)
                .map(x -> "The director answered \"" + x.subject() + "\": " + x.answer())
                .toList();
        Recorder office = r.as(Actor.system());
        office.emit(new CommitteeClarified(c.mission(), answers, c.number()));
        if (answers.isEmpty()) arbitration.ask(office, c);
    }

    /**
     * The committee a question of the desk belongs to: the latest one deliberating on the question's ticket (the
     * decision ticket), else the mission's latest. Questions carry no committee number of their own.
     */
    Optional<Committee> askedBy(Question q) {
        List<Committee> held = committees.getOrDefault(q.mission(), List.of());
        return held.reversed().stream()
                .filter(c -> c.ticket().equals(q.ticket()))
                .findFirst()
                .or(() -> held.isEmpty() ? Optional.empty() : Optional.of(held.getLast()));
    }

    private Committee existing(CommitteeRef ref) {
        Committee c = committee(ref).orElse(null);
        require(c != null, Refusal.NO_COMMITTEE, ref.mission() + " has no committee");
        return c;
    }

    /** The only way this area's state changes. */
    void apply(CommitteeEvent event, EventEnvelope envelope) {
        Instant at = envelope.occurredAt();
        switch (event) {
            case CommitteeConvened e -> on(e);
            case CommitteeContributed e -> on(addressed(e), e, at);
            case CommitteeAdvanced e -> on(addressed(e), e);
            case CommitteeClarificationOpened e -> on(addressed(e), e);
            case CommitteeClarificationWritten e -> addressed(e).clarificationNote = e.text();
            case CommitteeClarified e -> on(addressed(e), e);
            case CommitteeSentBack e -> on(addressed(e), e);
            case CommitteeArbitrated e -> on(addressed(e), e);
        }
    }

    /** The committee an event names; one that names no committee convened stops the replay (ADR-0042). */
    private Committee addressed(CommitteeEvent e) {
        return committee(new CommitteeRef(e.mission(), e.committeeNo()))
                .orElseThrow(() -> new IllegalStateException(e.mission() + " has no committee " + e.committeeNo()
                        + " for " + e.getClass().getSimpleName()));
    }

    /**
     * The next committee of the mission. A number already held is convened again (journals before 1.8.0 knew one
     * committee per mission, which a later convening replaced); a gap in the numbers stops the replay.
     */
    private void on(CommitteeConvened e) {
        List<Committee> held = committees.computeIfAbsent(e.mission(), k -> new ArrayList<>());
        int no = e.committeeNo();
        if (no > held.size() + 1) {
            throw new IllegalStateException(e.mission() + " convenes committee " + no + " after " + held.size());
        }
        Committee c = new Committee(
                new CommitteeRef(e.mission(), no),
                e.ticket(),
                e.topic(),
                e.members().stream().map(AgentId::new).toList(),
                e.roles(),
                e.format());
        if (no <= held.size()) held.set(no - 1, c);
        else held.add(c);
    }

    private static void on(Committee c, CommitteeContributed e, Instant at) {
        c.contributions.add(new Committee.Contribution(
                e.member(),
                e.round(),
                e.kind(),
                e.text(),
                e.proposal(),
                e.position(),
                e.criticalObjection(),
                at,
                Boolean.TRUE.equals(e.byDirector()),
                e.questions() == null ? List.of() : e.questions()));
    }

    private static void on(Committee c, CommitteeAdvanced e) {
        c.stage = e.stage();
        c.round = e.round();
    }

    private static void on(Committee c, CommitteeClarificationOpened e) {
        c.stage = Committee.Stage.CLARIFICATION;
        c.analyst = e.analyst();
        c.clarificationQuestions.clear();
    }

    private static void on(Committee c, CommitteeClarified e) {
        c.clarifiedUpTo = c.round;
        c.clarifications++;
        if (e.answers().isEmpty()) {
            c.stage = Committee.Stage.AWAITING_DIRECTOR;
        } else {
            c.constraints.addAll(e.answers());
            c.stage = Committee.Stage.DEBATE;
            c.round = c.round + 1;
        }
    }

    private static void on(Committee c, CommitteeSentBack e) {
        c.constraints.add(e.constraint());
        c.stage = Committee.Stage.DEBATE;
        c.round = c.round + 1;
    }

    private static void on(Committee c, CommitteeArbitrated e) {
        c.stage = e.adopted() ? Committee.Stage.ADOPTED : Committee.Stage.REJECTED;
        c.implement = e.implement();
    }

    /** A clarification question is its committee's while it waits for the answers. */
    void asked(QuestionAsked e) {
        if (e.kind() != QuestionKind.CLARIFICATION) return;
        List<Committee> held = committees.getOrDefault(e.mission(), List.of());
        held.reversed().stream()
                .filter(c ->
                        c.stage == Committee.Stage.CLARIFICATION && c.ticket().equals(e.ticket()))
                .findFirst()
                .or(() -> held.isEmpty() ? Optional.empty() : Optional.of(held.getLast()))
                .ifPresent(c -> c.clarificationQuestions.add(e.question()));
    }

    List<EventEnvelope> sendBack(CommandContext ctx, CommitteeRef ref, String constraint) {
        return arbitration.sendBack(ctx, ref, constraint);
    }

    void arbitrated(Recorder r, Question q, String chosen) {
        askedBy(q).ifPresent(c -> arbitration.arbitrated(r, c, chosen));
    }

    List<EventEnvelope> clarify(
            CommandContext ctx, CommitteeRef ref, String text, List<Committee.Clarification> asked) {
        return arbitration.clarify(ctx, ref, text, asked);
    }
}
