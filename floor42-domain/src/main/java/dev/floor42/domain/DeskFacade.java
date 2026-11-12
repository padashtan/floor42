package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import java.util.List;
import java.util.Optional;

/**
 * The director's desk: the questions Floor42 and the agents ask, their answers, and the approvals the director
 * remembers. Reached by {@link Company#desk()}; the rules live in the desk and in the answers each kind of question
 * leads to.
 */
public final class DeskFacade {

    private final Desk desk;
    private final Answers answers;

    DeskFacade(Desk desk, Answers answers) {
        this.desk = desk;
        this.answers = answers;
    }

    /**
     * A question's ask-backs (ADR-0059).
     *
     * @param rounds how many times the director asked back on its thread before it
     * @param possible an agent's open question, under the cap (its session may still be gone)
     * @param event the withdrawal that asked it back, the cause of the question that follows; null if it was not
     * @param text the director's words; null if it was not asked back
     */
    public record AskBack(int rounds, boolean possible, String event, String text) {}

    public AskBack askBack(Question q) {
        return desk.askBackOf(q);
    }

    public List<Question> of(MissionId mission) {
        return desk.questions(mission);
    }

    public List<Question> open() {
        return desk.openQuestions();
    }

    /** A question number held by a journal row this version could not read: no new question takes it. */
    public void reserve(QuestionId id) {
        desk.reserve(id);
    }

    public Optional<Question> find(QuestionId id) {
        return desk.question(id);
    }

    /** True when this answered approval still decides later requests in its scope. */
    public boolean remembered(QuestionId id) {
        return desk.remembered(id);
    }

    public List<EventEnvelope> ask(
            CommandContext ctx,
            MissionId id,
            String subject,
            String context,
            List<String> options,
            String recommendation,
            Criticality criticality,
            boolean blocking,
            TicketId ticket) {
        return ask(ctx, id, subject, context, options, recommendation, criticality, blocking, ticket, null);
    }

    /** @param phrase what Floor42 means by the subject and context it wrote, for a screen in another language */
    public List<EventEnvelope> ask(
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
        return desk.ask(ctx, id, subject, context, options, recommendation, criticality, blocking, ticket, phrase);
    }

    /**
     * What {@link #answer} checks, without recording anything: returns the answer as it would be recorded, or throws the
     * refusal. Lets a caller that records something else first check the answer before.
     */
    public String checkAnswer(CommandContext ctx, QuestionId id, String answer) {
        return desk.checkAnswer(ctx, id, answer);
    }

    /** Answering may unlock work: an approved action becomes authorized, an escalated review gets its outcome. */
    public List<EventEnvelope> answer(CommandContext ctx, QuestionId id, String answer) {
        return answers.answer(ctx, id, answer, null);
    }

    /**
     * The same, and the director asks to be asked again for one refused call: {@code askAgain} names its remembered
     * approval, one of {@link #liftable}, never text. Its refusal no longer decides; nothing is authorized by it.
     */
    public List<EventEnvelope> answer(CommandContext ctx, QuestionId id, String answer, QuestionId askAgain) {
        return answers.answer(ctx, id, answer, askAgain);
    }

    /** The remembered refusals of the asking agent the director may lift while answering this open question. */
    public List<Question> liftable(QuestionId id) {
        return desk.question(id).map(answers::liftable).orElse(List.of());
    }

    /** The director asks the agent back instead of answering (ADR-0059): at most {@link Desk#MAX_ASK_BACK} times. */
    public List<EventEnvelope> askBack(CommandContext ctx, QuestionId id, String text) {
        return desk.askBack(ctx, id, text);
    }

    public List<EventEnvelope> withdraw(CommandContext ctx, QuestionId id, String reason) {
        return desk.withdraw(ctx, id, reason);
    }

    /**
     * The director takes back a remembered answer: the next request in its scope asks again. Requests already decided
     * keep their decision.
     */
    public List<EventEnvelope> forgetApproval(CommandContext ctx, QuestionId id) {
        return desk.forgetApproval(ctx, id);
    }
}
