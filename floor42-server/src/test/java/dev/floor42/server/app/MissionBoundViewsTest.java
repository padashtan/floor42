package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.SpokenTurn;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.MissionEvent;
import dev.floor42.domain.event.TicketEvent;
import dev.floor42.domain.workflow.Workflow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The costs panel (mission totals, periods, rows per agent and per ticket) and the retrospective's facts read the
 * mission's one upper bound where the budget reads it (Verification): a meeting turn charged its budget with no session
 * of its own to count it is in each of them, apart from the estimate (ADR-0049).
 */
class MissionBoundViewsTest {

    static final AgentId CHRIS = new AgentId("chris");
    static final AgentId GEOFFREY = new AgentId("geoffrey");

    final Company company = new Company();
    Instant now = Instant.parse("2026-10-20T09:00:00Z");

    CommandContext ctx(Actor actor) {
        now = now.plusSeconds(1);
        return new CommandContext(actor, Origin.REAL, now, null);
    }

    MissionId missionInProgress() {
        for (AgentId id : List.of(GEOFFREY, CHRIS))
            company.staff()
                    .hire(
                            ctx(Actor.director()),
                            id,
                            id.value(),
                            "role",
                            "anthropic",
                            "claude-sonnet-5-5",
                            "medium",
                            List.of());
        MissionId m = ((MissionEvent.MissionCreated) company.missions()
                        .create(
                                ctx(Actor.director()),
                                "Write the report",
                                "A report",
                                "C:/work/report",
                                Workflow.BUILT_IN)
                        .getFirst()
                        .event())
                .mission();
        TicketId t = ((TicketEvent.TicketCreated) company.tickets()
                        .create(ctx(Actor.director()), m, "Deliver", "", Priority.NORMAL, List.of(), List.of("done"))
                        .getFirst()
                        .event())
                .ticket();
        company.tickets().assign(ctx(Actor.director()), t, CHRIS);
        company.missions().submitPlan(ctx(Actor.agent(GEOFFREY)), m);
        company.verification().setBudget(ctx(Actor.director()), m, 1.0);
        company.missions().approvePlan(ctx(Actor.director()), m);
        return m;
    }

    /** Nothing bounded: no bound, never 0, which would read as a real figure (the report says null too). */
    @Test
    void withNothingBoundedEveryViewLeavesTheBoundOut() {
        MissionId m = missionInProgress();
        Mission mission = company.missions().find(m).orElseThrow();

        assertThat(Costs.missionCost(company, mission).total().usdBound()).isNull();
        Costs.CostsOverview overview = Costs.overviewOf(company, java.time.ZoneId.of("UTC"), now);
        assertThat(overview.today().usdBound()).isNull();
        assertThat(overview.month().usdBound()).isNull();
    }

    @Test
    void aChargedTurnWithoutASessionIsInTheCostsTotalAndTheRetroFacts() {
        MissionId m = missionInProgress();
        company.meetings().call(ctx(Actor.director()), m, List.of(CHRIS), "The report");
        company.meetings().askForViews(ctx(Actor.director()), "MT-1", List.of(CHRIS), 1.0, null);
        // Interrupted by a restart before its session was journaled: charged what it was allowed (ADR-0049).
        company.meetings()
                .recordTurn(
                        ctx(Actor.system()), "MT-1", SpokenTurn.notHeard(1, CHRIS, null, "the daemon stopped", 0.4));
        Mission mission = company.missions().find(m).orElseThrow();
        assertThat(company.verification().spentUsdBound(m))
                .as("the budget counts it")
                .isEqualTo(0.4);

        assertThat(Costs.missionCost(company, mission).total().usdBound())
                .as("the costs panel shows what the budget counts")
                .isEqualTo(0.4);
        assertThat(RetroBriefs.facts(company, mission))
                .as("the retrospective is told the same bound")
                .contains("+ up to 0.40")
                .contains("for 1 session(s) with no reported cost");
    }

    /**
     * The same charged turn in the costs panel's other figures, as a bound apart from the estimate: the period of its
     * turn (not the one before), its speaker's row and the meeting ticket's row.
     */
    @Test
    void aChargedTurnIsInItsPeriodItsSpeakersRowAndItsTicketsRow() {
        MissionId m = missionInProgress();
        company.meetings().call(ctx(Actor.director()), m, List.of(CHRIS), "The report");
        TicketId turns = ((dev.floor42.domain.event.MeetingRoomEvent.MeetingViewsAsked) company.meetings()
                        .askForViews(ctx(Actor.director()), "MT-1", List.of(CHRIS), 1.0, null)
                        .getFirst()
                        .event())
                .ticket();
        company.meetings()
                .recordTurn(
                        ctx(Actor.system()), "MT-1", SpokenTurn.notHeard(1, CHRIS, null, "the daemon stopped", 0.4));
        Mission mission = company.missions().find(m).orElseThrow();

        var overview = Costs.overviewOf(company, java.time.ZoneOffset.UTC, now);
        assertThat(overview.today().usdBound()).as("the period of its turn").isEqualTo(0.4);
        assertThat(overview.today().usdEstimated()).as("never in the estimate").isZero();
        assertThat(overview.week().usdBound()).isEqualTo(0.4);
        assertThat(overview.month().usdBound()).isEqualTo(0.4);
        assertThat(Costs.overviewOf(company, java.time.ZoneOffset.UTC, now.plus(java.time.Duration.ofDays(2)))
                        .today()
                        .usdBound())
                .as("not a later day's")
                .isNull();

        var costs = Costs.costsOf(company, mission);
        assertThat(costs.agents())
                .filteredOn(l -> l.key().equals(CHRIS.value()))
                .singleElement()
                .satisfies(l -> assertThat(l.usdBound()).as("its speaker's row").isEqualTo(0.4));
        assertThat(costs.tickets())
                .filteredOn(l -> l.key().equals(turns.value()))
                .singleElement()
                .satisfies(l ->
                        assertThat(l.usdBound()).as("the meeting ticket's row").isEqualTo(0.4));
    }

