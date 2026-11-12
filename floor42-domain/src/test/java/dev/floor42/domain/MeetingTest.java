package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.DARIUS;
import static dev.floor42.domain.Fixture.GALEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MeetingEvent.MeetingCalled;
import dev.floor42.domain.event.MeetingEvent.MeetingClosed;
import dev.floor42.domain.event.MeetingEvent.MeetingNoteGiven;
import dev.floor42.domain.event.TicketEvent.DirectiveGiven;
import dev.floor42.domain.workflow.Workflow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The director's meetings at the table (ADR-0047): called, notes said, closed; all in the journal, all refused when due. */
@ExtendWith(GoldenFixtureExtension.class)
class MeetingTest {

    final Fixture f = new Fixture(Origin.REAL);

    /** A real mission approved with no cap, its one ticket started by Chris: what a meeting is called about. */
    MissionId running() {
        MissionId m = ((dev.floor42.domain.event.MissionEvent.MissionCreated) f.record(f.company
                                .missions()
                                .create(
                                        f.director(),
                                        "Secure the APIs",
                                        "Protect web services",
                                        "C:/repo",
                                        Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        TicketId t = f.ticket(m, "Validate token audience");
        f.record(f.company.tickets().assign(f.director(), t, CHRIS));
        f.record(f.company.missions().submitPlan(f.agent(GALEN), m));
        f.record(f.company.verification().setBudget(f.director(), m, null));
        f.record(f.company.missions().approvePlan(f.director(), m));
        f.record(f.company.tickets().start(f.agent(CHRIS), t));
        return m;
    }

    @Test
    void theDirectorCallsAMeetingSaysThingsAndClosesIt() {
        MissionId m = running();
        List<EventEnvelope> called =
                f.record(f.company.meetings().call(f.director(), m, List.of(CHRIS, DARIUS, CHRIS), " Scope "));

        EventEnvelope e = called.getFirst();
        assertThat(e.event()).isEqualTo(new MeetingCalled("MT-1", m, List.of(CHRIS, DARIUS), "Scope"));
        assertThat(e.actor().isDirector()).isTrue();
        assertThat(e.origin()).isEqualTo(Origin.REAL);
        assertThat(e.mission()).isEqualTo(m);
        assertThat(f.company.queries().statusOf(CHRIS)).isEqualTo(AgentStatus.IN_MEETING);
        assertThat(f.company.meetings().attending(DARIUS)).isPresent();

        f.record(f.company
                .meetings()
                .note(f.director(), "MT-1", null, " Keep the API stable ", MeetingNoteUse.NOTE_ONLY));
        f.record(f.company
                .meetings()
                .note(f.director(), "MT-1", CHRIS, "Start with the tests", MeetingNoteUse.NOTE_ONLY));
        assertThat(f.company.meetings().notesFor(m, CHRIS))
                .extracting(MeetingNote::text)
                .containsExactly("Keep the API stable", "Start with the tests");
        assertThat(f.company.meetings().notesFor(m, DARIUS))
                .extracting(MeetingNote::text)
                .containsExactly("Keep the API stable");

        List<EventEnvelope> closed = f.record(f.company.meetings().close(f.director(), "MT-1"));
        assertThat(closed.getFirst().event()).isEqualTo(new MeetingClosed("MT-1", m, List.of(CHRIS, DARIUS)));
        assertThat(f.company.queries().statusOf(CHRIS)).isEqualTo(AgentStatus.WORKING);
        assertThat(f.company.meetings().openOn(m)).isEmpty();
        assertThat(f.company.meetings().notesFor(m, CHRIS))
                .as("kept for the next brief")
                .hasSize(2);
    }

    @Test
    void aMeetingReplaysToTheSameState() {
        MissionId m = running();
        f.record(f.company.meetings().call(f.director(), m, List.of(CHRIS), null));
        f.record(f.company.meetings().note(f.director(), "MT-1", CHRIS, "x", MeetingNoteUse.NOTE_ONLY));

        Company replayed = Company.replay(f.journal);
        Meeting mt = replayed.meetings().find("MT-1").orElseThrow();
        assertThat(mt.open()).isTrue();
        assertThat(mt.agenda()).isNull();
        assertThat(mt.notes()).extracting(MeetingNote::attendee).containsExactly(CHRIS);
        assertThat(replayed.queries().statusOf(CHRIS)).isEqualTo(AgentStatus.IN_MEETING);
    }

    @Test
    void aNoteMayAlsoBeADirectiveOnTheAttendeesTicketUnderWay() {
        MissionId m = running();
        TicketId t = f.firstTicket(m);
        f.record(f.company.meetings().call(f.director(), m, List.of(CHRIS, DARIUS), null));

        List<EventEnvelope> events = f.record(f.company
                .meetings()
                .note(f.director(), "MT-1", null, "Log every refusal", MeetingNoteUse.ALSO_DIRECTIVE));

        assertThat(events).extracting(EventEnvelope::event).hasAtLeastOneElementOfType(MeetingNoteGiven.class);
        assertThat(events.stream().map(EventEnvelope::event).filter(DirectiveGiven.class::isInstance))
                .as("one ticket under way: Chris's; Darius has none")
                .containsExactly(new DirectiveGiven(m, t, "From the director's meeting MT-1: Log every refusal"));
        assertThatThrownBy(() ->
                        f.company.meetings().note(f.director(), "MT-1", DARIUS, "x", MeetingNoteUse.ALSO_DIRECTIVE))
                .isInstanceOf(DomainException.class)
                .extracting(x -> ((DomainException) x).code())
                .isEqualTo("NO_TICKET_UNDER_WAY");
    }

    @Test
    void refusalsSayWhy() {
        MissionId m = running();
        assertRefused(() -> f.company.meetings().call(f.agent(CHRIS), m, List.of(CHRIS), null), "DIRECTOR_ONLY");
        assertRefused(() -> f.company.meetings().call(f.director(), m, List.of(), null), "MISSING_ATTENDEES");
        assertRefused(
                () -> f.company.meetings().call(f.director(), m, List.of(new AgentId("nobody")), null),
                "UNKNOWN_AGENT");
        f.record(f.company.staff().dismiss(f.director(), GALEN, "gone"));
        assertRefused(() -> f.company.meetings().call(f.director(), m, List.of(GALEN), null), "AGENT_DISMISSED");

        f.record(f.company.meetings().call(f.director(), m, List.of(CHRIS), null));
        assertRefused(() -> f.company.meetings().call(f.director(), m, List.of(DARIUS), null), "MEETING_OPEN");
        assertRefused(
                () -> f.company.meetings().note(f.director(), "MT-1", DARIUS, "x", MeetingNoteUse.NOTE_ONLY),
                "NOT_AN_ATTENDEE");
        assertRefused(
                () -> f.company.meetings().note(f.director(), "MT-1", null, " ", MeetingNoteUse.NOTE_ONLY),
                "MISSING_TEXT");
        assertRefused(() -> f.company.meetings().close(f.director(), "MT-9"), "UNKNOWN_MEETING");
        f.record(f.company.meetings().close(f.director(), "MT-1"));
        assertRefused(() -> f.company.meetings().close(f.director(), "MT-1"), "MEETING_CLOSED");

        MissionId other = running();
        f.record(f.company.meetings().call(f.director(), other, List.of(CHRIS), null));
        assertRefused(() -> f.company.meetings().call(f.director(), m, List.of(CHRIS), null), "AGENT_IN_MEETING");
        f.record(f.company.missions().cancel(f.director(), m, "enough"));
        assertRefused(() -> f.company.meetings().call(f.director(), m, List.of(DARIUS), null), "MISSION_CLOSED");
        assertThat(f.company.meetings().close(f.director(), "MT-2"))
                .as("a meeting still closes")
                .hasSize(1);
    }

    @Test
    void anAgentSittingOnACommitteeInSessionIsNotCalled() {
        Fixture r = new Fixture(Origin.REAL);
        MissionId id = ((dev.floor42.domain.event.MissionEvent.MissionCreated) r.record(r.company
                                .missions()
                                .create(
                                        r.director(),
                                        "Gateway?",
                                        "Should we put a gateway?",
                                        "C:/repo",
                                        Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        TicketId ticket = r.ticket(id, "Decide: Gateway?");
        r.record(r.company.tickets().assign(r.director(), ticket, GALEN));
        r.record(r.company
                .committees()
                .convene(
                        r.director(),
                        id,
                        ticket,
                        "Gateway?",
                        List.of(GALEN, DARIUS, CHRIS),
                        List.of("Architect", "Security", "Dev")));
        r.record(r.company.missions().submitPlan(r.agent(GALEN), id));
        r.record(r.company.verification().setBudget(r.director(), id, null));
        r.record(r.company.missions().approvePlan(r.director(), id));
        r.record(r.company.tickets().start(r.agent(GALEN), ticket));

        assertRefused(() -> r.company.meetings().call(r.director(), id, List.of(DARIUS), null), "AGENT_IN_COMMITTEE");
        assertThatThrownBy(() -> r.company.meetings().call(r.director(), id, List.of(DARIUS), null))
                .as("the numbered committee it sits on (ADR-0046)")
                .hasMessageContaining(id + " committee 1");
    }

    /** A rehearsal's staffing review is never worked by a real agent: it must not bar the planner from a real meeting. */
    @Test
    void aStaffingReviewOfARehearsalDoesNotBarThePlannerFromARealMeeting() {
        java.util.function.Function<Actor, CommandContext> sim =
                a -> new CommandContext(a, Origin.SIMULATED, java.time.Instant.parse("2026-10-20T10:00:00Z"), null);
        MissionId rehearsal = ((dev.floor42.domain.event.MissionEvent.MissionCreated) f.record(f.company
                                .missions()
                                .createRehearsal(sim.apply(Actor.director()), "Rehearsal", "Practice", "C:/sample"))
                        .getFirst()
                        .event())
                .mission();
        TicketId t = ((dev.floor42.domain.event.TicketEvent.TicketCreated) f.record(f.company
                                .tickets()
                                .create(
                                        sim.apply(Actor.agent(GALEN)),
                                        rehearsal,
                                        "Practice",
                                        "",
                                        Priority.NORMAL,
                                        List.of(),
                                        List.of("criterion")))
                        .getFirst()
                        .event())
                .ticket();
        f.record(f.company.tickets().assign(sim.apply(Actor.director()), t, CHRIS));
        f.record(f.company.missions().submitPlan(sim.apply(Actor.agent(GALEN)), rehearsal));
        f.record(f.company.verification().setBudget(sim.apply(Actor.director()), rehearsal, null));
        f.record(f.company.missions().approvePlan(sim.apply(Actor.director()), rehearsal));
        f.record(f.company.staff().requestStaffingReview(sim.apply(Actor.director()), rehearsal, GALEN));
        MissionId m = running();

        assertThat(f.company.meetings().call(f.director(), m, List.of(GALEN), null))
                .as("the planner's only review is a rehearsal's")
                .hasSize(1);
    }

    @Test
    void aRehearsalCallsNoMeeting() {
        Fixture sim = new Fixture();
        MissionId m = sim.runningMission();
        assertRefused(() -> sim.company.meetings().call(sim.director(), m, List.of(CHRIS), null), "SIMULATED_MISSION");
    }

    @Test
    void anAgentAtAnOpenRetrospectiveIsNotCalled() {
        MissionId ended = running();
        f.record(f.company.sessions().start(f.system(), "s-1", f.firstTicket(ended), CHRIS, "claude-sonnet-5-5", "2"));
        f.record(f.company.sessions().end(f.system(), "s-1", false, "completed", 3, 10L, 10L, 0.02));
        f.record(f.company.tickets().cancel(f.director(), f.firstTicket(ended), "not needed"));
        f.record(f.company.missions().complete(f.director(), ended, "enough"));
        f.record(f.company.retros().open(f.system(), ended, CHRIS, List.of(CHRIS), "facts"));
        MissionId m = running();

        assertRefused(() -> f.company.meetings().call(f.director(), m, List.of(CHRIS), null), "AGENT_IN_RETRO");
        assertThatThrownBy(() -> f.company.meetings().call(f.director(), m, List.of(CHRIS), null))
                .hasMessageContaining("retrospective of " + ended);
    }

    @Test
    void anAgentWritingAStaffingReviewIsNotCalled() {
        MissionId m = running();
        f.record(f.company.staff().requestStaffingReview(f.director(), m, GALEN));

        assertRefused(() -> f.company.meetings().call(f.director(), m, List.of(GALEN), null), "AGENT_IN_STAFFING");
        assertThat(f.company.meetings().call(f.director(), m, List.of(CHRIS), null))
                .as("the others may still be called")
                .hasSize(1);
    }

    @Test
    void aMeetingOfAnEndedMissionHoldsNobodyAndStillCloses() {
        MissionId m = running();
        f.record(f.company.meetings().call(f.director(), m, List.of(CHRIS, DARIUS), null));
        f.record(f.company.missions().cancel(f.director(), m, "enough"));

        assertThat(f.company.meetings().attending(CHRIS)).isEmpty();
        assertThat(f.company.queries().statusOf(CHRIS)).isNotEqualTo(AgentStatus.IN_MEETING);
        MissionId next = running();
        assertThat(f.company.meetings().call(f.director(), next, List.of(CHRIS), null))
                .as("free for another meeting")
                .hasSize(1);
        assertThat(f.company.meetings().close(f.director(), "MT-1")).hasSize(1);
    }

    @Test
    void aDirectiveOnATicketInReviewReopensIt() {
        MissionId m = running();
        TicketId t = f.firstTicket(m);
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "Done.\nVERDICT: DONE"));
        f.record(f.company.meetings().call(f.director(), m, List.of(CHRIS), null));

        List<EventEnvelope> events = f.record(f.company
                .meetings()
                .note(f.director(), "MT-1", CHRIS, "Check the expiry too", MeetingNoteUse.ALSO_DIRECTIVE));

        assertThat(events)
                .extracting(x -> x.event().getClass().getSimpleName())
                .containsExactly("MeetingNoteGiven", "DirectiveGiven", "TicketReopened");
        assertThat(f.company.tickets().find(t).orElseThrow().status()).isEqualTo(TicketStatus.IN_PROGRESS);
    }

    private static void assertRefused(Runnable command, String code) {
        assertThatThrownBy(command::run)
                .isInstanceOf(DomainException.class)
                .extracting(x -> ((DomainException) x).code())
                .isEqualTo(code);
    }
}
