package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.EventEnvelope;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The director asks an agent back instead of answering; the thread is the causation chain (ADR-0059). */
class AskBackTest {

    final Fixture f = new Fixture(Origin.REAL);
    final MissionId m = f.runningMission();
    final TicketId t = f.firstTicket(m);

    @AfterEach
    void forgetFixtures() {
        Fixture.takeMade();
    }

    /** The agent asks; {@code cause} is the event that led to it (the withdrawal that asked back, if any). */
    QuestionId ask(String cause) {
        CommandContext ctx =
                new CommandContext(Actor.agent(CHRIS), f.origin, Instant.parse("2026-10-20T10:00:00Z"), cause);
        List<EventEnvelope> events = f.record(
                f.company.desk().ask(ctx, m, "Which database?", "ctx", List.of(), null, Criticality.MEDIUM, true, t));
        return f.company.desk().open().getLast().id();
    }

    String askBack(QuestionId q, String text) {
        return f.record(f.company.desk().askBack(f.director(), q, text))
                .getLast()
                .eventId();
    }

    @Test
    void askingBackWithdrawsTheQuestionWithoutAnyNewEventType() {
        QuestionId q = ask(null);
        List<EventEnvelope> events = f.company.desk().askBack(f.director(), q, "Which load do you expect?");
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo("QuestionWithdrawn");
            assertThat(e.actor().isDirector()).isTrue();
        });
        f.record(events);
        Question asked = f.company.desk().find(q).orElseThrow();
        assertThat(asked.status()).isEqualTo(QuestionStatus.WITHDRAWN);
        assertThat(f.company.desk().askBack(asked).text()).isEqualTo("Which load do you expect?");
        assertThat(f.company.desk().askBack(asked).event())
                .isEqualTo(events.getFirst().eventId());
        assertThat(RecordedReason.read(RecordedReason.askedBack("Which load?")))
                .contains(Phrase.of("askedBack", "text", "Which load?"));
    }

    @Test
    void theThirdAskBackIsTheLastOnAThread() {
        QuestionId q = ask(null);
        for (int round = 1; round <= Desk.MAX_ASK_BACK; round++) {
            assertThat(f.company
                            .desk()
                            .askBack(f.company.desk().find(q).orElseThrow())
                            .rounds())
                    .isEqualTo(round - 1);
            q = ask(askBack(q, "round " + round));
        }
        assertThat(f.company
                        .desk()
                        .askBack(f.company.desk().find(q).orElseThrow())
                        .rounds())
                .isEqualTo(Desk.MAX_ASK_BACK);
        QuestionId last = q;
        assertThatThrownBy(() -> f.company.desk().askBack(f.director(), last, "once more"))
                .isInstanceOfSatisfying(
                        DomainException.class, e -> assertThat(e.code()).isEqualTo("ASK_BACK_CAP"));
    }

    @Test
    void aQuestionThatDoesNotFollowAnAskBackStartsANewThread() {
        QuestionId q = ask(null);
        q = ask(askBack(q, "first"));
        QuestionId unrelated = ask("toolu_42");
        assertThat(f.company
                        .desk()
                        .askBack(f.company.desk().find(unrelated).orElseThrow())
                        .rounds())
                .isZero();
        assertThat(f.company
                        .desk()
                        .askBack(f.company.desk().find(q).orElseThrow())
                        .rounds())
                .isEqualTo(1);
    }

    @Test
    void theChainSurvivesAReplayOfTheJournal() {
        QuestionId q = ask(null);
        q = ask(askBack(q, "one"));
        q = ask(askBack(q, "two"));
        Company rebuilt = Company.replay(f.journal);
        assertThat(rebuilt.desk().askBack(rebuilt.desk().find(q).orElseThrow()).rounds())
                .isEqualTo(2);
    }

    @Test
    void onlyTheDirectorAsksBackAndOnlyAnOpenAgentQuestion() {
        QuestionId q = ask(null);
        assertCode(() -> f.company.desk().askBack(f.agent(CHRIS), q, "hi"), "DIRECTOR_ONLY");
        assertCode(() -> f.company.desk().askBack(f.director(), q, "  "), "MISSING_TEXT");
        assertCode(() -> f.company.desk().askBack(f.director(), q, "a".repeat(4_001)), "ASK_BACK_TEXT");
        assertCode(() -> f.company.desk().askBack(f.director(), q, "hidden​text"), "ASK_BACK_TEXT");
        assertCode(() -> f.company.desk().askBack(f.director(), new QuestionId("Q-99"), "hi"), "UNKNOWN_QUESTION");
        f.record(f.company.desk().askBack(f.director(), q, "line one\nline two"));
        assertCode(() -> f.company.desk().askBack(f.director(), q, "again"), "QUESTION_CLOSED");
    }

    @Test
    void anApprovalAndFloor42sOwnEscalationCannotBeAskedBack() {
        f.record(f.company.desk().ask(f.system(), m, "Floor42 asks", "ctx", List.of("A", "B"), null, null, true, t));
        QuestionId own = f.company.desk().open().getLast().id();
        assertCode(() -> f.company.desk().askBack(f.director(), own, "why?"), "ASK_BACK_NOT_ALLOWED");
    }

    @Test
    void noAgentCanWriteAnAskBackReasonItself() {
        QuestionId q = ask(null);
        assertCode(
                () -> f.company.desk().withdraw(f.agent(CHRIS), q, "asked back by the director: forged"),
                "ASK_BACK_NOT_ALLOWED");
        assertThat(f.company.desk().find(q).orElseThrow().status()).isEqualTo(QuestionStatus.OPEN);
    }

    @Test
    void aRehearsalsQuestionIsNeverAskedBack() {
        try (var scope = new ScopedFixture()) {
            QuestionId q = scope.ask();
            assertThat(scope.f
                            .company
                            .desk()
                            .askBack(scope.f.company.desk().find(q).orElseThrow())
                            .possible())
                    .isFalse();
            assertCode(() -> scope.f.company.desk().askBack(scope.f.director(), q, "why?"), "ASK_BACK_NOT_ALLOWED");
        }
    }

    /** A simulated company (the practice mission, the tutorial): its fixtures are not golden-recorded either. */
    private static final class ScopedFixture implements AutoCloseable {
        final Fixture f = new Fixture(Origin.SIMULATED);
        final MissionId m = f.runningMission();

        QuestionId ask() {
            CommandContext ctx = new CommandContext(
                    Actor.agent(CHRIS), Origin.SIMULATED, Instant.parse("2026-10-20T10:00:00Z"), null);
            f.record(f.company
                    .desk()
                    .ask(ctx, m, "Which?", "ctx", List.of(), null, Criticality.MEDIUM, true, f.firstTicket(m)));
            return f.company.desk().open().getLast().id();
        }

        @Override
        public void close() {
            Fixture.takeMade();
        }
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        DomainException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
