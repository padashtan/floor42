package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.GALEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.MissionEvent.MissionCreated;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The guided first mission (ADR-0045): a rehearsal is simulated, works on Floor42's sample repository, and keeps the
 * cap rule of a mission on a repository. The demo, simulated on no repository, keeps approving without a cap.
 */
class RehearsalTest {

    static final String SAMPLE = "/data/tutorial/sample-repository";

    final Fixture f = new Fixture();

    /** Not part of the golden master: its fixtures are dropped, never handed to the next golden class. */
    @AfterEach
    void drop() {
        Fixture.takeMade();
    }

    MissionId rehearsal() {
        MissionId m = ((MissionCreated)
                        f.record(f.company.missions().createRehearsal(f.director(), "First", "Notes", SAMPLE))
                                .getFirst()
                                .event())
                .mission();
        f.record(f.company.tickets().assign(f.director(), f.ticket(m, "Inspect"), CHRIS));
        f.record(f.company.missions().submitPlan(f.agent(GALEN), m));
        return m;
    }

    @Test
    void aRehearsalIsSimulatedOnTheSample() {
        Mission m = f.company.missions().find(rehearsal()).orElseThrow();
        assertThat(m.origin()).isEqualTo(Origin.SIMULATED);
        assertThat(m.workspace()).isEqualTo(SAMPLE);
    }

    @Test
    void aRehearsalIsNeverReal() {
        Fixture real = new Fixture(Origin.REAL);
        assertThatThrownBy(() -> real.company.missions().createRehearsal(real.director(), "First", "Notes", SAMPLE))
                .extracting("code")
                .isEqualTo("ORIGIN_MISMATCH");
        assertThatThrownBy(() -> f.company.missions().createRehearsal(f.director(), "First", "Notes", " "))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> f.company.missions().createRehearsal(f.agent(GALEN), "First", "Notes", SAMPLE))
                .extracting("code")
                .isEqualTo("DIRECTOR_ONLY");
    }

    @Test
    void aRehearsalIsApprovedOnlyWithItsCapDecided() {
        MissionId m = rehearsal();
        assertThatThrownBy(() -> f.company.missions().approvePlan(f.director(), m))
                .extracting("code")
                .isEqualTo("CAP_NEEDED");

        f.record(f.company.verification().setBudget(f.director(), m, 1.0));
        f.record(f.company.missions().approvePlan(f.director(), m));
        assertThat(f.company.missions().find(m).orElseThrow().status()).isEqualTo(MissionStatus.IN_PROGRESS);
    }

    @Test
    void aRehearsalSpendsNothingWhateverItsReplayReported() {
        MissionId m = rehearsal();
        f.record(f.company.verification().setBudget(f.director(), m, 0.001));
        f.record(f.company.missions().approvePlan(f.director(), m));
        TicketId t = f.company.tickets().of(m).getFirst().id();
        f.record(f.company.tickets().start(f.agent(CHRIS), t));
        f.record(f.company.sessions().start(f.system(), "rec-1", t, CHRIS, "claude-haiku-5-5", "2.1.304"));
        f.record(f.company.sessions().end(f.system(), "rec-1", false, "completed", 3, 1000L, 100L, 0.0024));

        assertThat(f.company.verification().spentUsdEstimated(m)).isZero();
        assertThat(f.company.verification().budgetReached(m))
                .as("a recording never exhausts a cap")
                .isFalse();
    }

    @Test
    void theDemoOnNoRepositoryStillNeedsNoCap() {
        MissionId m = f.mission();
        f.record(f.company.tickets().assign(f.director(), f.ticket(m, "Inspect"), CHRIS));
        f.record(f.company.missions().submitPlan(f.agent(GALEN), m));
        f.record(f.company.missions().approvePlan(f.director(), m));
        assertThat(f.company.missions().find(m).orElseThrow().status()).isEqualTo(MissionStatus.IN_PROGRESS);
    }
}
