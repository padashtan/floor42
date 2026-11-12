package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.agentOf;
import static dev.floor42.domain.Rules.requireDirector;
import static dev.floor42.domain.Rules.requireText;

import dev.floor42.domain.event.DeskEvent;
import dev.floor42.domain.event.DeskEvent.ApprovalForgotten;
import dev.floor42.domain.event.DeskEvent.QuestionAnswered;
import dev.floor42.domain.event.DeskEvent.QuestionAsked;
import dev.floor42.domain.event.DeskEvent.QuestionWithdrawn;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.workflow.StepText;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** The director's desk: the questions asked of the director, their answers, and the approvals they remember. */
final class Desk {

    /** Asks-back on one thread of questions, the same agent asking again each time. */
    public static final int MAX_ASK_BACK = 3;

    static final int MAX_ASK_BACK_TEXT = 4_000;

    /** @param event the withdrawal's event id, the cause of the question that follows */
    record AskedBackNote(String event, String text) {}

    private final CompanyView view;
    private final Map<QuestionId, Question> questions = new LinkedHashMap<>();
    /** Approval questions whose answer decides nothing beyond their own action. */
    private final Set<QuestionId> notRemembered = new HashSet<>();
    /** The withdrawal that asked a question back, by its event id: the next question names it as its cause. */
    private final Map<String, QuestionId> askedBackBy = new HashMap<>();
    /** Asks-back before a question on its thread (ADR-0059); absent for none. */
    private final Map<QuestionId, Integer> askedBackRounds = new HashMap<>();
    /** The withdrawal that asked a question back, and the director's words. */
    private final Map<QuestionId, AskedBackNote> askedBackNotes = new HashMap<>();

    Desk(CompanyView view) {
        this.view = view;
    }

    List<Question> questions(MissionId mission) {
        return questions.values().stream()
                .filter(q -> q.mission().equals(mission))
                .toList();
    }

    Collection<Question> questionsView() {
        return Collections.unmodifiableCollection(questions.values());
    }

    List<Question> openQuestions() {
        return questions.values().stream()
                .filter(q -> q.status() == QuestionStatus.OPEN)
                .toList();
    }

    Optional<Question> question(QuestionId id) {
        return Optional.ofNullable(questions.get(id));
    }

    Question existing(QuestionId id) {
        Question q = questions.get(id);
        require(q != null, Refusal.UNKNOWN_QUESTION, "No question " + id);
        return q;
    }

    /** The highest Q-n number ever asked, read or not: a number is never given twice. */
    private transient int highestQuestion; // transient: kept out of the golden field dumps, see reserve

    QuestionId nextId() {
        return new QuestionId("Q-" + (Math.max(highestQuestion, questions.size()) + 1));
    }

    /** A question number held by a journal row this version could not read: no new question takes it. */
    void reserve(QuestionId id) {
        String v = id.value();
        if (v.matches("Q-\\d{1,9}")) highestQuestion = Math.max(highestQuestion, Integer.parseInt(v.substring(2)));
    }

    boolean forgotten(QuestionId id) {
        return notRemembered.contains(id);
    }

    /** True when this answered approval still decides later requests in its scope. */
    boolean remembered(QuestionId id) {
        Question q = questions.get(id);
        return q != null
                && q.kind() == QuestionKind.ACTION_APPROVAL
                && q.status() == QuestionStatus.ANSWERED
                && !notRemembered.contains(id);
    }

    /** Requests already decided keep their decision; the next one in the scope asks again. */
    List<EventEnvelope> forgetApproval(CommandContext ctx, QuestionId id) {
        requireDirector(ctx);
        Question q = existing(id);
        view.missionFor(ctx, q.mission());
        require(remembered(id), Refusal.NOT_REMEMBERED, id + " is not a remembered approval");
        return view.record(ctx, q.mission())
                .emit(new ApprovalForgotten(id, "forgotten by the director"))
                .events();
    }

