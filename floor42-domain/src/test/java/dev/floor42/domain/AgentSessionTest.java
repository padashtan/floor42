package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.DEHREN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.workflow.Workflow;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(GoldenFixtureExtension.class)
class AgentSessionTest {

    final Fixture f = new Fixture();

    @Test
    void aRealMissionNamesItsRepositoryAndASimulatedOneHasNone() {
        CommandContext real =
                new CommandContext(Actor.director(), Origin.REAL, Instant.parse("2026-10-20T08:00:00Z"), null);

        assertThatThrownBy(() -> f.company.missions().create(real, "t", "o", null, Workflow.BUILT_IN))
                .extracting("code")
                .isEqualTo("MISSING_WORKSPACE");
        assertThatThrownBy(() -> f.company.missions().create(f.director(), "t", "o", "C:/repo", Workflow.BUILT_IN))
                .extracting("code")
                .isEqualTo("SIMULATED_WORKSPACE");

        f.company.missions().create(real, "t", "o", "C:/repo", Workflow.BUILT_IN);
        assertThat(f.company.missions().all())
                .singleElement()
                .extracting(Mission::workspace)
                .isEqualTo("C:/repo");
    }

    @Test
    void theRuntimeRecordsASessionOnTheTicketAndItsProvidedAndEstimatedFigures() {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);

        f.record(f.company.sessions().start(f.system(), "s1", t, CHRIS, "claude-sonnet-5-5", "2.1.304"));
        assertThat(f.company.sessions().find("s1").orElseThrow().isOpen()).isTrue();

        f.record(f.company.sessions().end(f.system(), "s1", false, "report", 6, 12_000L, 800L, 0.04));

        AgentSession s = f.company.sessions().find("s1").orElseThrow();
        assertThat(s.isOpen()).isFalse();
        assertThat(s.inputTokensProvided()).isEqualTo(12_000L);
        assertThat(s.costUsdEstimated()).isEqualTo(0.04);
        assertThat(f.company.sessions().of(m)).containsExactly(s);
    }

    @Test
    void onlyTheRuntimeRecordsSessions() {
        MissionId m = f.runningMission();

        assertThatThrownBy(
                        () -> f.company.sessions().start(f.agent(CHRIS), "s1", f.firstTicket(m), CHRIS, "model", null))
                .extracting("code")
                .isEqualTo("FORBIDDEN");
    }

    @Test
    void aSessionNeedsAnApprovedPlanAndAnAgentOnTheTicket() {
        MissionId planning = f.mission();
        TicketId draft = f.ticket(planning, "Analyse");
        f.record(f.company.tickets().assign(f.director(), draft, CHRIS));
        assertThatThrownBy(() -> f.company.sessions().start(f.system(), "s0", draft, CHRIS, "model", null))
                .extracting("code")
                .isEqualTo("MISSION_NOT_ACTIVE");

        MissionId m = f.runningMission();
        assertThatThrownBy(() -> f.company.sessions().start(f.system(), "s1", f.firstTicket(m), DEHREN, "model", null))
                .extracting("code")
                .isEqualTo("NOT_ON_TICKET");
    }

    @Test
    void aReviewerInSessionIsShownReviewing() {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "report"));

        f.record(f.company.sessions().start(f.system(), "r1", t, DEHREN, "claude-haiku-5-5", null));

        assertThat(f.company.queries().statusOf(DEHREN)).isEqualTo(AgentStatus.REVIEWING);
    }

    @Test
    void aSessionEndsOnce() {
        MissionId m = f.runningMission();
        f.record(f.company.sessions().start(f.system(), "s1", f.firstTicket(m), CHRIS, "model", null));
        f.record(f.company.sessions().end(f.system(), "s1", true, "crashed", null, null, null, null));

        assertThatThrownBy(() -> f.company.sessions().end(f.system(), "s1", false, "again", null, null, null, null))
                .extracting("code")
                .isEqualTo("SESSION_CLOSED");
    }
}
