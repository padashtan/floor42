package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.DeskEvent.QuestionAsked;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Ticket and question numbers are never given twice: a replay stops on a duplicate, a reserved number is skipped. */
class IdReuseTest {

    final Fixture f = new Fixture(Origin.REAL);
    final MissionId m = f.runningMission();

    @AfterEach
    void forgetFixtures() {
        Fixture.takeMade();
    }

    QuestionId ask() {
        CommandContext ctx =
                new CommandContext(Actor.agent(CHRIS), f.origin, Instant.parse("2026-10-20T10:00:00Z"), null);
        f.record(f.company
                .desk()
                .ask(ctx, m, "Which database?", "ctx", List.of(), null, Criticality.MEDIUM, true, null));
        return f.company.desk().open().getLast().id();
    }

    @Test
    void twoCreationsOfOneTicketStopTheReplay() {
        f.firstTicket(m);
        List<EventEnvelope> twice = new ArrayList<>(f.journal);
        twice.add(f.journal.stream()
                .filter(e -> e.event() instanceof TicketCreated)
                .findFirst()
                .orElseThrow());
        assertThatThrownBy(() -> Company.replay(twice)).hasMessageContaining("T-1 is created twice");
    }

    @Test
    void twoAsksOfOneQuestionStopTheReplay() {
        ask();
        List<EventEnvelope> twice = new ArrayList<>(f.journal);
        twice.add(f.journal.stream()
                .filter(e -> e.event() instanceof QuestionAsked)
                .findFirst()
                .orElseThrow());
        assertThatThrownBy(() -> Company.replay(twice)).hasMessageContaining("Q-1 is created twice");
    }

    @Test
    void aReservedTicketNumberIsNeverGivenToANewTicket() {
        TicketId first = f.firstTicket(m);
        Company c = Company.replay(f.journal);
        c.tickets().reserve(new TicketId("T-" + (Integer.parseInt(first.value().substring(2)) + 1)));
        TicketId next = ((TicketCreated) c.tickets()
                        .create(f.agent(CHRIS), m, "Next", "", Priority.NORMAL, List.of(), List.of("c"))
                        .getFirst()
                        .event())
                .ticket();
        assertThat(next.value())
                .isEqualTo("T-" + (Integer.parseInt(first.value().substring(2)) + 2));
    }

    @Test
    void aReservedQuestionNumberIsNeverGivenToANewQuestion() {
        ask();
        f.company.desk().reserve(new QuestionId("Q-5"));
        assertThat(ask().value()).isEqualTo("Q-6");
    }

    @Test
    void twoCallsOfOneMeetingStopTheReplay() {
        f.record(f.company.meetings().call(f.director(), m, List.of(CHRIS), "Scope"));
        List<EventEnvelope> twice = new ArrayList<>(f.journal);
        twice.add(f.journal.stream()
                .filter(e -> e.event() instanceof dev.floor42.domain.event.MeetingEvent.MeetingCalled)
                .findFirst()
                .orElseThrow());
        assertThatThrownBy(() -> Company.replay(twice)).hasMessageContaining("MT-1 is created twice");
    }

    @Test
    void aReservedMeetingNumberIsNeverGivenToANewMeeting() {
        f.company.meetings().reserve("MT-5");
        var called = f.company.meetings().call(f.director(), m, List.of(CHRIS), "Scope");
        assertThat(((dev.floor42.domain.event.MeetingEvent.MeetingCalled)
                                called.getFirst().event())
                        .meeting())
                .isEqualTo("MT-6");
    }
}