    /** Floor42's own options carry their code; an agent's never do, whatever its words. */
    List<EventEnvelope> ask(
            CommandContext ctx,
            MissionId id,
            String subject,
            String context,
            List<String> options,
            String recommendation,
            Criticality criticality,
            boolean blocking,
            TicketId ticket,
            Phrase phrase) {
        checkAsking(ctx, id, subject, ticket);
        return view.record(ctx, id)
                .emit(question(
                        agentOf(ctx),
                        id,
                        subject,
                        context,
                        options,
                        recommendation,
                        criticality,
                        blocking,
                        ticket,
                        phrase))
                .events();
    }

    private QuestionAsked question(
            AgentId askedBy,
            MissionId id,
            String subject,
            String context,
            List<String> options,
            String recommendation,
            Criticality criticality,
            boolean blocking,
            TicketId ticket,
            Phrase phrase) {
        List<String> offered = options == null ? List.of() : options;
        return new QuestionAsked(
                nextId(),
                id,
                QuestionKind.OPEN,
                askedBy,
                subject,
                context,
                offered,
                recommendation,
                criticality == null ? Criticality.MEDIUM : criticality,
                blocking,
                ticket,
                null,
                phrase,
                askedBy == null ? OptionCode.codesOf(offered) : OptionCode.none(offered));
    }

    private void checkAsking(CommandContext ctx, MissionId id, String subject, TicketId ticket) {
        Mission m = view.missionFor(ctx, id);
        require(!m.status.isTerminal(), Refusal.MISSION_CLOSED, id + " is closed");
        require(
                ctx.actor().kind() != Actor.Kind.DIRECTOR,
                Refusal.FORBIDDEN,
                "The director gives directives, not questions");
        requireText(subject, "subject");
        if (ticket != null) {
            require(
                    view.existingTicket(ticket).mission().equals(id),
                    Refusal.UNKNOWN_TICKET,
                    ticket + " is not part of " + id);
        }
    }

    /** The answer as it would be recorded, or the refusal: one of the options (or its stable code), else free. */
    String checkAnswer(CommandContext ctx, QuestionId id, String answer) {
        requireDirector(ctx);
        Question q = existing(id);
        view.missionFor(ctx, q.mission());
        require(q.status() == QuestionStatus.OPEN, Refusal.QUESTION_CLOSED, id + " is " + q.status());
        requireText(answer, "answer");
        if (q.options().isEmpty()) return answer.strip();
        return q.options().stream()
                .filter(o -> o.equalsIgnoreCase(answer.strip()))
                .findFirst()
                // An API client may answer with the option's stable code (RETRY) rather than its label.
                .or(() -> Arrays.stream(OptionCode.values())
                        .filter(c -> c.name().equalsIgnoreCase(answer.strip()))
                        .map(q::optionOf)
                        .filter(Objects::nonNull)
                        .findFirst())
                .orElseThrow(() -> new DomainException(Refusal.INVALID_ANSWER, "Answer one of " + q.options()));
    }

    /** Records the answer checked by {@link #checkAnswer}; what it settles elsewhere follows on the same recorder. */
    Recorder recordAnswer(CommandContext ctx, Question q, String chosen) {
        return view.record(ctx, q.mission()).emit(new QuestionAnswered(q.id(), chosen));
    }

    List<EventEnvelope> withdraw(CommandContext ctx, QuestionId id, String reason) {
        Question q = existing(id);
        view.missionFor(ctx, q.mission());
        require(
                ctx.actor().isDirector() || (q.askedBy() != null && ctx.actor().is(q.askedBy())),
                Refusal.FORBIDDEN,
                "Only the director or the asking agent withdraws " + id);
        require(q.status() == QuestionStatus.OPEN, Refusal.QUESTION_CLOSED, id + " is " + q.status());
        // An orphaned action would be authorised by another question's answer on the same scope.
        require(
                ctx.actor().isDirector() || q.kind() != QuestionKind.ACTION_APPROVAL,
                Refusal.FORBIDDEN,
                "Only the director withdraws the approval question " + id);
        require(
                RecordedReason.askedBackText(reason).isEmpty(),
                Refusal.ASK_BACK_NOT_ALLOWED,
                "Only askBack writes an ask-back reason");
        return view.record(ctx, q.mission())
                .emit(new QuestionWithdrawn(id, reason))
                .events();
    }

    int rounds(QuestionId id) {
        return askedBackRounds.getOrDefault(id, 0);
    }

