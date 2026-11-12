package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.ReviewNote;
import dev.floor42.domain.ReviewVerdict;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.TicketEvent.ReviewItemsRecorded;
import dev.floor42.server.journal.Journal;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * A real review through the reviewer's session (ADR-0053): Floor42 keeps as a reservation only what cites a criterion
 * of the ticket and says what to change, records the rest as notes, journals both as the reviewer's, and serves them.
 */
@QuarkusTest
class ReviewCalibrationApiTest extends DispatchFixture {

    @Inject
    Journal journal;

    @Test
    void theReviewersItemsAreSortedIntoReservationsAndNotes() throws Exception {
        MissionId m = mission("Write notes [fake:reserved]");
        approve(m);
        Supplier<Ticket> reviewed = () -> office.query(c -> c.tickets().of(m).stream()
                .filter(t -> !t.reviews().isEmpty())
                .findFirst()
                .orElse(null));
        await("a review is recorded", Duration.ofSeconds(90), () -> {
            answerAll(m, null);
            return reviewed.get() != null;
        });
        quiet();
        Ticket t = reviewed.get();
        Ticket.Review r = t.reviews().getFirst();
        assertThat(r.calibrated()).as("the review's items were sorted").isTrue();
        assertThat(r.verdict()).isEqualTo(ReviewVerdict.VALIDATED_WITH_RESERVATIONS);
        assertThat(r.items().reservations()).singleElement().satisfies(x -> {
            assertThat(x.criterion()).isEqualTo(1);
            assertThat(x.criterionText()).isEqualTo(t.acceptanceCriteria().getFirst());
            assertThat(x.change()).isEqualTo("add the install step");
        });
        assertThat(r.items().notes())
                .extracting(ReviewNote::why)
                .containsExactly(ReviewNote.Why.NO_CRITERION, ReviewNote.Why.NOTE);

        List<EventEnvelope> items = journal.readAll().stream()
                .filter(e ->
                        e.event() instanceof ReviewItemsRecorded x && x.ticket().equals(t.id()))
                .toList();
        assertThat(items).singleElement().satisfies(e -> {
            assertThat(e.origin()).isEqualTo(Origin.REAL);
            assertThat(e.actor()).isEqualTo(Actor.agent(r.reviewer()));
        });

        String review = "tickets.find { it.id == '" + t.id().value() + "' }.reviews[0].items";
        api().get("/api/missions/" + m.value())
                .then()
                .statusCode(200)
                .body(review + ".declared", Matchers.equalTo("VALIDATED_WITH_RESERVATIONS"))
                .body(review + ".reservations[0].criterion", Matchers.equalTo(1))
                .body(review + ".notes.why", Matchers.contains("NO_CRITERION", "NOTE"));
        // The report counts the review with reservations: one is kept (ADR-0053).
        api().get("/api/missions/" + m.value() + "/report")
                .then()
                .statusCode(200)
                .body("reviews.withReservations", Matchers.equalTo(1));
        cancel(m);
    }
}
