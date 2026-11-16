package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.DEHREN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The director hires agents for a work role and dismisses them; both are journaled (ADR-0019). */
@ExtendWith(GoldenFixtureExtension.class)
class StaffTest {

    static final AgentId KEN = new AgentId("ken");

    final Fixture f = new Fixture();

    @Test
    void theDirectorHiresForARole() {
        f.record(f.company
                .staff()
                .hire(f.director(), KEN, "Ken", "Java developer", WorkRole.DEVELOPER, "claude-sonnet-5-5", "medium"));

        Agent ken = f.company.staff().find(KEN).orElseThrow();
        assertThat(ken.workRole()).isEqualTo(WorkRole.DEVELOPER);
        assertThat(Company.replay(f.journal).staff().find(KEN).orElseThrow().workRole())
                .isEqualTo(WorkRole.DEVELOPER);
        assertThatThrownBy(() -> f.company
                        .staff()
                        .hire(f.agent(CHRIS), new AgentId("x"), "X", "Dev", WorkRole.DEVELOPER, "m", null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void aDismissedAgentLeavesAndTakesNoWork() {
        MissionId m = f.mission();
        TicketId t = f.ticket(m, "Next");
        f.record(f.company.staff().dismiss(f.director(), new AgentId("dehren"), "not needed"));

        assertThat(f.company.staff().find(new AgentId("dehren")).orElseThrow().dismissed())
                .isTrue();
        assertThatThrownBy(() -> f.company.tickets().assign(f.director(), t, new AgentId("dehren")))
                .hasMessageContaining("left");
        assertThatThrownBy(() -> f.company.staff().dismiss(f.director(), new AgentId("dehren"), "again"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void anAgentAtWorkStaysUntilItsSessionEnds() {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);
        f.record(f.company.sessions().start(f.system(), "s1", t, CHRIS, "claude-sonnet-5-5", null));

        assertThat(f.company.queries().atWork(CHRIS)).isTrue();
        assertThatThrownBy(() -> f.company.staff().dismiss(f.director(), CHRIS, "too slow"))
                .extracting("code")
                .isEqualTo("AGENT_BUSY");

        f.record(f.company.sessions().end(f.system(), "s1", false, "report", 1, null, null, null));
        assertThat(f.company.queries().atWork(CHRIS)).isFalse();
        f.record(f.company.staff().dismiss(f.director(), CHRIS, "too slow"));
        assertThat(f.company.staff().find(CHRIS).orElseThrow().dismissed()).isTrue();
    }

    @Test
    void aTicketStartedByAnAgentWhoLeavesGoesBackToTheQueue() {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);

        // Started, but no session runs: Chris is not at work right now, so he may leave.
        f.record(f.company.staff().dismiss(f.director(), CHRIS, "not needed"));

        Ticket ticket = f.company.tickets().find(t).orElseThrow();
        assertThat(ticket.status()).isEqualTo(TicketStatus.TODO);
        assertThat(ticket.assignee()).isNull();
        Ticket replayed = Company.replay(f.journal).tickets().find(t).orElseThrow();
        assertThat(replayed.status()).isEqualTo(TicketStatus.TODO);
        assertThat(replayed.assignee()).isNull();
        f.record(f.company.tickets().assign(f.director(), t, DEHREN));
    }

    @Test
    void onlyATicketWhoseAssigneeLeftIsReleased() {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);

        assertThatThrownBy(() -> f.company.tickets().release(f.system(), t))
                .extracting("code")
                .isEqualTo("AGENT_PRESENT");
        assertThatThrownBy(() -> f.company.tickets().release(f.agent(CHRIS), t))
                .extracting("code")
                .isEqualTo("FORBIDDEN");
    }

    @Test
    void theDirectorStarsCoreMembersAndTheStarSurvivesReplay() {
        Fixture real = new Fixture(Origin.REAL);
        var marked = real.record(real.company.staff().markCore(real.director(), CHRIS, true));

        assertThat(marked).singleElement().satisfies(e -> {
            assertThat(e.event()).isEqualTo(new dev.floor42.domain.event.RosterEvent.AgentMarkedCore(CHRIS));
            assertThat(e.origin()).isEqualTo(Origin.REAL);
            assertThat(e.actor()).isEqualTo(Actor.director());
        });
        assertThat(real.company.staff().find(CHRIS).orElseThrow().core()).isTrue();
        assertThat(real.company.staff().find(DEHREN).orElseThrow().core()).isFalse();
        assertThat(Company.replay(real.journal)
                        .staff()
                        .find(CHRIS)
                        .orElseThrow()
                        .core())
                .isTrue();
        // Asking again records nothing; the star comes off with its own event.
        assertThat(real.company.staff().markCore(real.director(), CHRIS, true)).isEmpty();
        real.record(real.company.staff().markCore(real.director(), CHRIS, false));
        assertThat(Company.replay(real.journal)
                        .staff()
                        .find(CHRIS)
                        .orElseThrow()
                        .core())
                .isFalse();
        assertThat(real.journal.getLast().event())
                .isEqualTo(new dev.floor42.domain.event.RosterEvent.AgentUnmarkedCore(CHRIS));
    }

    @Test
    void onlyTheDirectorStarsAndOnlyForReal() {
        Fixture real = new Fixture(Origin.REAL);
        assertThatThrownBy(() -> real.company.staff().markCore(real.agent(CHRIS), CHRIS, true))
                .extracting("code")
                .isEqualTo("DIRECTOR_ONLY");
        assertThatThrownBy(() -> real.company.staff().markCore(real.system(), CHRIS, true))
                .extracting("code")
                .isEqualTo("DIRECTOR_ONLY");
        assertThatThrownBy(() -> f.company.staff().markCore(f.director(), CHRIS, true))
                .as("a simulated context")
                .extracting("code")
                .isEqualTo("INVALID_ARGUMENT");
        real.record(real.company.staff().dismiss(real.director(), DEHREN, "not needed"));
        assertThatThrownBy(() -> real.company.staff().markCore(real.director(), DEHREN, true))
                .extracting("code")
                .isEqualTo("AGENT_DISMISSED");
        assertThat(real.company.staff().find(CHRIS).orElseThrow().core()).isFalse();
    }
}
