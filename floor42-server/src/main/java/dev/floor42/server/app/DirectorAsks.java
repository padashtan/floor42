package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Criticality;
import dev.floor42.domain.Phrase;
import dev.floor42.domain.QuestionId;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.event.DeskEvent.QuestionAsked;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.AskOutcome;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One session's {@code ask_director} calls (ADR-0027, ADR-0059): a blocking question on the director's desk, the wait
 * for it, and what the director did with it. The question asked straight after an ask-back is linked to it (the
 * withdrawal is its cause); that link is consumed by the next ask and dropped when the session does anything else.
 */
final class DirectorAsks {

    private final OfficeService office;
    private final SessionSpec spec;
    /** The withdrawal that asked back, until the session's very next ask or its next other tool call. */
    private final AtomicReference<String> followUp = new AtomicReference<>();
    /** What this session waits on: released when it ends, so an ask-back is then refused (SESSION_GONE). */
    private final Set<Runnable> waits = ConcurrentHashMap.newKeySet();

    DirectorAsks(OfficeService office, SessionSpec spec) {
        this.office = office;
        this.spec = spec;
    }

    /** The session did something else than ask: an ask-back no longer leads to its next question. */
    void otherToolCall() {
        followUp.set(null);
    }

    /** The session ended: nothing waits for the director any more, whatever its question's timeout says. */
    void sessionEnded() {
        waits.forEach(Runnable::run);
        waits.clear();
        followUp.set(null);
    }

    AskOutcome ask(String question, List<String> options, String recommendation, String toolUseId, Duration patience) {
        QuestionId id = put(question, options, recommendation, toolUseId);
        awaitAnswer(id, patience);
        var q = office.query(c -> c.desk().find(id)).orElseThrow();
        var back = office.query(c -> c.desk().askBack(q));
        if (q.status() == QuestionStatus.ANSWERED) return new AskOutcome.Answer(q.answer());
        if (back.event() != null) {
            followUp.set(back.event());
            return new AskOutcome.AskedBack(back.text());
        }
        if (q.status() == QuestionStatus.OPEN) {
            office.execute(c -> c.desk()
                    .withdraw(
                            office.context(Actor.agent(spec.agent()), spec.origin()),
                            id,
                            "no answer within " + patience.toMinutes() + " min: " + spec.agent()
                                    + " goes on with its own judgment"));
        }
        return new AskOutcome.NoAnswer();
    }

    private QuestionId put(String question, List<String> options, String recommendation, String toolUseId) {
        String cause = followUp.getAndSet(null);
        String causation = cause == null ? toolUseId : cause;
        var asked = office.execute(c -> c.desk()
                .ask(
                        office.context(Actor.agent(spec.agent()), spec.origin(), causation),
                        spec.mission(),
                        question,
                        spec.agent() + " asked this during its session on " + spec.ticket()
                                + "; the session waits for your answer.",
                        options,
                        recommendation,
                        Criticality.MEDIUM,
                        true,
                        spec.ticket(),
                        Phrase.of(
                                "agentAsked",
                                "agent",
                                spec.agent().value(),
                                "ticket",
                                spec.ticket().value())));
        return asked.stream()
                .map(e -> e.event())
                .filter(QuestionAsked.class::isInstance)
                .map(e -> ((QuestionAsked) e).question())
                .findFirst()
                .orElseThrow();
    }

    /** Waits until the question is no longer open, at most {@code patience}. */
    private void awaitAnswer(QuestionId id, Duration patience) {
        CompletableFuture<Void> settled = new CompletableFuture<>();
        Runnable wait = office.waitOn(id);
        waits.add(wait);
        try (AutoCloseable ignored = office.subscribe(events -> {
            if (!open(id)) settled.complete(null);
        })) {
            if (open(id)) settled.get(patience.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Handled: nobody answered in time: the question goes, the agent decides.
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept for the caller; the question is withdrawn by the caller, the agent decides
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            throw new IllegalStateException("Waiting for the director failed", e);
        } finally {
            waits.remove(wait);
            wait.run();
        }
    }

    private boolean open(QuestionId id) {
        return office.query(c ->
                c.desk().find(id).map(q -> q.status() == QuestionStatus.OPEN).orElse(false));
    }
}
