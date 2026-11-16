package dev.floor42.server.app.office.standup;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.ActionId;
import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Criticality;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.QuestionId;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.DeskEvent.QuestionAnswered;
import dev.floor42.domain.event.DeskEvent.QuestionAsked;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.RosterEvent.AgentAssignmentsSuspended;
import dev.floor42.domain.event.RosterEvent.AgentDismissed;
import dev.floor42.domain.event.RosterEvent.AgentHired;
import dev.floor42.domain.event.SessionEvent.AgentSessionEnded;
import dev.floor42.domain.event.SessionEvent.AgentSessionStarted;
import dev.floor42.domain.event.TicketEvent.TicketAssigned;
import dev.floor42.domain.event.TicketEvent.TicketCompleted;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.event.TicketEvent.TicketStarted;
import dev.floor42.domain.event.TicketEvent.TicketSubmittedForReview;
import dev.floor42.server.app.office.standup.Standup.AgentStandup;
import dev.floor42.server.app.office.standup.Standup.Line;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The standup composed from hand-built envelopes: no Quarkus, no journal file, no model. */
class StandupComposerTest {

    private static final MissionId M = new MissionId("M-1");
    private static final AgentId ADA = new AgentId("ada");
    private static final AgentId BOB = new AgentId("bob");
    private static final ZoneId UTC = ZoneId.of("UTC");
    /** A Friday, then the Monday after it. */
    private static final Instant FRI = Instant.parse("2026-11-13T10:00:00Z");

    private static final Instant MON_9 = Instant.parse("2026-11-16T09:05:00Z");

    private final List<EventEnvelope> events = new ArrayList<>();
    private int n;

    private void add(Instant at, Origin origin, DomainEvent e) {
        events.add(new EventEnvelope("e" + ++n, at, origin, Actor.system(), M, null, null, e));
    }

    private void add(Instant at, DomainEvent e) {
        add(at, Origin.REAL, e);
    }

    private void staff() {
        add(FRI.minusSeconds(3600), new AgentHired(ADA, "Ada", "developer", "claude", "m", "low", List.of()));
        add(FRI.minusSeconds(3500), new AgentHired(BOB, "Bob", "reviewer", "claude", "m", "low", List.of()));
    }

    private void ticket(Instant at, String id, String title, List<TicketId> deps) {
        add(at, new TicketCreated(new TicketId(id), M, title, "d", Priority.NORMAL, deps, List.of(), null));
    }

