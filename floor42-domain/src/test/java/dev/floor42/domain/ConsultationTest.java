package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.DEHREN;
import static dev.floor42.domain.Fixture.GEOFFREY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** A reviewer in doubt asks for help once per review, then concludes with the answer in hand (ADR-0018). */
@ExtendWith(GoldenFixtureExtension.class)
class ConsultationTest {

    final Fixture f = new Fixture();
    final MissionId m = f.runningMission();
    final TicketId t = f.firstTicket(m);

    {
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "report"));
    }

    Ticket ticket() {
        return f.company.tickets().find(t).orElseThrow();
    }

    @Test
    void theReviewerConsultsOncePerReview() {
        f.record(f.company
                .tickets()
                .requestConsultation(
                        f.agent(DEHREN),
                        t,
                        ConsultationKind.CONSULT,
                        "security",
                        "Is a static token acceptable here?"));
        assertThat(ticket().consultationThisRound())
                .hasValueSatisfying(c -> assertThat(c.pending()).isTrue());
        assertThatThrownBy(() -> f.company
                        .tickets()
                        .requestConsultation(f.agent(DEHREN), t, ConsultationKind.RESEARCH, null, "again"))
                .hasMessageContaining("One consultation per review");

        f.record(f.company.tickets().answerConsultation(f.agent(GEOFFREY), t, "No: rotate it."));
        Ticket.Consultation c = ticket().consultations().getFirst();
        assertThat(c.answer()).isEqualTo("No: rotate it.");
        assertThat(c.answeredBy()).isEqualTo(GEOFFREY);
        assertThat(c.round()).isZero();

        // A rejection and a new delivery open a new review round: help may be asked again.
        f.record(f.company.tickets().review(f.agent(DEHREN), t, ReviewVerdict.REJECTED, "rotate the token"));
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "report 2"));
        f.record(f.company
                .tickets()
                .requestConsultation(f.agent(DEHREN), t, ConsultationKind.RESEARCH, null, "token rotation"));
        f.record(f.company.tickets().answerConsultation(f.system(), t, "Research not run: the table denies web tools"));
        assertThat(ticket().consultations()).hasSize(2);
        assertThat(ticket().consultations().getLast().answeredBy()).isNull();
        assertThat(Company.replay(f.journal).tickets().find(t).orElseThrow().consultations())
                .isEqualTo(ticket().consultations());
    }

    @Test
    void theProducerNeitherAsksNorAnswers() {
        assertThatThrownBy(() -> f.company
                        .tickets()
                        .requestConsultation(f.agent(CHRIS), t, ConsultationKind.CONSULT, "security", "am I right?"))
                .hasMessageContaining("cannot review");
        f.record(f.company
                .tickets()
                .requestConsultation(f.agent(DEHREN), t, ConsultationKind.CONSULT, "developer", "?"));
        assertThatThrownBy(() -> f.company.tickets().answerConsultation(f.agent(CHRIS), t, "trust me"))
                .hasMessageContaining("producer");
        assertThatThrownBy(() -> f.company.tickets().answerConsultation(f.director(), t, "director answers"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void aConsultationNeedsAQuestionAndARole() {
        assertThatThrownBy(() ->
                        f.company.tickets().requestConsultation(f.agent(DEHREN), t, ConsultationKind.CONSULT, " ", "q"))
                .hasMessageContaining("Whom");
        assertThatThrownBy(() -> f.company.tickets().answerConsultation(f.system(), t, "nothing asked"))
                .hasMessageContaining("No consultation pending");
    }
}
