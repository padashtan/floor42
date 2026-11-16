package dev.floor42.server.api.journal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.ActionId;
import dev.floor42.domain.Actor;
import dev.floor42.domain.Company;
import dev.floor42.domain.GoldenJournals;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.ActionEvent.ActionExecuted;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCancelled;
import dev.floor42.domain.event.NotebookEvent.NoteChecked;
import dev.floor42.domain.event.SessionEvent.AgentSessionEnded;
import dev.floor42.domain.event.TicketEvent.TicketEdited;
import dev.floor42.server.api.event.EnglishLines;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventViewTest {

    private static final Company EMPTY = Company.replay(List.of());

    private static EventEnvelope envelope(DomainEvent event) {
        return new EventEnvelope(
                "e1", Instant.parse("2026-10-30T10:00:00Z"), Origin.REAL, Actor.director(), null, null, null, event);
    }

    @Test
    void aLineCarriesItsCodeAndTheValuesToWriteItInAnyLanguage() {
        var view =
                EventView.of(Company.replay(List.of()), envelope(new MissionCancelled(new MissionId("M-3"), "done")));
        assertThat(view.code()).isEqualTo("MissionCancelled");
        assertThat(view.params()).containsEntry("mission", "M-3").containsEntry("reason", "done");
    }

    @Test
    void theSubjectIsShortenedInTheReplayExport() {
        String sub = "7f3c9a10-1111-2222-3333-444455556666";
        var issued = new dev.floor42.domain.event.SessionEvent.AgentTokenIssued(
                "L1",
                new dev.floor42.domain.AgentId("ada"),
                new MissionId("M-1"),
                new TicketId("T-1"),
                "http://kc",
                "2026-11-13T18:00:00Z",
                "jti-1",
                sub,
                "floor42-agent-ada",
                "floor42-agent-ada",
                List.of("openid"));
        var view = EventView.of(EMPTY, envelope(issued));
        // the journal's own line holds the whole subject
        assertThat(view.code()).isEqualTo("AgentTokenIssued.claims");
        assertThat(view.params()).containsEntry("subject", sub);
        var part = ReplayEvents.part(
                EMPTY,
                envelope(issued),
                1,
                new dev.floor42.server.security.ReplaySanitizer("t", Map.of()),
                new java.util.HashSet<>());
        assertThat(part.text()).contains("on behalf of 7f3c9a10…").doesNotContain(sub);
    }

    @Test
    void aTokenWithoutClaimsKeepsTheOldLine() {
        var old = new dev.floor42.domain.event.SessionEvent.AgentTokenIssued(
                "L1",
                new dev.floor42.domain.AgentId("ada"),
                new MissionId("M-1"),
                new TicketId("T-1"),
                "http://kc",
                "2026-11-13T18:00:00Z",
                "jti-1");
        assertThat(EventView.of(EMPTY, envelope(old)).code()).isEqualTo("AgentTokenIssued");
    }

    @Test
    void theCodeNamesTheVariantWhenTheSentenceChanges() {
        assertThat(EventView.of(EMPTY, envelope(new NoteChecked("N-1", true))).code())
                .isEqualTo("NoteChecked.done");
        assertThat(EventView.of(EMPTY, envelope(new NoteChecked("N-1", false))).code())
                .isEqualTo("NoteChecked.reopened");
    }

    @Test
    void aTicketEditedByFloor42NamesFloor42NotTheDirector() {
        var edited = new TicketEdited(new TicketId("T-24"), "Read", "d", List.of());
        var byFloor42 = new EventEnvelope(
                "e2", Instant.parse("2026-10-30T10:00:00Z"), Origin.REAL, Actor.system(), null, null, null, edited);
        var view = EventView.of(EMPTY, byFloor42);
        assertThat(view.code()).isEqualTo("TicketEdited.floor42");
        assertThat(EnglishLines.of(view.code(), view.params())).isEqualTo("Floor42 tidies T-24's description");
        assertThat(EventView.of(EMPTY, envelope(edited)).code()).isEqualTo("TicketEdited");
    }

    @Test
    void aToolOutputStaysOutOfTheLinesValues() {
        var params = EventView.of(
                        EMPTY, envelope(new ActionExecuted(new ActionId("M-1:toolu_1"), "a very long output", true)))
                .params();
        assertThat(params).doesNotContainKey("output").containsEntry("call", "M-1:toolu_1");
    }

    @Test
    void aReplayFileWritesTheLineFromTheEnglishCatalogue() {
        var view = EventView.of(EMPTY, envelope(new MissionCancelled(new MissionId("M-3"), "done")));
        assertThat(EnglishLines.of(view.code(), view.params())).isEqualTo("M-3 cancelled: done");
        assertThat(EnglishLines.of("NoSuchEvent", Map.of())).isEqualTo("NoSuchEvent");
    }

    /** A replay renders old events against today's company: an approval question still reads REQUESTED. */
    @Test
    void anApprovalQuestionReadsTheStageItWasAskedInNotTheActionsLiveOne() {
        List<String> moved = new java.util.ArrayList<>();
        GoldenJournals.all().forEach((name, events) -> {
            Company now = Company.replay(events);
            for (EventEnvelope e : events) {
                if (!(e.event() instanceof dev.floor42.domain.event.DeskEvent.QuestionAsked q) || q.action() == null)
                    continue;
                EventView v = EventView.of(now, e);
                assertThat(v.stage()).as(name + " " + q.question()).isEqualTo("REQUESTED");
                String live =
                        now.actions().find(q.action()).orElseThrow().stage().name();
                if (!live.equals("REQUESTED")) moved.add(live);
            }
        });
        assertThat(moved).as("actions that moved on after their question").isNotEmpty();
    }

    @Test
    void aCostIsWrittenTheSameWhateverTheMachinesLanguage() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            var view = EventView.of(
                    EMPTY, envelope(new AgentSessionEnded("s-12345678-9", false, "done", 3, 10L, 20L, 0.1, null)));
            assertThat(view.params()).containsEntry("cost", "0.1000").containsEntry("short", "s-123456");
            assertThat(EnglishLines.of(view.code(), view.params()))
                    .isEqualTo("Session s-123456 ended · cost 0.1000 USD (estimated)");
        } finally {
            Locale.setDefault(before);
        }
    }
}