    /** What the screen and the session need of a question's ask-backs (ADR-0059). */
    DeskFacade.AskBack askBackOf(Question q) {
        boolean real =
                view.mission(q.mission()).map(m -> m.origin() == Origin.REAL).orElse(false);
        boolean possible = real
                && q.status() == QuestionStatus.OPEN
                && q.askedBy() != null
                && q.action() == null
                && q.kind() == QuestionKind.OPEN
                && rounds(q.id()) < MAX_ASK_BACK;
        AskedBackNote note = askedBackNotes.get(q.id());
        return new DeskFacade.AskBack(
                rounds(q.id()), possible, note == null ? null : note.event(), note == null ? null : note.text());
    }

    /**
     * The director answers an agent's question with a question or a remark of their own (ADR-0059): the question is
     * withdrawn, the words kept with it, and the waiting session is told. Never for an approval or Floor42's own
     * escalation, and at most {@link #MAX_ASK_BACK} times on one thread.
     */
    List<EventEnvelope> askBack(CommandContext ctx, QuestionId id, String text) {
        requireDirector(ctx);
        Question q = existing(id);
        Mission m = view.missionFor(ctx, q.mission());
        require(q.status() == QuestionStatus.OPEN, Refusal.QUESTION_CLOSED, id + " is " + q.status());
        require(m.origin() == Origin.REAL, Refusal.ASK_BACK_NOT_ALLOWED, "A rehearsal's question is not asked back");
        require(
                q.askedBy() != null && q.action() == null && q.kind() == QuestionKind.OPEN,
                Refusal.ASK_BACK_NOT_ALLOWED,
                "Only an agent's question can be asked back, not " + id);
        require(
                rounds(q.id()) < MAX_ASK_BACK,
                Refusal.ASK_BACK_CAP,
                "Asked back " + rounds(q.id()) + " times already: answer, or give a directive");
        requireText(text, "text");
        StepText.checkMessage(text, MAX_ASK_BACK_TEXT).ifPresent(code -> {
            throw new DomainException(Refusal.ASK_BACK_TEXT, "The text is refused (" + code + ")");
        });
        return view.record(ctx, q.mission())
                .emit(new QuestionWithdrawn(id, RecordedReason.askedBack(text)))
                .events();
    }

    /** The only way this area's state changes. */
    void apply(DeskEvent event, EventEnvelope envelope) {
        Instant at = envelope.occurredAt();
        switch (event) {
            case QuestionAsked e -> on(e, at, envelope.causationId());
            case QuestionAnswered e -> on(e);
            case QuestionWithdrawn e -> on(e, envelope);
            case ApprovalForgotten e -> on(e);
        }
    }

    void on(QuestionAsked e, Instant at, String causation) {
        Question asked = new Question(
                e.question(),
                e.mission(),
                e.kind(),
                e.askedBy(),
                e.subject(),
                e.context(),
                e.options(),
                e.recommendation(),
                e.criticality(),
                e.blocking(),
                e.ticket(),
                e.action(),
                at,
                e.phrase(),
                e.optionCodes());
        // Two asks of one id would merge two questions: the replay stops instead.
        if (questions.containsKey(e.question())) throw new IllegalStateException(e.question() + " is created twice");
        QuestionId before = causation == null ? null : askedBackBy.get(causation);
        if (before != null) askedBackRounds.put(e.question(), rounds(before) + 1);
        reserve(e.question());
        questions.put(e.question(), asked);
    }

    void on(QuestionAnswered e) {
        Question q = questions.get(e.question());
        q.status = QuestionStatus.ANSWERED;
        q.answer = e.answer();
    }

    void on(QuestionWithdrawn e, EventEnvelope envelope) {
        Question q = questions.get(e.question());
        q.status = QuestionStatus.WITHDRAWN;
        Optional<String> back = RecordedReason.askedBackText(e.reason());
        if (back.isPresent() && envelope.actor().isDirector()) {
            askedBackNotes.put(q.id(), new AskedBackNote(envelope.eventId(), back.get()));
            askedBackBy.put(envelope.eventId(), q.id());
        }
    }

    void on(ApprovalForgotten e) {
        notRemembered.add(e.question());
    }
}
