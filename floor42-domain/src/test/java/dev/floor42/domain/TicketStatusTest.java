package dev.floor42.domain;

import static dev.floor42.domain.TicketStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Every move between ticket statuses, allowed or not. Release and reopen are the two named moves the events apply. */
class TicketStatusTest {

    private static final Map<TicketStatus, Set<TicketStatus>> COMMANDS = Map.of(
            PROPOSED, Set.of(TODO, CANCELLED),
            TODO, Set.of(IN_PROGRESS, CANCELLED),
            IN_PROGRESS, Set.of(IN_REVIEW, CANCELLED),
            IN_REVIEW, Set.of(DONE, IN_PROGRESS, CANCELLED),
            DONE, Set.of(),
            CANCELLED, Set.of());

    @Test
    void commandMovesAreExactlyTheseAndEveryOtherIsForbidden() {
        for (TicketStatus from : values()) {
            for (TicketStatus to : values()) {
                assertThat(from.canMoveTo(to))
                        .as(from + " -> " + to)
                        .isEqualTo(COMMANDS.get(from).contains(to));
            }
        }
    }

    @Test
    void releaseIsInProgressToTodoOnly() {
        for (TicketStatus s : values()) {
            assertThat(s.canBeReleased()).as("release from " + s).isEqualTo(s == IN_PROGRESS);
        }
    }

    @Test
    void reopenIsFromReviewOrDoneOnly() {
        for (TicketStatus s : values()) {
            assertThat(s.canBeReopened()).as("reopen from " + s).isEqualTo(s == IN_REVIEW || s == DONE);
        }
    }
}
