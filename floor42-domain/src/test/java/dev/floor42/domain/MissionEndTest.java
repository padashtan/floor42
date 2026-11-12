package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.DARIUS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The checks the end-of-mission report asks before offering a next step (ADR-0039): the same refusals as the commands
 * themselves, and nothing recorded.
 */
@ExtendWith(GoldenFixtureExtension.class)
class MissionEndTest {

    final Fixture f = new Fixture();

    private TicketId done(MissionId m) {
        TicketId t = f.firstTicket(m);
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "report"));
        f.record(f.company.tickets().review(f.agent(DARIUS), t, ReviewVerdict.VALIDATED, "ok"));
        return t;
    }

    @Test
    void closingIsRefusedWithTheCommandsOwnReasonWhileATicketIsOpen() {
        MissionId m = f.runningMission();
        long version = f.company.version();

        assertThatThrownBy(() -> f.company.missions().checkComplete(f.director(), m))
                .extracting("code")
                .isEqualTo("MISSION_NOT_FINISHED");
        assertThatThrownBy(() -> f.company.missions().checkComplete(f.agent(CHRIS), m))
                .extracting("code")
                .isEqualTo("FORBIDDEN");

        done(m);
        assertThatCode(() -> f.company.missions().checkComplete(f.director(), m))
                .doesNotThrowAnyException();
        assertThat(f.company.missions().find(m).orElseThrow().status()).isEqualTo(MissionStatus.IN_PROGRESS);
        assertThat(f.company.version()).isGreaterThan(version);
    }

    @Test
    void checkingRecordsNothing() {
        MissionId m = f.runningMission();
        TicketId t = done(m);
        int before = f.journal.size();
        long version = f.company.version();

        f.company.missions().checkComplete(f.director(), m);
        assertThat(f.company.workshop().checkDirective(f.director(), t)).isEqualTo(t);
        assertThat(f.company.missions().refusalToComplete(f.director(), m)).isEmpty();
        assertThat(f.company.workshop().refusalToDirect(f.director(), t)).isEmpty();

        assertThat(f.journal).hasSize(before);
        assertThat(f.company.version()).isEqualTo(version);
        assertThat(f.company.tickets().find(t).orElseThrow().status()).isEqualTo(TicketStatus.DONE);
    }

    @Test
    void aClosedMissionCannotBeReopenedNorClosedAgain() {
        MissionId m = f.runningMission();
        TicketId t = done(m);
        f.record(f.company.missions().complete(f.director(), m, "delivered"));

        assertThatThrownBy(() -> f.company.workshop().checkDirective(f.director(), t))
                .extracting("code")
                .isEqualTo("MISSION_NOT_ACTIVE");
        assertThatThrownBy(() -> f.company.workshop().giveDirective(f.director(), t, "again"))
                .extracting("code")
                .isEqualTo("MISSION_NOT_ACTIVE");
        assertThatThrownBy(() -> f.company.missions().checkComplete(f.director(), m))
                .extracting("code")
                .isEqualTo("INVALID_TRANSITION");

        assertThat(f.company.workshop().refusalToDirect(f.director(), t))
                .map(Refused::code)
                .contains("MISSION_NOT_ACTIVE");
        assertThat(f.company.missions().refusalToComplete(f.director(), m))
                .contains(new Refused("INVALID_TRANSITION", m + ": COMPLETED -> COMPLETED is not allowed"));
    }

    @Test
    void onlyTheDirectorGivesDirectivesAndADirectiveStillReopensADoneTicket() {
        MissionId m = f.runningMission();
        TicketId t = done(m);

        assertThatThrownBy(() -> f.company.workshop().checkDirective(f.agent(CHRIS), t))
                .extracting("code")
                .isEqualTo("DIRECTOR_ONLY");
        assertThatThrownBy(() -> f.company.workshop().giveDirective(f.director(), t, " "))
                .isInstanceOf(DomainException.class);

        f.record(f.company.workshop().giveDirective(f.director(), t, "render the PDFs too"));
        Ticket reopened = f.company.tickets().find(t).orElseThrow();
        assertThat(reopened.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(reopened.directives()).containsExactly("render the PDFs too");
        assertThat(f.company.workshop().refusalToDirect(f.agent(CHRIS), t))
                .map(Refused::code)
                .contains("DIRECTOR_ONLY");
    }
}
