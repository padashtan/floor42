package dev.floor42.server.app;

import static dev.floor42.server.app.SimulatedCrew.CHRIS;
import static dev.floor42.server.app.SimulatedCrew.DEHREN;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Mission;
import dev.floor42.domain.Origin;
import dev.floor42.domain.ReviewVerdict;
import dev.floor42.server.app.StalledMissions.Stalled;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * The briefs Floor42 writes about a simulated mission (ADR-0045) carry no amount at all: a replayed recording reported a
 * cost, nothing was paid. The retrospective's brief and the staffing review's facts both say "simulated" instead.
 */
@QuarkusTest
class SimulatedBriefsTest {

    @Inject
    OfficeService office;

    @Inject
    SimulatedCrew crew;

    @Inject
    SampleRepository sample;

    @Test
    void aSimulatedMissionsBriefsHoldNoUsdFigure() {
        Stalled s = StalledMissions.of(office, crew, sample.prepare().toString(), Origin.SIMULATED);
        var system = office.context(Actor.system(), Origin.SIMULATED);
        office.execute(c -> c.sessions().start(system, "rec-brief", s.ticket(), CHRIS, "claude-haiku-5-5", "2.1.304"));
        office.execute(c -> c.sessions().end(system, "rec-brief", false, "completed", 3, 1000L, 100L, 0.04));
        office.execute(c -> c.tickets().submitForReview(crew.agent(CHRIS), s.ticket(), "notes"));
        office.execute(c -> c.tickets().review(crew.agent(DEHREN), s.ticket(), ReviewVerdict.VALIDATED, "fine"));
        try {
            String retro = office.query(c -> RetroBriefs.facts(c, mission(c, s)));
            assertThat(retro).doesNotContain("USD").contains("chris: 1 session(s), 0 failed, simulated");

            String staffing =
                    office.query(c -> Staffing.facts(c, s.mission(), 1).text());
            assertThat(staffing)
                    .contains("Per done ticket")
                    .doesNotContain("USD")
                    .contains("simulated");
        } finally {
            office.execute(c -> c.missions().cancel(crew.director(), s.mission(), "test over"));
        }
    }

    private static Mission mission(dev.floor42.domain.Company c, Stalled s) {
        return c.missions().find(s.mission()).orElseThrow();
    }
}
