package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.GEOFFREY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.EventEnvelope;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Reinforcing the team: the planner advises from facts, the director decides yes or no. */
@ExtendWith(GoldenFixtureExtension.class)
class StaffingTest {

    final Fixture f = new Fixture();
    final MissionId m = f.runningMission();

    @Test
    void thePlannerReviewsOnItsOwnTicketAndTheDirectorIsAskedYesOrNo() {
        f.record(f.company.staff().requestStaffingReview(f.director(), m, GEOFFREY));
        StaffingReview review = f.company.staff().staffingReview(m).orElseThrow();
        Ticket ticket = f.company.tickets().find(review.ticket()).orElseThrow();
        assertThat(ticket.assignee()).isEqualTo(GEOFFREY);
        assertThat(ticket.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(f.company.staff().isStaffingTicket(review.ticket())).isTrue();
        assertThatThrownBy(() -> f.company.staff().requestStaffingReview(f.director(), m, GEOFFREY))
                .hasMessageContaining("already reviewing");

        List<EventEnvelope> events = f.record(f.company
                .staff()
                .adviseStaffing(
                        f.agent(GEOFFREY),
                        m,
                        "Four tickets are independent; two more developers halve the wait.\nVERDICT: HIRE",
                        true,
                        List.of(
                                new StaffingReview.Hire("Ken", "Backend developer"),
                                new StaffingReview.Hire("Ida", "UI developer")),
                        3,
                        "Estimated by Floor42: ..."));

        assertThat(events).extracting(EventEnvelope::type).containsExactly("StaffingAdvised", "QuestionAsked");
        Company replayed = Company.replay(f.journal);
        StaffingReview advised = replayed.staff().staffingReview(m).orElseThrow();
        assertThat(advised.hires()).extracting(StaffingReview.Hire::name).containsExactly("Ken", "Ida");
        assertThat(advised.parallel()).isEqualTo(3);
        Question q = replayed.desk().find(advised.question()).orElseThrow();
        assertThat(q.kind()).isEqualTo(QuestionKind.STAFFING);
        assertThat(q.blocking()).isFalse();
        assertThat(q.options()).containsExactly(StaffingReview.HIRE, StaffingReview.NOT_NOW);
        assertThat(replayed.tickets().find(advised.ticket()).orElseThrow().status())
                .isEqualTo(TicketStatus.DONE);
    }

    @Test
    void onlyTheDirectorAsksAndOnlyThePlannerAdvises() {
        assertThatThrownBy(() -> f.company.staff().requestStaffingReview(f.agent(CHRIS), m, GEOFFREY))
                .isInstanceOf(DomainException.class);
        f.record(f.company.staff().requestStaffingReview(f.director(), m, GEOFFREY));
        assertThatThrownBy(() ->
                        f.company.staff().adviseStaffing(f.agent(CHRIS), m, "hire me", true, List.of(), null, null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void noOneToHireAsksNothing() {
        f.record(f.company.staff().requestStaffingReview(f.director(), m, GEOFFREY));

        List<EventEnvelope> events = f.record(f.company
                .staff()
                .adviseStaffing(f.agent(GEOFFREY), m, "One ticket left: no one to add.", false, List.of(), null, null));

        assertThat(events).extracting(EventEnvelope::type).containsExactly("StaffingAdvised");
    }
}
