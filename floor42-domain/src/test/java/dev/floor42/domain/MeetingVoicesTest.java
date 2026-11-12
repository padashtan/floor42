package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.DARIUS;
import static dev.floor42.domain.Fixture.GALEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MeetingEvent.MeetingCalled;
import dev.floor42.domain.event.MeetingRoomEvent;
import dev.floor42.domain.event.MeetingRoomEvent.MeetingTurnTaken;
import dev.floor42.domain.event.MeetingRoomEvent.MeetingViewsAsked;
import dev.floor42.domain.workflow.Workflow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Turns of speech at the director's meeting (ADR-0049): asked in an order within a cap, taken in that order, on the
 * meeting's own ticket, which is never one of the mission's tickets and never a ticket command's.
 */
@ExtendWith(GoldenFixtureExtension.class)
class MeetingVoicesTest {

    final Fixture f = new Fixture(Origin.REAL);

    MissionId running() {
        MissionId m = ((dev.floor42.domain.event.MissionEvent.MissionCreated) f.record(f.company
                                .missions()
                                .create(
                                        f.director(),
                                        "Ship the release",
                                        "A release ready to ship",
                                        "C:/repo",
                                        Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        TicketId t = f.ticket(m, "Write the release notes");
        f.record(f.company.tickets().assign(f.director(), t, CHRIS));
        f.record(f.company.missions().submitPlan(f.agent(GALEN), m));
        f.record(f.company.verification().setBudget(f.director(), m, null));
        f.record(f.company.missions().approvePlan(f.director(), m));
        f.record(f.company.tickets().start(f.agent(CHRIS), t));
        return m;
    }

    /** Chris and Darius at the conference table, asked Darius first. */
    MeetingViewsAsked asked(MissionId m) {
        f.record(f.company
                .meetings()
                .call(f.director(), m, List.of(CHRIS, DARIUS), "Review the release notes", MeetingPlace.CONFERENCE));
        List<EventEnvelope> events = f.record(
                f.company.meetings().askForViews(f.director(), "MT-1", List.of(DARIUS, CHRIS), 0.5, " Is it good? "));
        return (MeetingViewsAsked) events.getFirst().event();
    }

    @Test
    void speakersTakeTheirTurnInTheDirectorsOrderOnTheMeetingsOwnTicket() {
        MissionId m = running();
        MeetingViewsAsked e = asked(m);
        assertThat(e)
                .isEqualTo(new MeetingViewsAsked(
                        "MT-1", new TicketId("MT-1.turns"), 1, List.of(DARIUS, CHRIS), 0.5, "Is it good?"));
        Ticket own = f.company.tickets().find(e.ticket()).orElseThrow();
        assertThat(own.kind()).isEqualTo(TicketKind.MEETING);
        assertThat(f.company.tickets().of(m))
                .as("never on the board")
                .extracting(Ticket::id)
                .containsExactly(new TicketId("T-1"));

        assertRefused(
                () -> f.company.sessions().start(f.system(), "s-c", e.ticket(), CHRIS, "m", null), "NOT_ON_TICKET");
        assertRefused(
                () -> f.company
                        .meetings()
                        .recordTurn(f.system(), "MT-1", new SpokenTurn(1, CHRIS, null, "me first", null, null)),
                "NOT_THEIR_TURN");
        f.record(f.company.sessions().start(f.system(), "s-d", e.ticket(), DARIUS, "m", null));
        f.record(f.company
                .actions()
                .request(
                        f.agent(DARIUS),
                        new ActionId("M-1:read"),
                        m,
                        e.ticket(),
                        DARIUS,
                        ActionCategory.READ,
                        "Read",
                        "NOTES.md"));
        assertThat(f.company.queries().statusOf(DARIUS))
                .as("a read at the meeting keeps the speaker seated")
                .isEqualTo(AgentStatus.IN_MEETING);
        f.record(f.company.sessions().end(f.system(), "s-d", false, "completed", 2, 10L, 5L, 0.12));
        f.record(f.company
                .meetings()
                .recordTurn(f.system(), "MT-1", new SpokenTurn(1, DARIUS, "s-d", " Tighten it. ", null, null)));
        f.record(f.company
                .meetings()
                .recordTurn(f.system(), "MT-1", new SpokenTurn(1, CHRIS, null, null, "the cap is reached", null)));

        Meeting mt = f.company.meetings().find("MT-1").orElseThrow();
        assertThat(mt.place()).isEqualTo(MeetingPlace.CONFERENCE);
        assertThat(mt.roundUnderWay()).isEmpty();
        assertThat(mt.rounds().getFirst().turns())
                .extracting(MeetingTurn::attendee, MeetingTurn::answer, MeetingTurn::why)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(DARIUS, "Tighten it.", null),
                        org.assertj.core.groups.Tuple.tuple(CHRIS, null, "the cap is reached"));
        assertThat(f.company.meetings().spentUsdCharged(mt)).isEqualTo(0.12);
        assertThat(f.journal.stream().map(EventEnvelope::event).filter(MeetingTurnTaken.class::isInstance))
                .hasSize(2);
    }

    @Test
    void aTurnNeverBecomesTheLatestSessionOnTheAttendeesWork() {
        MissionId m = running();
        TicketId work = f.firstTicket(m);
        f.record(f.company.sessions().start(f.system(), "s-work", work, CHRIS, "m", null));
        f.record(f.company.sessions().end(f.system(), "s-work", true, "stopped", 3, 1L, 1L, 0.2));
        MeetingViewsAsked e = asked(m);
        f.record(f.company
                .meetings()
                .recordTurn(f.system(), "MT-1", new SpokenTurn(1, DARIUS, null, null, "not heard", null)));
        f.record(f.company.sessions().start(f.system(), "s-turn", e.ticket(), CHRIS, "m", null));
        f.record(f.company.sessions().end(f.system(), "s-turn", false, "completed", 1, 1L, 1L, 0.01));

        assertThat(f.company.sessions().requestContinuation(f.director(), "s-work"))
                .as("the held work session can still be continued with --resume")
                .hasSize(1);
    }

    @Test
    void refusalsSayWhy() {
        MissionId m = running();
        f.record(f.company.meetings().call(f.director(), m, List.of(CHRIS, DARIUS), null));
        assertRefused(() -> ask(List.of(GALEN), 1), "NOT_AN_ATTENDEE");
        assertRefused(() -> ask(List.of(), 1), "MISSING_SPEAKERS");
        assertRefused(() -> ask(List.of(CHRIS, CHRIS), 1), "INVALID_ARGUMENT");
        assertRefused(() -> ask(List.of(CHRIS), 0), "INVALID_ARGUMENT");
        assertRefused(() -> ask(List.of(CHRIS), 25), "INVALID_ARGUMENT");
        assertRefused(
                () -> f.company.meetings().askForViews(f.agent(CHRIS), "MT-1", List.of(CHRIS), 1, null),
                "DIRECTOR_ONLY");
        MeetingViewsAsked e =
                (MeetingViewsAsked) f.record(ask(List.of(CHRIS), 1)).getFirst().event();
        assertRefused(() -> ask(List.of(DARIUS), 1), "ROUND_UNDER_WAY");
        assertRefused(() -> f.company.tickets().assign(f.director(), e.ticket(), DARIUS), "MEETING_TICKET");
        assertRefused(() -> f.company.tickets().cancel(f.director(), e.ticket(), "x"), "MEETING_TICKET");
        assertRefused(() -> f.company.workshop().checkDirective(f.director(), e.ticket()), "MEETING_TICKET");
        assertRefused(
                () -> f.company
                        .tickets()
                        .create(f.director(), m, "Next", "", Priority.NORMAL, List.of(e.ticket()), List.of()),
                "UNKNOWN_DEPENDENCY");
        assertRefused(
                () -> f.company
                        .meetings()
                        .recordTurn(f.director(), "MT-1", new SpokenTurn(1, CHRIS, null, "x", null, null)),
                "FORBIDDEN");
        assertRefused(
                () -> f.company
                        .meetings()
                        .recordTurn(f.system(), "MT-1", new SpokenTurn(2, CHRIS, null, "x", null, null)),
                "UNKNOWN_ROUND");
        assertRefused(
                () -> f.company
                        .meetings()
                        .recordTurn(f.system(), "MT-1", new SpokenTurn(1, CHRIS, null, "x", "y", null)),
                "INVALID_ARGUMENT");
        f.record(f.company.sessions().start(f.system(), "s1", e.ticket(), CHRIS, "m", null));
        f.record(f.company.sessions().end(f.system(), "s1", false, "completed", 1, 1L, 1L, 0.9));
        f.record(f.company
                .meetings()
                .recordTurn(f.system(), "MT-1", new SpokenTurn(1, CHRIS, "s1", "fine", null, null)));
        assertRefused(() -> ask(List.of(CHRIS), 0.9), "INVALID_ARGUMENT");
        assertRefused(() -> ask(List.of(CHRIS), 0.94), "INVALID_ARGUMENT");
        assertThat(f.record(ask(List.of(CHRIS), 1.5)).getFirst().event())
                .as("a second round raises the meeting's cap, on the same ticket")
                .isEqualTo(new MeetingViewsAsked("MT-1", e.ticket(), 2, List.of(CHRIS), 1.5, null));
    }

    @Test
    void theMeetingTableIsLeftUnsaidAndAMeetingReplaysToTheSameState() {
        MissionId m = running();
        assertThat(f.record(f.company.meetings().call(f.director(), m, List.of(CHRIS), null)))
                .as("the meeting table: one MeetingCalled as before 1.9.0, no MeetingPlaced")
                .extracting(EventEnvelope::event)
                .singleElement()
                .isInstanceOf(MeetingCalled.class);
        assertThat(f.record(f.company
                        .meetings()
                        .call(f.director(), running(), List.of(DARIUS), null, MeetingPlace.CONFERENCE)))
                .extracting(EventEnvelope::event)
                .containsExactly(
                        new MeetingCalled("MT-2", new MissionId("M-2"), List.of(DARIUS), null),
                        new MeetingRoomEvent.MeetingPlaced("MT-2", MeetingPlace.CONFERENCE));
        f.record(f.company.meetings().askForViews(f.director(), "MT-1", List.of(CHRIS), 1, null));

        Company replayed = Company.replay(f.journal);
        Meeting mt = replayed.meetings().find("MT-1").orElseThrow();
        assertThat(mt.place()).isEqualTo(MeetingPlace.TABLE);
        assertThat(mt.ticket()).isEqualTo(new TicketId("MT-1.turns"));
        assertThat(mt.roundUnderWay().flatMap(MeetingRound::next)).contains(CHRIS);
        assertThat(replayed.tickets().of(m)).hasSize(1);
    }

    @Test
    void noRoundOnAMissionNotInProgressNorOnASimulatedOne() {
        MissionId m = running();
        f.record(f.company.meetings().call(f.director(), m, List.of(CHRIS), null));
        f.record(f.company.missions().suspend(f.director(), m, "pause"));
        assertRefused(() -> ask(List.of(CHRIS), 1), "MISSION_NOT_ACTIVE");
    }

    @Test
    void aTurnWithoutAnEstimateIsChargedItsBudgetAndALongAnswerIsKeptCut() {
        MissionId m = running();
        MeetingViewsAsked e = asked(m);
        f.record(f.company.sessions().start(f.system(), "s-d", e.ticket(), DARIUS, "m", null));
        f.record(f.company.sessions().end(f.system(), "s-d", true, "failed", null, null, null, null));
        f.record(f.company
                .meetings()
                .recordTurn(f.system(), "MT-1", SpokenTurn.notHeard(1, DARIUS, "s-d", "the session failed", 0.4)));
        Meeting mt = f.company.meetings().find("MT-1").orElseThrow();
        assertThat(f.company.meetings().spentUsdCharged(mt))
                .as("no estimate: what it was allowed counts")
                .isEqualTo(0.4);
        assertRefused(
                () -> f.company
                        .meetings()
                        .recordTurn(f.system(), "MT-1", SpokenTurn.heard(1, CHRIS, "s-d", "not mine", 0.1)),
                "UNKNOWN_SESSION");
        String longAnswer = "word ".repeat(1_000);
        f.record(f.company.sessions().start(f.system(), "s-c", e.ticket(), CHRIS, "m", null));
        f.record(f.company
                .meetings()
                .recordTurn(f.system(), "MT-1", SpokenTurn.heard(1, CHRIS, "s-c", longAnswer, 0.1)));
        String kept = f.company
                .meetings()
                .find("MT-1")
                .orElseThrow()
                .rounds()
                .getFirst()
                .turns()
                .getLast()
                .answer();
        assertThat(kept).hasSize(MeetingRounds.MAX_ANSWER).endsWith(MeetingRounds.CUT_NOTE);
    }

    @Test
    void aTurnNamesASessionOfItsOwnRoundOnlyChargedOnce() {
        MissionId m = running();
        f.record(f.company.meetings().call(f.director(), m, List.of(CHRIS), null));
        MeetingViewsAsked e =
                (MeetingViewsAsked) f.record(ask(List.of(CHRIS), 1)).getFirst().event();
        f.record(f.company.sessions().start(f.system(), "s1", e.ticket(), CHRIS, "m", null));
        f.record(f.company.sessions().end(f.system(), "s1", false, "completed", 1, 1L, 1L, 0.3));
        f.record(f.company.meetings().recordTurn(f.system(), "MT-1", SpokenTurn.heard(1, CHRIS, "s1", "fine", 0.5)));
        f.record(ask(List.of(CHRIS), 1));
        assertRefused(
                () -> f.company
                        .meetings()
                        .recordTurn(f.system(), "MT-1", SpokenTurn.notHeard(2, CHRIS, "s1", "restart", 0.5)),
                "UNKNOWN_SESSION");
        f.record(f.company
                .meetings()
                .recordTurn(f.system(), "MT-1", SpokenTurn.notHeard(2, CHRIS, null, "restart", null)));
        assertThat(f.company
                        .meetings()
                        .spentUsdCharged(f.company.meetings().find("MT-1").orElseThrow()))
                .as("round 1's session charged once, nothing for a turn that never ran")
                .isEqualTo(0.3);
    }

    @Test
    void aTurnChargedItsBudgetCountsAgainstTheMissionsBudgetAsABound() {
        MissionId m = running();
        f.record(f.company.verification().setBudget(f.director(), m, 1.0));
        MeetingViewsAsked e = asked(m);
        f.record(f.company.sessions().start(f.system(), "s-d", e.ticket(), DARIUS, "m", null));
        f.record(f.company.sessions().end(f.system(), "s-d", true, "failed", null, null, null, null));
        f.record(f.company
                .meetings()
                .recordTurn(f.system(), "MT-1", SpokenTurn.notHeard(1, DARIUS, "s-d", "the session failed", 0.4)));

        assertThat(f.company.verification().spentUsdEstimated(m))
                .as("no estimate came")
                .isZero();
        assertThat(f.company.verification().spentUsdBound(m))
                .as("a session ended before caps were kept: the turn's charge is the bound")
                .isEqualTo(0.4);
        assertThat(f.company.verification().sessionsBounded(m)).isEqualTo(1);
        assertThat(f.company.verification().budgetLeftUsd(m)).contains(0.6);
    }

    /** A turn's session is a session of the mission: its cap and the turn's charge are one amount, counted once. */
    @Test
    void aMeetingTurnWithNoReportedCostCountsOnce() {
        MissionId m = running();
        f.record(f.company.verification().setBudget(f.director(), m, 1.0));
        MeetingViewsAsked e = asked(m);
        f.record(f.company.sessions().start(f.system(), "s-d", e.ticket(), DARIUS, "m", null, 0.4));
        f.record(f.company.sessions().end(f.system(), "s-d", true, "killed", null, null, null, null, 0.4));
        f.record(f.company
                .meetings()
                .recordTurn(f.system(), "MT-1", SpokenTurn.notHeard(1, DARIUS, "s-d", "the session was stopped", 0.4)));

        VerificationFacade v = f.company.verification();
        assertThat(v.spentUsdEstimated(m)).as("no estimate came").isZero();
        assertThat(v.spentUsdBound(m))
                .as("the session's cap, the turn's charge not added")
                .isEqualTo(0.4);
        assertThat(v.sessionsBounded(m)).isEqualTo(1);
        assertThat(v.spentUsdCounted(m)).isEqualTo(0.4);
        assertThat(v.budgetLeftUsd(m)).contains(0.6);
    }

    private List<EventEnvelope> ask(List<AgentId> speakers, double cap) {
        return f.company.meetings().askForViews(f.director(), "MT-1", speakers, cap, null);
    }

    private static void assertRefused(Runnable command, String code) {
        assertThatThrownBy(command::run)
                .isInstanceOf(DomainException.class)
                .extracting(x -> ((DomainException) x).code())
                .isEqualTo(code);
    }
}
