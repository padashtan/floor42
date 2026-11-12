package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.GEOFFREY;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.event.DeskEvent;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.TicketEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Floor42's own options mean what their stable code says, whatever their label; questions and tickets journaled before
 * codes and kinds existed are still read by the rule of their time (ADR-0009, amendment 2026-10-31).
 */
class OptionCodeTest {

    final Fixture f = new Fixture();
    final MissionId m = f.mission();
    final TicketId t = f.ticket(m, "Deliver");

    EventEnvelope envelope(DomainEvent event) {
        return new EventEnvelope(
                "e-" + f.journal.size(),
                Instant.parse("2026-10-20T10:00:00Z"),
                Origin.SIMULATED,
                Actor.system(),
                m,
                null,
                null,
                event);
    }

    /** A question as the journal holds it, with the given labels and codes (null: journaled before codes). */
    Question asked(List<String> options, List<String> codes, String answer) {
        List<EventEnvelope> journal = new ArrayList<>(f.journal);
        QuestionId id = new QuestionId("Q-99");
        journal.add(envelope(new DeskEvent.QuestionAsked(
                id,
                m,
                QuestionKind.OPEN,
                null,
                "s",
                "c",
                options,
                null,
                Criticality.MEDIUM,
                false,
                t,
                null,
                null,
                codes)));
        if (answer != null) journal.add(envelope(new DeskEvent.QuestionAnswered(id, answer)));
        return Company.replay(journal).desk().find(id).orElseThrow();
    }

    @Test
    void anAnswerJournaledBeforeCodesIsReadByItsLabel() {
        Question q = asked(List.of("Go on without it", "Drop the ticket"), null, "Drop the ticket");

        assertThat(q.offers(OptionCode.GO_ON_WITHOUT)).isTrue();
        assertThat(q.answered(OptionCode.DROP_TICKET)).isTrue();
        assertThat(q.answered(OptionCode.GO_ON_WITHOUT)).isFalse();
    }

    @Test
    void aRecordedCodeHoldsWhateverTheLabelSays() {
        // As if a later version reworded the option: the journal keeps the label shown then, and its code.
        Question q = asked(List.of("Try again", "Leave it"), List.of("RETRY", "LEAVE_IT"), "Try again");

        assertThat(q.answered(OptionCode.RETRY)).isTrue();
        assertThat(q.offers(OptionCode.RETRY)).isTrue();
    }

    @Test
    void anAgentsOptionIsNeverOneOfFloor42sWhateverItsWords() {
        f.record(f.company.tickets().assign(f.director(), t, CHRIS));
        f.record(f.company.missions().submitPlan(f.agent(GEOFFREY), m));
        f.record(f.company.missions().approvePlan(f.director(), m));
        QuestionId id = ((DeskEvent.QuestionAsked) f.record(f.company
                                .desk()
                                .ask(
                                        f.agent(CHRIS),
                                        m,
                                        "Which one?",
                                        "",
                                        List.of("Retry", "Leave it"),
                                        null,
                                        Criticality.MEDIUM,
                                        false,
                                        t))
                        .getFirst()
                        .event())
                .question();
        f.record(f.company.desk().answer(f.director(), id, "Retry"));

        Question q = f.company.desk().find(id).orElseThrow();
        assertThat(q.offers(OptionCode.RETRY)).isFalse();
        assertThat(q.answerCode()).isNull();
        assertThat(Company.replay(f.journal).desk().find(id).orElseThrow().answerCode())
                .isNull();
    }

    @Test
    void floor42sOwnQuestionCarriesItsCodesAndTakesACodeAsAnAnswer() {
        QuestionId id = ((DeskEvent.QuestionAsked) f.record(f.company
                                .desk()
                                .ask(
                                        f.system(),
                                        m,
                                        "Retry?",
                                        "",
                                        List.of(OptionCode.RETRY.label(), OptionCode.LEAVE_IT.label()),
                                        null,
                                        Criticality.MEDIUM,
                                        false,
                                        t))
                        .getFirst()
                        .event())
                .question();
        assertThat(((DeskEvent.QuestionAsked) f.journal.getLast().event()).optionCodes())
                .containsExactly("RETRY", "LEAVE_IT");

        f.record(f.company.desk().answer(f.director(), id, "retry"));

        Question q = Company.replay(f.journal).desk().find(id).orElseThrow();
        assertThat(q.answer()).as("the label is what the journal shows").isEqualTo("Retry");
        assertThat(q.answered(OptionCode.RETRY)).isTrue();
    }

    @Test
    void aPlanningTicketKeepsItsKindWhenItsTitleIsRewritten() {
        TicketId planning = ((TicketEvent.TicketCreated) f.record(f.company
                                .tickets()
                                .create(
                                        f.system(),
                                        m,
                                        "Plan: secure the APIs",
                                        "",
                                        Priority.NORMAL,
                                        List.of(),
                                        List.of(),
                                        TicketKind.PLANNING))
                        .getFirst()
                        .event())
                .ticket();

        f.record(f.company
                .tickets()
                .edit(f.director(), planning, "Secure the APIs, planned by Geoffrey", "", List.of()));

        assertThat(f.company.tickets().find(planning).orElseThrow().kind()).isEqualTo(TicketKind.PLANNING);
        assertThat(Company.replay(f.journal)
                        .tickets()
                        .find(planning)
                        .orElseThrow()
                        .kind())
                .isEqualTo(TicketKind.PLANNING);
        assertThat(f.company.tickets().find(t).orElseThrow().kind()).isEqualTo(TicketKind.WORK);
    }

    @Test
    void aTicketJournaledBeforeKindsIsReadFromItsFirstTitle() {
        List<EventEnvelope> journal = new ArrayList<>(f.journal);
        journal.add(envelope(new TicketEvent.TicketCreated(
                new TicketId("T-50"), m, "Plan: inventory", "", Priority.NORMAL, List.of(), List.of(), null)));
        journal.add(envelope(new TicketEvent.TicketCreated(
                new TicketId("T-51"), m, "Deliver: inventory", "", Priority.NORMAL, List.of(), List.of(), null)));

        Company c = Company.replay(journal);

        assertThat(c.tickets().find(new TicketId("T-50")).orElseThrow().kind()).isEqualTo(TicketKind.PLANNING);
        assertThat(c.tickets().find(new TicketId("T-51")).orElseThrow().kind()).isEqualTo(TicketKind.WORK);
    }
}