    private AgentStandup of(String agent, Instant now) {
        return StandupComposer.compose(events, now, UTC).agents().stream()
                .filter(a -> a.agent().equals(agent))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> codes(List<Line> lines) {
        return lines.stream().map(Line::code).toList();
    }

    @Test
    void mondayLooksBackAtTheLastDayWithActivityNotAtSunday() {
        staff();
        add(FRI, new AgentSessionStarted("s1", M, null, ADA, "m", null, null));
        assertThat(StandupComposer.compose(events, MON_9, UTC).previousDay()).isEqualTo("2026-11-13");
    }

    @Test
    void whatTheAgentSubmittedAndCompletedAndRanIsYesterdays() {
        staff();
        ticket(FRI, "T-1", "Add the parser", List.of());
        add(FRI, new TicketAssigned(new TicketId("T-1"), ADA));
        add(FRI, new TicketStarted(new TicketId("T-1")));
        add(FRI, new AgentSessionStarted("s1", M, new TicketId("T-1"), ADA, "m", null, null));
        add(FRI, new AgentSessionEnded("s1", false, "ok", 3, null, null, null, null));
        add(FRI, new AgentSessionStarted("s2", M, new TicketId("T-1"), ADA, "m", null, null));
        add(FRI, new AgentSessionEnded("s2", true, "boom", 1, null, null, null, null));
        add(FRI, new TicketSubmittedForReview(new TicketId("T-1"), "done"));
        add(FRI, new TicketCompleted(new TicketId("T-1")));

        List<Line> y = of("ada", MON_9).yesterday();

        assertThat(codes(y)).containsExactlyInAnyOrder("SUBMITTED", "COMPLETED", "SESSIONS", "SESSIONS_FAILED");
        assertThat(y)
                .filteredOn(l -> l.code().equals("SESSIONS"))
                .extracting(Line::count)
                .containsExactly(2);
        assertThat(y)
                .filteredOn(l -> l.code().equals("SESSIONS_FAILED"))
                .extracting(Line::count)
                .containsExactly(1);
        assertThat(y)
                .filteredOn(l -> l.code().equals("COMPLETED"))
                .extracting(Line::text)
                .containsExactly("Add the parser");
        assertThat(of("bob", MON_9).yesterday()).isEmpty();
    }

    @Test
    void todayListsWhatIsOnThePlate() {
        staff();
        ticket(FRI, "T-1", "Working", List.of());
        ticket(FRI, "T-2", "Reviewing", List.of());
        ticket(FRI, "T-3", "Queued", List.of());
        for (String t : List.of("T-1", "T-2", "T-3")) add(FRI, new TicketAssigned(new TicketId(t), ADA));
        add(FRI, new TicketStarted(new TicketId("T-1")));
        add(FRI, new TicketStarted(new TicketId("T-2")));
        add(FRI, new TicketSubmittedForReview(new TicketId("T-2"), "x"));
        add(MON_9.minusSeconds(60), new AgentSessionStarted("s9", M, new TicketId("T-1"), ADA, "m", null, null));

        assertThat(of("ada", MON_9).today().stream().map(l -> l.code() + ":" + l.ticket()))
                .containsExactlyInAnyOrder("IN_PROGRESS:T-1", "IN_REVIEW:T-2", "QUEUED:T-3", "SESSIONS:null");
    }

    @Test
    void blockedHoldsOpenBlockingQuestionsUnmetDependenciesAndSuspension() {
        staff();
        ticket(FRI, "T-1", "First", List.of());
        ticket(FRI, "T-2", "Second", List.of(new TicketId("T-1")));
        add(FRI, new TicketAssigned(new TicketId("T-2"), ADA));
        add(
                FRI,
                new QuestionAsked(
                        new QuestionId("Q-1"),
                        M,
                        QuestionKind.ACTION_APPROVAL,
                        ADA,
                        "May I run it",
                        "c",
                        List.of("yes"),
                        null,
                        Criticality.MEDIUM,
                        true,
                        null,
                        new ActionId("A-1"),
                        null,
                        null));
        add(
                FRI,
                new QuestionAsked(
                        new QuestionId("Q-2"),
                        M,
                        QuestionKind.ACTION_APPROVAL,
                        ADA,
                        "Old one",
                        "c",
                        List.of("yes"),
                        null,
                        Criticality.MEDIUM,
                        true,
                        null,
                        new ActionId("A-2"),
                        null,
                        null));
        add(FRI, new QuestionAnswered(new QuestionId("Q-2"), "yes"));
        add(FRI, new AgentAssignmentsSuspended(ADA, "director"));

        List<Line> b = of("ada", MON_9).blocked();

        assertThat(codes(b)).containsExactlyInAnyOrder("SUSPENDED", "QUESTION", "DEPENDENCY");
        assertThat(b)
                .filteredOn(l -> l.code().equals("QUESTION"))
                .extracting(Line::text)
                .containsExactly("May I run it");
        assertThat(of("bob", MON_9).blocked()).isEmpty();
    }

    @Test
    void aDependencyThatIsDoneBlocksNothing() {
        staff();
        ticket(FRI, "T-1", "First", List.of());
        ticket(FRI, "T-2", "Second", List.of(new TicketId("T-1")));
        add(FRI, new TicketAssigned(new TicketId("T-2"), ADA));
        add(FRI, new TicketCompleted(new TicketId("T-1")));
        assertThat(of("ada", MON_9).blocked()).isEmpty();
    }

    @Test
    void simulatedEventsAndDismissedAgentsAreLeftOut() {
        staff();
        add(FRI, Origin.SIMULATED, new AgentSessionStarted("s1", M, null, ADA, "m", null, null));
        add(FRI, Origin.SIMULATED, new AgentSessionEnded("s1", false, "ok", 1, null, null, null, null));
        add(FRI, new AgentDismissed(BOB, "left"));
        Standup s = StandupComposer.compose(events, MON_9, UTC);
        assertThat(s.agents()).extracting(AgentStandup::agent).containsExactly("ada");
        assertThat(of("ada", MON_9).yesterday()).isEmpty();
    }

    @Test
    void theDayIsTheCallersZoneAndALongTitleIsCut() {
        staff();
        String title = "x".repeat(300);
        // 23:30 UTC on the 12th is already the 13th in Paris.
        Instant late = Instant.parse("2026-11-12T23:30:00Z");
        ticket(late, "T-1", title, List.of());
        add(late, new TicketAssigned(new TicketId("T-1"), ADA));
        add(late, new TicketCompleted(new TicketId("T-1")));
        Standup paris = StandupComposer.compose(events, MON_9, ZoneId.of("Europe/Paris"));
        assertThat(paris.previousDay()).isEqualTo("2026-11-13");
        assertThat(paris.zone()).isEqualTo("Europe/Paris");
        assertThat(paris.agents().getFirst().yesterday().getFirst().text()).hasSize(120);
        // In UTC the completion belongs to the 12th, a day before the last active one: not in the standup.
        assertThat(of("ada", MON_9).yesterday()).isEmpty();
    }

    @Test
    void noActivityBeforeTodayLeavesNoPreviousDay() {
        staff();
        Standup s = StandupComposer.compose(events, Instant.parse("2026-11-13T09:00:00Z"), UTC);
        assertThat(s.previousDay()).isNull();
        assertThat(s.agents()).hasSize(2);
    }
}
