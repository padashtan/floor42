package dev.floor42.server.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Decision;
import dev.floor42.domain.Identifier;
import dev.floor42.domain.PermissionRule;
import dev.floor42.domain.event.ActionEvent;
import dev.floor42.domain.event.CommitteeEvent;
import dev.floor42.domain.event.DeskEvent;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.DomainEvents;
import dev.floor42.domain.event.MissionEvent;
import dev.floor42.domain.event.PreferenceEvent;
import dev.floor42.domain.event.SessionEvent;
import dev.floor42.domain.event.TicketEvent;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EventCodecTest {

    final EventCodec codec = new EventCodec();

    /** Every event type, discovered from the sealed hierarchy, survives a round trip: none can be forgotten. */
    @Test
    void everyEventTypeRoundTrips() throws Exception {
        var types = DomainEvents.types().values();
        assertThat(types).hasSizeGreaterThan(30);
        for (Class<?> type : types) {
            DomainEvent sample = sample(type);
            String json = codec.encode(sample);
            assertThat(codec.decode(type.getSimpleName(), json))
                    .as(type.getSimpleName())
                    .isEqualTo(sample);
        }
    }

    @Test
    void theEventTypesFoundAreExactlyTheCommittedList() {
        assertThat(DomainEvents.types().keySet()).containsExactlyElementsOf(EventCodec.listedTypes());
    }

    @Test
    void theCodecRefusesTypesThatDifferFromTheList() {
        assertThatThrownBy(() -> EventCodec.checkTypes(Set.of("MissionCreated"), Set.of("MissionCreated", "Gone")))
                .isInstanceOf(JournalException.class)
                .hasMessageContaining("not found [Gone]");
        assertThatThrownBy(() -> EventCodec.checkTypes(Set.of("MissionCreated", "New"), Set.of("MissionCreated")))
                .hasMessageContaining("not listed [New]");
    }

    @Test
    void identifiersAreWrittenAsPlainStrings() throws Exception {
        String json = codec.encode(
                new TicketEvent.TicketAssigned(new dev.floor42.domain.TicketId("T-1"), new AgentId("geoffrey")));

        assertThat(json).isEqualTo("{\"ticket\":\"T-1\",\"agent\":\"geoffrey\"}");
    }

    /** Before 1.5.0, Floor42 was Office: its own tools' category was OFFICE, as journaled on M-9. */
    @Test
    void theCategoryOfOfficesToolsIsReadAsFloor42s() {
        DomainEvent old = codec.decode(
                "ActionRequested",
                "{\"action\":\"M-9:toolu_01\",\"mission\":\"M-9\",\"ticket\":\"T-33\",\"agent\":\"chris\","
                        + "\"category\":\"OFFICE\",\"tool\":\"mcp__office__ask_director\","
                        + "\"summary\":\"T-33: \\\"OFFICE\\\" stays as written in a text\"}");

        ActionEvent.ActionRequested a = (ActionEvent.ActionRequested) old;
        assertThat(a.category()).isEqualTo(ActionCategory.FLOOR42);
        assertThat(a.tool()).isEqualTo("mcp__office__ask_director");
        assertThat(a.summary()).isEqualTo("T-33: \"OFFICE\" stays as written in a text");
        assertThat(codec.encode(a)).contains("\"category\":\"FLOOR42\"");

        DomainEvent rule = codec.decode(
                "DefaultPermissionSet",
                "{\"tool\":\"mcp__office__ask_director\",\"category\":\"OFFICE\",\"decision\":\"ALLOW\"}");
        assertThat(((PreferenceEvent.DefaultPermissionSet) rule).category()).isEqualTo(ActionCategory.FLOOR42);
    }

    @Test
    void floor42sOwnActorWasCalledOffice() {
        assertThat(SqliteJournal.actor("SYSTEM", "office")).isEqualTo(dev.floor42.domain.Actor.system());
        assertThat(SqliteJournal.actor("SYSTEM", "floor42")).isEqualTo(dev.floor42.domain.Actor.system());
        assertThat(SqliteJournal.actor("AGENT", "office").id()).isEqualTo("office");
    }

    @Test
    void rulesStoredBeforeToolSettingsExistedStillDecode() {
        DomainEvent old = codec.decode(
                "PermissionRuleAdded",
                "{\"mission\":\"M-1\",\"rule\":{\"agent\":null,\"category\":\"READ\",\"commandPrefix\":null,"
                        + "\"decision\":\"ALLOW\"}}");

        assertThat(((MissionEvent.PermissionRuleAdded) old).rule().tool()).isNull();
    }

    @Test
    void questionsStoredBeforePhrasesExistedStillDecode() {
        DomainEvent old = codec.decode(
                "QuestionAsked",
                "{\"question\":\"Q-1\",\"mission\":\"M-1\",\"kind\":\"ACTION_APPROVAL\",\"askedBy\":\"chris\","
                        + "\"subject\":\"Allow chris to use Bash?\",\"context\":\"git status\",\"options\":[],"
                        + "\"recommendation\":null,\"criticality\":\"MEDIUM\",\"blocking\":true,\"ticket\":null,"
                        + "\"action\":null}");

        assertThat(((DeskEvent.QuestionAsked) old).phrase()).isNull();
        assertThat(((DeskEvent.QuestionAsked) old).subject()).isEqualTo("Allow chris to use Bash?");
    }

    @Test
    void questionsAndTicketsStoredBeforeCodesAndKindsStillDecode() {
        DomainEvent question = codec.decode(
                "QuestionAsked",
                "{\"question\":\"Q-2\",\"mission\":\"M-1\",\"kind\":\"OPEN\",\"askedBy\":null,"
                        + "\"subject\":\"T-2 builds on work done on other branches\",\"context\":\"\","
                        + "\"options\":[\"Go on without it\",\"Drop the ticket\"],\"recommendation\":null,"
                        + "\"criticality\":\"MEDIUM\",\"blocking\":false,\"ticket\":\"T-2\",\"action\":null,"
                        + "\"phrase\":null}");
        DomainEvent ticket = codec.decode(
                "TicketCreated",
                "{\"ticket\":\"T-1\",\"mission\":\"M-1\",\"title\":\"Plan: Inventory\",\"description\":\"\","
                        + "\"priority\":\"NORMAL\",\"dependsOn\":[],\"acceptanceCriteria\":[]}");

        assertThat(((DeskEvent.QuestionAsked) question).optionCodes()).isNull();
        assertThat(((TicketEvent.TicketCreated) ticket).kind()).isNull();
    }

    /** Before 1.8.0 a mission had one committee: its events carry no number and read as committee 1 (ADR-0046). */
    /** Rows written before 1.9.0 have no cap and no bound: they read as none, and a row needing neither omits them. */
    @Test
    void sessionRowsStoredBeforeCapsAndBoundsExistedReadAsNone() {
        DomainEvent started = codec.decode(
                "AgentSessionStarted",
                "{\"session\":\"M-1:s1\",\"mission\":\"M-1\",\"ticket\":\"T-1\",\"agent\":\"chris\","
                        + "\"model\":\"claude-sonnet-5-5\",\"runtimeVersion\":\"2.1.304\"}");
        DomainEvent ended = codec.decode(
                "AgentSessionEnded",
                "{\"session\":\"M-1:s1\",\"failed\":true,\"outcome\":\"interrupted\",\"turns\":null,"
                        + "\"inputTokensProvided\":null,\"outputTokensProvided\":null,\"costUsdEstimated\":null}");

        assertThat(((SessionEvent.AgentSessionStarted) started).capUsd()).isNull();
        assertThat(((SessionEvent.AgentSessionEnded) ended).costUsdBound()).isNull();
        assertThat(codec.encode(ended)).doesNotContain("costUsdBound");
        assertThat(codec.encode(started)).doesNotContain("capUsd");
    }

    @Test
    void committeeEventsStoredBeforeNumbersExistedReadAsTheFirstCommittee() {
        DomainEvent convened = codec.decode(
                "CommitteeConvened",
                "{\"mission\":\"M-1\",\"ticket\":\"T-1\",\"topic\":\"Which build?\",\"members\":[\"ada\",\"bob\"],"
                        + "\"roles\":[\"chair\",\"security\"],\"panel\":null,\"moderated\":null,"
                        + "\"maxDebateRounds\":null,\"directorSeat\":null}");
        DomainEvent advanced =
                codec.decode("CommitteeAdvanced", "{\"mission\":\"M-1\",\"stage\":\"DEBATE\",\"round\":2}");
        DomainEvent ruled = codec.decode(
                "CommitteeArbitrated",
                "{\"mission\":\"M-1\",\"adopted\":true,\"implement\":false,\"overCriticalObjection\":false}");

        assertThat(((CommitteeEvent) convened).committeeNo()).isEqualTo(1);
        assertThat(((CommitteeEvent) advanced).committeeNo()).isEqualTo(1);
        assertThat(((CommitteeEvent) ruled).committeeNo()).isEqualTo(1);
        // Committee 1 is written as before: 1.7.0 still reads it. A second committee carries its number, and 1.7.0
        // sets that row aside as newer (ADR-0042) instead of taking it for the first.
        assertThat(codec.encode(advanced)).isEqualTo("{\"mission\":\"M-1\",\"stage\":\"DEBATE\",\"round\":2}");
        DomainEvent second = new CommitteeEvent.CommitteeAdvanced(
                new dev.floor42.domain.MissionId("M-1"), dev.floor42.domain.Committee.Stage.DEBATE, 2, 2);
        assertThat(codec.encode(second)).endsWith(",\"committeeNo\":2}");
        assertThat(codec.decode("CommitteeAdvanced", codec.encode(second))).isEqualTo(second);
        assertThat(codec.decode("CommitteeAdvanced", codec.encode(advanced))).isEqualTo(advanced);
    }

    /** A first committee's events are written byte for byte as 1.7.0 wrote them, all eight types: 1.7.0 reads them. */
    @Test
    void theFirstCommitteesEventsAreTheBytes170Wrote() {
        var m = new dev.floor42.domain.MissionId("M-1");
        var a = new AgentId("ada");
        var debate = dev.floor42.domain.Committee.Stage.DEBATE;
        java.util.Map<DomainEvent, String> written170 = new java.util.LinkedHashMap<>();
        written170.put(
                new CommitteeEvent.CommitteeConvened(
                        m,
                        new dev.floor42.domain.TicketId("T-1"),
                        "Which build?",
                        List.of("ada", "bob"),
                        List.of("chair", "security"),
                        "idea",
                        true,
                        3,
                        "bob",
                        1),
                "{\"mission\":\"M-1\",\"ticket\":\"T-1\",\"topic\":\"Which build?\",\"members\":[\"ada\",\"bob\"],"
                        + "\"roles\":[\"chair\",\"security\"],\"panel\":\"idea\",\"moderated\":true,"
                        + "\"maxDebateRounds\":3,\"directorSeat\":\"bob\"}");
        written170.put(
                new CommitteeEvent.CommitteeContributed(
                        m,
                        a,
                        2,
                        dev.floor42.domain.Committee.Kind.REBUTTAL,
                        "text",
                        "p",
                        "NEW",
                        null,
                        null,
                        List.of("q?"),
                        1),
                "{\"mission\":\"M-1\",\"member\":\"ada\",\"round\":2,\"kind\":\"REBUTTAL\",\"text\":\"text\","
                        + "\"proposal\":\"p\",\"position\":\"NEW\",\"criticalObjection\":null,\"byDirector\":null,"
                        + "\"questions\":[\"q?\"]}");
        written170.put(
                new CommitteeEvent.CommitteeClarificationOpened(m, a, 2, 1),
                "{\"mission\":\"M-1\",\"analyst\":\"ada\",\"round\":2}");
        written170.put(
                new CommitteeEvent.CommitteeClarificationWritten(m, a, "note", 2, 1),
                "{\"mission\":\"M-1\",\"analyst\":\"ada\",\"text\":\"note\",\"questions\":2}");
        written170.put(
                new CommitteeEvent.CommitteeClarified(m, List.of("yes"), 1),
                "{\"mission\":\"M-1\",\"answers\":[\"yes\"]}");
        written170.put(
                new CommitteeEvent.CommitteeAdvanced(m, debate, 2, 1),
                "{\"mission\":\"M-1\",\"stage\":\"DEBATE\",\"round\":2}");
        written170.put(
                new CommitteeEvent.CommitteeSentBack(m, "cheaper", 1),
                "{\"mission\":\"M-1\",\"constraint\":\"cheaper\"}");
        written170.put(
                new CommitteeEvent.CommitteeArbitrated(m, true, false, true, 1),
                "{\"mission\":\"M-1\",\"adopted\":true,\"implement\":false,\"overCriticalObjection\":true}");

        assertThat(written170.keySet().stream()
                        .map(e -> e.getClass().getSimpleName())
                        .distinct())
                .hasSize(8);
        written170.forEach((event, bytes) -> {
            assertThat(codec.encode(event)).as(event.getClass().getSimpleName()).isEqualTo(bytes);
            assertThat(codec.decode(event.getClass().getSimpleName(), bytes)).isEqualTo(event);
        });
    }

    /** D15: a mission-wide rule is written as before; only a ticket's own rule carries its ticket. */
    @Test
    void aRulesTicketIsWrittenOnlyForATicketsOwnRule() {
        var m = new dev.floor42.domain.MissionId("M-1");
        String wide = codec.encode(new MissionEvent.PermissionRuleAdded(
                m, PermissionRule.forTool("Write", ActionCategory.WRITE, Decision.ASK)));
        MissionEvent.PermissionRuleAdded own = new MissionEvent.PermissionRuleAdded(
                m,
                PermissionRule.forTicket(
                        new dev.floor42.domain.TicketId("T-2"), "Write", ActionCategory.WRITE, Decision.ASK));

        assertThat(wide)
                .isEqualTo("{\"mission\":\"M-1\",\"rule\":{\"agent\":null,\"category\":\"WRITE\",\"tool\":\"Write\","
                        + "\"commandPrefix\":null,\"decision\":\"ASK\"}}");
        assertThat(codec.encode(own)).contains("\"ticket\":\"T-2\"");
        assertThat(codec.decode("PermissionRuleAdded", codec.encode(own))).isEqualTo(own);
    }

    /** Every committee event names its committee: none can be added without the number. */
    @Test
    void everyCommitteeEventCarriesTheCommitteeNumber() {
        var committeeTypes = DomainEvents.types().values().stream()
                .filter(CommitteeEvent.class::isAssignableFrom)
                .toList();
        assertThat(committeeTypes).hasSize(8);
        for (Class<?> type : committeeTypes) {
            RecordComponent[] components = type.getRecordComponents();
            assertThat(components[components.length - 1].getName())
                    .as(type.getSimpleName())
                    .isEqualTo("committeeNo");
            assertThat(components[components.length - 1].getType()).isEqualTo(Integer.class);
        }
    }

    @Test
    void unknownTypesAndFieldsAreRejectedRatherThanGuessed() {
        assertThatThrownBy(() -> codec.decode("SomethingNew", "{}")).isInstanceOf(JournalException.class);
        assertThatThrownBy(() -> codec.decode("TicketStarted", "{\"ticket\":\"T-1\",\"extra\":1}"))
                .isInstanceOf(JournalException.class);
    }

    private static DomainEvent sample(Class<?> type) throws Exception {
        RecordComponent[] components = type.getRecordComponents();
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) args[i] = value(components[i]);
        Class<?>[] signature =
                Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
        return (DomainEvent) type.getDeclaredConstructor(signature).newInstance(args);
    }

    private static Object value(RecordComponent c) throws Exception {
        Class<?> t = c.getType();
        if (t == String.class) return "text with \"quotes\" and é";
        if (t == boolean.class || t == Boolean.class) return true;
        if (t == Integer.class) return 7;
        if (t == Long.class) return 12_345L;
        if (t == long.class) return 4_200L;
        if (t == int.class) return 3;
        if (t == double.class) return 0.42;
        if (t == Double.class) return 0.0421;
        if (t == List.class) {
            // Element type matters for identifiers: use the generic signature.
            String generic = c.getGenericType().getTypeName();
            if (generic.contains("TicketId")) return List.of(new dev.floor42.domain.TicketId("T-9"));
            if (generic.contains("AgentId")) return List.of(new dev.floor42.domain.AgentId("chris"));
            if (generic.contains("StaffingReview$Hire")) {
                return List.of(new dev.floor42.domain.StaffingReview.Hire("Ida", "UI developer"));
            }
            if (generic.contains("DeliveryCopy")) {
                return List.of(
                        new dev.floor42.domain.DeliveryCopy("dev-1", "notes.md", "notes (2).md", "KEPT_BOTH", null),
                        new dev.floor42.domain.DeliveryCopy("dev-1", "run.sh", null, "LEFT_OUT", "not a document"));
            }
            if (generic.contains("ReviewReservation")) {
                return List.of(new dev.floor42.domain.ReviewReservation(2, "covers upgrade", "skips it", "add it"));
            }
            if (generic.contains("ReviewNote")) {
                return List.of(
                        new dev.floor42.domain.ReviewNote("naming", dev.floor42.domain.ReviewNote.Why.NOTE),
                        new dev.floor42.domain.ReviewNote("style", dev.floor42.domain.ReviewNote.Why.NO_CRITERION));
            }
            if (generic.contains("UncommittedWorktree")) {
                return List.of(
                        new dev.floor42.domain.UncommittedWorktree("dev-1", "DIRTY", null, 2, List.of("a.md", "b.md")),
                        new dev.floor42.domain.UncommittedWorktree("dev-2", "UNKNOWN", "GIT_FAILED", 0, List.of()));
            }
            if (generic.contains("ProofStep")) {
                return List.of(
                        new dev.floor42.domain.ProofStep(
                                1, "Up", "echo ok", "FLOOR42", null, "PASSED", null, 0, false, 12, "ok"),
                        new dev.floor42.domain.ProofStep(
                                2, "Look", "open it", "DIRECTOR", "a window", "FAILED", "blank", null, false, 0, ""));
            }
            if (generic.contains("ProofTarget")) return List.of(proofTarget());
            if (generic.contains("String")) return List.of("a", "b");
            throw new IllegalStateException("No list sample for " + generic + " in " + c);
        }
        if (t.isEnum()) return t.getEnumConstants()[t.getEnumConstants().length - 1];
        if (Identifier.class.isAssignableFrom(t))
            return t.getDeclaredConstructor(String.class).newInstance("id-1");
        if (t == PermissionRule.class)
            return new PermissionRule(
                    new AgentId("geoffrey"), ActionCategory.EXECUTE, "Bash", "git status", Decision.ASK);
        if (t == dev.floor42.domain.ProofTarget.class) return proofTarget();
        if (t == dev.floor42.domain.Phrase.class) return dev.floor42.domain.Phrase.of("approval", "tool", "Bash");
        throw new IllegalStateException("No sample for " + t + " in " + c);
    }

    private static dev.floor42.domain.ProofTarget proofTarget() {
        return new dev.floor42.domain.ProofTarget(
                new AgentId("chris"), "0123456789abcdef", "abc1234", "fedcba9876543210");
    }
}