    /** A meeting turn of the attendee charged {@code usd}, spoken at {@code at}, in a round of its own. */
    void chargedTurn(MeetingRound r, String session, double usd, String at) {
        company.meetings().askForViews(ctx(Actor.director()), "MT-1", List.of(CHRIS), 5.0, null);
        now = Instant.parse(at);
        company.meetings()
                .recordTurn(ctx(Actor.system()), "MT-1", SpokenTurn.notHeard(r.n, CHRIS, session, "stopped", usd));
    }

    record MeetingRound(int n) {}

    @Test
    void aChargedTurnIsInItsOwnDayAndNotTheDayBefore() {
        MissionId m = missionInProgress();
        company.meetings().call(ctx(Actor.director()), m, List.of(CHRIS), "The report");
        chargedTurn(new MeetingRound(1), null, 0.4, "2026-10-20T22:30:00Z");
        chargedTurn(new MeetingRound(2), null, 0.2, "2026-10-21T00:00:10Z");
        Instant read = Instant.parse("2026-10-21T10:00:00Z");

        assertThat(Costs.overviewOf(company, java.time.ZoneOffset.UTC, read)
                        .today()
                        .usdBound())
                .as("only the turn of the day, not the one of the evening before")
                .isEqualTo(0.2);
        assertThat(Costs.overviewOf(company, java.time.ZoneId.of("Europe/Paris"), read)
                        .today()
                        .usdBound())
                .as("the day is the zone's: 22:30Z is after midnight in Paris")
                .isCloseTo(0.6, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aChargedTurnNearABoundaryIsInTheRightWeekAndMonth() {
        MissionId m = missionInProgress();
        company.meetings().call(ctx(Actor.director()), m, List.of(CHRIS), "The report");
        chargedTurn(new MeetingRound(1), null, 0.4, "2026-09-30T23:59:50Z");
        chargedTurn(new MeetingRound(2), null, 0.8, "2026-10-01T00:00:10Z");
        chargedTurn(new MeetingRound(3), null, 0.1, "2026-10-18T23:59:50Z");
        chargedTurn(new MeetingRound(4), null, 0.2, "2026-10-19T00:00:10Z");
        var overview = Costs.overviewOf(company, java.time.ZoneOffset.UTC, Instant.parse("2026-10-21T10:00:00Z"));

        assertThat(overview.week().usdBound())
                .as("the week starts on Monday the 19th")
                .isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(overview.month().usdBound())
                .as("the month starts on the 1st: not the turn of the 30th")
                .isCloseTo(1.1, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aChargedTurnWhoseSessionCountsABoundIsNotCountedTwiceInTheRows() {
        MissionId m = missionInProgress();
        company.meetings().call(ctx(Actor.director()), m, List.of(CHRIS), "The report");
        TicketId turns = ((dev.floor42.domain.event.MeetingRoomEvent.MeetingViewsAsked) company.meetings()
                        .askForViews(ctx(Actor.director()), "MT-1", List.of(CHRIS), 5.0, null)
                        .getFirst()
                        .event())
                .ticket();
        company.sessions().start(ctx(Actor.system()), "s1", turns, CHRIS, "claude-sonnet-5-5", "2.1.304");
        company.sessions().end(ctx(Actor.system()), "s1", true, "interrupted", null, null, null, null, 0.30);
        company.meetings().recordTurn(ctx(Actor.system()), "MT-1", SpokenTurn.notHeard(1, CHRIS, "s1", "stopped", 0.4));
        Mission mission = company.missions().find(m).orElseThrow();

        var costs = Costs.costsOf(company, mission);
        assertThat(costs.agents())
                .filteredOn(l -> l.key().equals(CHRIS.value()))
                .singleElement()
                .satisfies(l ->
                        assertThat(l.usdBound()).as("its session's cap only").isEqualTo(0.30));
        assertThat(costs.tickets())
                .filteredOn(l -> l.key().equals(turns.value()))
                .singleElement()
                .satisfies(l -> assertThat(l.usdBound()).isEqualTo(0.30));
        assertThat(costs.mission().total().usdBound()).isEqualTo(0.30);
    }
}
