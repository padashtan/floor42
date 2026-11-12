package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.DEHREN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.TicketEvent.ReviewItemsRecorded;
import dev.floor42.domain.event.TicketEvent.TicketReviewed;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A reservation cites a criterion of the ticket and says what to change; anything else is a note (ADR-0053). */
class ReviewCalibrationTest {

    final Fixture f = new Fixture();
    final MissionId m = f.runningMission();
    final TicketId t = f.firstTicket(m);

    {
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "report"));
    }

    /** Its fixtures are not golden-recorded: never left for the next class's golden to pick up. */
    @AfterEach
    void forgetFixtures() {
        Fixture.takeMade();
    }

    Ticket.Review last(Company c) {
        return c.tickets().find(t).orElseThrow().reviews().getLast();
    }

    @Test
    void anItemCitingNoKnownCriterionIsANote() {
        ReviewCalibration.Calibrated sorted = ReviewCalibration.classify(
                List.of("tests pass", "docs updated"),
                List.of(
                        ReviewClaim.reservation("C2", "README misses the flag", "document --dry-run"),
                        ReviewClaim.reservation(null, "naming is odd", "rename it"),
                        ReviewClaim.reservation("C9", "no such criterion", "do it"),
                        ReviewClaim.reservation("C1", "one test skipped", null),
                        ReviewClaim.note("could be faster")));
        assertThat(sorted.reservations())
                .containsExactly(
                        new ReviewReservation(2, "docs updated", "README misses the flag", "document --dry-run"));
        assertThat(sorted.notes())
                .extracting(ReviewNote::why)
                .containsExactly(
                        ReviewNote.Why.NO_CRITERION,
                        ReviewNote.Why.UNKNOWN_CRITERION,
                        ReviewNote.Why.NO_CHANGE,
                        ReviewNote.Why.NOTE);
        assertThat(sorted.notes().getFirst().text()).isEqualTo("naming is odd | CHANGE: rename it");
    }

    @Test
    void reservationsThatAreAllNotesKeepTheDeclaredVerdict() {
        List<EventEnvelope> events = f.record(f.company
                .tickets()
                .review(
                        f.agent(DEHREN),
                        t,
                        ReviewVerdict.VALIDATED_WITH_RESERVATIONS,
                        "fine overall",
                        List.of(ReviewClaim.reservation(null, "style", "tidy"), ReviewClaim.note("nice"))));

        // Floor42 never rewrites the reviewer's verdict: the notes are only classified (ADR-0053).
        assertThat(((TicketReviewed) events.getFirst().event()).verdict())
                .isEqualTo(ReviewVerdict.VALIDATED_WITH_RESERVATIONS);
        EventEnvelope items = events.get(1);
        assertThat(items.event()).isInstanceOf(ReviewItemsRecorded.class);
        assertThat(items.actor()).isEqualTo(Actor.agent(DEHREN));
        assertThat(items.origin()).isEqualTo(f.origin);
        ReviewItemsRecorded e = (ReviewItemsRecorded) items.event();
        assertThat(e.declared()).isEqualTo(ReviewVerdict.VALIDATED_WITH_RESERVATIONS);
        assertThat(e.reservations()).isEmpty();
        assertThat(e.notes()).hasSize(2);

        for (Company c : List.of(f.company, Company.replay(f.journal))) {
            Ticket.Review r = last(c);
            assertThat(r.verdict()).isEqualTo(ReviewVerdict.VALIDATED_WITH_RESERVATIONS);
            assertThat(r.reservationsStand())
                    .as("only notes: no reservation stands")
                    .isFalse();
            assertThat(r.calibrated()).isTrue();
            assertThat(r.items().declared()).isEqualTo(ReviewVerdict.VALIDATED_WITH_RESERVATIONS);
            assertThat(r.items().notes())
                    .extracting(ReviewNote::why)
                    .containsExactly(ReviewNote.Why.NO_CRITERION, ReviewNote.Why.NOTE);
            assertThat(c.tickets().find(t).orElseThrow().status()).isEqualTo(TicketStatus.DONE);
        }
    }

    @Test
    void aReservationOnACriterionIsKeptWithTheCriterionAsItStood() {
        f.record(f.company
                .tickets()
                .review(
                        f.agent(DEHREN),
                        t,
                        ReviewVerdict.VALIDATED,
                        "mostly there",
                        List.of(ReviewClaim.reservation("C1", "edge case untested", "add a test for it"))));
        Ticket.Review r = last(Company.replay(f.journal));
        assertThat(r.verdict()).as("declared, untouched").isEqualTo(ReviewVerdict.VALIDATED);
        assertThat(r.reservationsStand()).as("a kept reservation counts").isTrue();
        assertThat(r.items().reservations())
                .containsExactly(new ReviewReservation(1, "criterion", "edge case untested", "add a test for it"));
    }

    @Test
    void reservationsDeclaredButUnreadableKeepTheVerdictAndSaySo() {
        f.record(f.company
                .tickets()
                .review(
                        f.agent(DEHREN),
                        t,
                        ReviewVerdict.VALIDATED_WITH_RESERVATIONS,
                        "Reservations: the docs are thin",
                        List.of()));
        Ticket.Review r = last(Company.replay(f.journal));
        assertThat(r.verdict()).isEqualTo(ReviewVerdict.VALIDATED_WITH_RESERVATIONS);
        assertThat(r.items().notes()).extracting(ReviewNote::why).containsExactly(ReviewNote.Why.UNPARSED);
        // A validation with nothing to read is just a validation.
        assertThat(ReviewCalibration.sort(List.of("c"), List.of(), ReviewVerdict.VALIDATED)
                        .notes())
                .isEmpty();
    }

    @Test
    void aPartialReadKeepsTheDeclaredVerdictAndTheKeptReservation() {
        f.record(f.company
                .tickets()
                .review(
                        f.agent(DEHREN),
                        t,
                        ReviewVerdict.VALIDATED_WITH_RESERVATIONS,
                        "one keyed, one free-form",
                        List.of(ReviewClaim.reservation("C1", "edge case untested", "add a test"))));
        Ticket.Review r = last(Company.replay(f.journal));
        assertThat(r.verdict()).isEqualTo(ReviewVerdict.VALIDATED_WITH_RESERVATIONS);
        assertThat(r.items().reservations()).hasSize(1);
        assertThat(r.reservationsStand()).isTrue();
    }

    @Test
    void anUnverifiableReviewKeepsItsVerdict() {
        f.record(f.company
                .tickets()
                .review(
                        f.agent(DEHREN),
                        t,
                        ReviewVerdict.UNVERIFIABLE,
                        "cannot run it",
                        List.of(ReviewClaim.reservation("C1", "not run", "run it"))));
        Ticket.Review r = last(Company.replay(f.journal));
        assertThat(r.verdict()).isEqualTo(ReviewVerdict.UNVERIFIABLE);
        assertThat(r.reservationsStand()).as("not a validation").isFalse();
    }

    @Test
    void aRejectionKeepsItsVerdict() {
        f.record(f.company
                .tickets()
                .review(f.agent(DEHREN), t, ReviewVerdict.REJECTED, "broken", List.of(ReviewClaim.note("x"))));
        assertThat(last(f.company).verdict()).isEqualTo(ReviewVerdict.REJECTED);
        assertThat(f.company.tickets().find(t).orElseThrow().status()).isEqualTo(TicketStatus.IN_PROGRESS);
    }

    @Test
    void anOldReviewWithReservationsKeepsItsMeaning() {
        f.record(f.company
                .tickets()
                .review(f.agent(DEHREN), t, ReviewVerdict.VALIDATED_WITH_RESERVATIONS, "ok, docs missing"));
        Ticket.Review r = last(Company.replay(f.journal));
        assertThat(r.verdict()).isEqualTo(ReviewVerdict.VALIDATED_WITH_RESERVATIONS);
        assertThat(r.calibrated()).isFalse();
        assertThat(r.items()).isNull();
        assertThat(r.reservationsStand()).as("an old reservation stays one").isTrue();
    }

    @Test
    void itemsThatFollowNoReviewStopTheReplay() {
        List<EventEnvelope> journal = new ArrayList<>(f.journal);
        EventEnvelope any = journal.getLast();
        journal.add(new EventEnvelope(
                any.eventId() + "-x",
                any.occurredAt(),
                any.origin(),
                any.actor(),
                any.mission(),
                null,
                null,
                new ReviewItemsRecorded(t, DEHREN, ReviewVerdict.VALIDATED, List.of(), List.of())));
        assertThatThrownBy(() -> Company.replay(journal)).hasMessageContaining("has no review by");
    }
}
