package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.ActionCategory;
import dev.floor42.server.runtime.RuntimeEvent.AgentText;
import dev.floor42.server.runtime.RuntimeEvent.SessionEnded;
import dev.floor42.server.runtime.RuntimeEvent.SessionStarted;
import dev.floor42.server.runtime.RuntimeEvent.ToolCompleted;
import dev.floor42.server.runtime.RuntimeEvent.ToolRequested;
import dev.floor42.server.runtime.RuntimeEvent.Unfamiliar;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract test against a real (sanitized) Claude Code 2.1.304 transcript recorded by the runtime spike. */
class StreamJsonParserTest {

    final StreamJsonParser parser = new StreamJsonParser(new com.fasterxml.jackson.databind.ObjectMapper());

    static List<String> transcript() throws IOException {
        try (InputStream in = StreamJsonParserTest.class.getResourceAsStream("/transcripts/readonly-inventory.jsonl")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    @Test
    void readsTheSessionToolCallsResultsAndUsage() throws IOException {
        List<RuntimeEvent> events =
                transcript().stream().flatMap(l -> parser.parse(l).stream()).toList();

        assertThat(events.getFirst())
                .isInstanceOfSatisfying(
                        SessionStarted.class, s -> assertThat(s.model()).isEqualTo("claude-haiku-5-5"));
        assertThat(events)
                .filteredOn(ToolRequested.class::isInstance)
                .extracting(e -> ((ToolRequested) e).tool())
                .containsExactly("Bash", "Write", "Read");
        assertThat(events)
                .filteredOn(ToolCompleted.class::isInstance)
                .extracting(e -> ((ToolCompleted) e).error())
                .containsExactly(false, true, false);
        assertThat(events)
                .filteredOn(ToolCompleted.class::isInstance)
                .extracting(e -> ((ToolCompleted) e).refusedByGovernance())
                .as("the write was refused by the hook, the others ran")
                .containsExactly(false, true, false);
        assertThat(events).filteredOn(AgentText.class::isInstance).hasSize(1);
        assertThat(events.getLast()).isInstanceOfSatisfying(SessionEnded.class, end -> {
            assertThat(end.error()).isFalse();
            assertThat(end.turns()).isEqualTo(4);
            assertThat(end.inputTokens()).isPositive();
            assertThat(end.estimatedCostUsd()).isPositive();
        });
    }

    @Test
    void emptyLinesAndUsageWithoutWindowsCarryNothing() {
        assertThat(parser.parse("{\"type\":\"rate_limit_event\",\"rate_limit_info\":{}}"))
                .isEmpty();
        assertThat(parser.parse("")).isEmpty();
    }

    @Test
    void summariesAreRelativeToTheWorkingDirectory() throws Exception {
        var json = new ObjectMapper();

        assertThat(ToolCatalog.summary(
                        "Read",
                        json.readTree("{\"file_path\":\"C:\\\\work\\\\agent\\\\README.md\"}"),
                        "C:\\work\\agent"))
                .isEqualTo("README.md");
        assertThat(ToolCatalog.summary("Bash", json.readTree("{\"command\":\"git status\"}"), "/workspace"))
                .isEqualTo("git status");
        assertThat(ToolCatalog.summary(
                        "Glob",
                        json.readTree("{\"pattern\":\"**/*.java\",\"path\":\"C:\\\\work\\\\agent\"}"),
                        "C:\\work\\agent"))
                .isEqualTo("**/*.java");
        assertThat(ToolCatalog.summary(
                        "Glob",
                        json.readTree("{\"pattern\":\"*\",\"path\":\"C:\\\\work\\\\agent\\\\src\"}"),
                        "C:\\work\\agent"))
                .isEqualTo("* in src");
        assertThat(ToolCatalog.summary(
                        "Glob", json.readTree("{\"pattern\":\"*\",\"path\":\"C:\\\\work\"}"), "C:\\work\\agent"))
                .as("a search aimed outside shows where")
                .isEqualTo("* in C:/work");
        assertThat(ToolCatalog.category("mcp__floor42__ask_director")).isEqualTo(ActionCategory.FLOOR42);
        assertThat(ToolCatalog.category("SomeFutureTool")).isEqualTo(ActionCategory.OTHER);
    }

    @Test
    void usageWindowsAreReadFromARateLimitEvent() {
        // The shape recorded from Claude Code 2.1.304; the readings are neutral values.
        String line = "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"allowed\",\"resetsAt\""
                + ":1790000000,\"rateLimitType\":\"five_hour\",\"overageStatus\":\"rejected\",\"isUsingOverage\""
                + ":false,\"unifiedWindows\":{\"five_hour\":{\"utilization\":0.8,\"resetsAt\":1790000000},"
                + "\"seven_day\":{\"utilization\":0.25,\"resetsAt\":1790400000}}},\"session_id\":\"s\"}";

        assertThat(parser.parse(line))
                .singleElement()
                .isEqualTo(new RuntimeEvent.PlanUsageReported(List.of(
                        new RuntimeEvent.UsageWindow("five_hour", 0.8, 1790000000L, "allowed"),
                        new RuntimeEvent.UsageWindow("seven_day", 0.25, 1790400000L, null))));
    }

    static List<String> lines(String name) throws IOException {
        try (InputStream in = StreamJsonParserTest.class.getResourceAsStream("/transcripts/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    /**
     * Every recorded session, oldest Claude Code first: nothing in them is unknown to Floor42, every call requested is
     * answered in the stream, and the session ends with its usage. A new recording that fails here is a protocol change.
     */
    @Test
    void recordedSessionsHoldNothingOfficeDoesNotKnow() throws IOException {
        for (String name : List.of(
                "readonly-inventory.jsonl",
                "claude-2.1.308-developer.jsonl",
                "claude-2.1.308-reviewer.jsonl",
                "claude-2.1.312-subagent.jsonl")) {
            List<RuntimeEvent> events =
                    lines(name).stream().flatMap(l -> parser.parse(l).stream()).toList();
            assertThat(events).as(name).noneMatch(Unfamiliar.class::isInstance);
            assertThat(events.stream()
                            .filter(ToolCompleted.class::isInstance)
                            .map(e -> ((ToolCompleted) e).toolUseId()))
                    .as(name)
                    .containsExactlyInAnyOrderElementsOf(events.stream()
                            .filter(ToolRequested.class::isInstance)
                            .map(e -> ((ToolRequested) e).toolUseId())
                            .toList());
            assertThat(events.getLast()).as(name).isInstanceOfSatisfying(SessionEnded.class, e -> {
                assertThat(e.inputTokens()).isPositive();
                assertThat(e.estimatedCostUsd()).isPositive();
            });
        }
    }

    /**
     * A developer session of mission M-8 (Claude Code 2.1.308, 2026-10-22): it sees only the tools Floor42 offered and
     * no MCP server, and a shell write the director refused reads as a refusal, not as a tool failure.
     */
    @Test
    void aRecordedDeveloperSessionSeesOnlyOfficesToolsAndItsRefusals() throws IOException {
        List<String> lines = lines("claude-2.1.308-developer.jsonl");
        var init = new ObjectMapper().readTree(lines.getFirst());
        assertThat(init.path("tools").toString()).isEqualTo("[\"Bash\",\"Edit\",\"Glob\",\"Grep\",\"Read\",\"Write\"]");
        assertThat(init.path("mcp_servers").size()).isZero();
        List<RuntimeEvent> events =
                lines.stream().flatMap(l -> parser.parse(l).stream()).toList();
        assertThat(events.getFirst()).isInstanceOfSatisfying(SessionStarted.class, s -> {
            assertThat(s.model()).isEqualTo("claude-sonnet-5-5");
            assertThat(s.runtimeVersion()).isEqualTo("2.1.308");
        });
        assertThat(events)
                .filteredOn(ToolCompleted.class::isInstance)
                .map(e -> (ToolCompleted) e)
                .filteredOn(ToolCompleted::refusedByGovernance)
                .isNotEmpty();
    }

    /** The reviewer of M-8: read-only tools, and a verdict in its final text. */
    @Test
    void aRecordedReviewerSessionIsReadOnlyAndEndsWithAVerdict() throws IOException {
        List<String> lines = lines("claude-2.1.308-reviewer.jsonl");
        assertThat(new ObjectMapper().readTree(lines.getFirst()).path("tools").toString())
                .isEqualTo("[\"Glob\",\"Grep\",\"Read\"]");
        List<RuntimeEvent> events =
                lines.stream().flatMap(l -> parser.parse(l).stream()).toList();
        assertThat(events)
                .filteredOn(AgentText.class::isInstance)
                .last()
                .satisfies(t -> assertThat(((AgentText) t).text()).contains("VERDICT:"));
    }

    /**
     * A session that started an Explore subagent (Claude Code 2.1.312, 2026-10-29): the subagent's own calls come in
     * the same stream, so Floor42 sees and governs them like the agent's.
     */
    @Test
    void aSessionStoppedAtItsCostCapSaysSo() throws IOException {
        // Recorded on Claude Code 2.1.312 with --max-budget-usd 0.001: the cap is checked after the turn that passed
        // it.
        List<RuntimeEvent> events = lines("claude-2.1.312-budget-cap.jsonl").stream()
                .flatMap(l -> parser.parse(l).stream())
                .toList();
        assertThat(events).noneMatch(Unfamiliar.class::isInstance);
        assertThat(events.getLast()).isInstanceOfSatisfying(SessionEnded.class, e -> {
            assertThat(e.error()).isTrue();
            assertThat(e.capReached()).isTrue();
            assertThat(e.estimatedCostUsd()).isGreaterThan(0.001);
        });
    }

    @Test
    void aSubagentsCallsComeInTheSessionsStream() throws IOException {
        List<RuntimeEvent> events = lines("claude-2.1.312-subagent.jsonl").stream()
                .flatMap(l -> parser.parse(l).stream())
                .toList();
        assertThat(events)
                .filteredOn(ToolRequested.class::isInstance)
                .map(e -> ((ToolRequested) e).tool())
                .containsExactly("Agent", "Glob", "Read");
        assertThat(ToolCatalog.summary(
                        "Agent",
                        new ObjectMapper()
                                .readTree(
                                        "{\"subagent_type\":\"Explore\",\"description\":\"Find note.txt\",\"prompt\":\"...\"}"),
                        null))
                .isEqualTo("Explore: Find note.txt");
        assertThat(ToolCatalog.summary("Skill", new ObjectMapper().readTree("{\"skill\":\"db-migration\"}"), null))
                .isEqualTo("db-migration");
        assertThat(ToolCatalog.category("Skill")).isEqualTo(ActionCategory.OTHER);
    }

    /** Kind and shape of each unknown line the parser reported. */
    List<String> unfamiliar(String line) {
        return parser.parse(line).stream()
                .map(e -> (Unfamiliar) e)
                .map(u -> u.kind() + " " + u.shape())
                .toList();
    }

    @Test
    void anUnknownLineOrBlockIsReportedNotDropped() {
        assertThat(unfamiliar("{\"type\":\"system\",\"subtype\":\"brand_new\"}"))
                .containsExactly("system/brand_new {type: \"system\", subtype: \"brand_new\"}");
        assertThat(unfamiliar("{\"type\":\"hologram\"}")).containsExactly("hologram {type: \"hologram\"}");
        assertThat(unfamiliar("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"video\"},"
                        + "{\"type\":\"thinking\",\"thinking\":\"...\"}]}}"))
                .containsExactly("assistant:video {type: \"video\"}");
        assertThat(parser.parse("{\"type\":\"system\",\"subtype\":\"thinking_tokens\"}"))
                .isEmpty();
    }

    @Test
    void anUnknownLineShowsItsShapeAndKeepsItsContentSecretsMasked() {
        String line = "{\"type\":\"memo\",\"text\":\"use sk-ant-api03-abcdefghijkl now\",\"n\":2,"
                + "\"meta\":{\"ok\":true,\"deep\":{\"x\":1}},\"api_key\":\"plain\",\"input_tokens\":12,"
                + "\"auth\":\"Bearer abcdefghijklmnop\",\"items\":[{\"type\":\"rm -rf /\"}]}";
        assertThat(unfamiliar(line))
                .containsExactly("memo {type: \"memo\", text: string, n: number, meta: {ok: boolean, deep: {…}},"
                        + " api_key: string, input_tokens: number, auth: string, items: [{…}]}");
        String content = ((Unfamiliar) parser.parse(line).getFirst()).content();
        assertThat(content)
                .contains("\"text\":\"use [masked] now\"", "\"api_key\":\"[masked]\"", "\"auth\":\"[masked]\"")
                .contains("\"input_tokens\":12", "\"x\":1")
                .doesNotContain("sk-ant", "plain", "abcdefghijklmnop");
    }

    @Test
    void theLessObviousSecretsAreMaskedToo() {
        String line = "{\"type\":\"memo\",\"refresh_tokens\":[\"r1\"],\"credentials\":{\"user\":\"bob\"},"
                + "\"a\":\"password hunter2\",\"b\":\"jwt eyJhbGciOi.eyJzdWIiOi.c2lnbmF0dXJl\","
                + "\"c\":\"https://bob:pa55@example.test/x\",\"d\":\"ASIAABCDEFGHIJKL\",\"output_tokens\":3}";
        String content = ((Unfamiliar) parser.parse(line).getFirst()).content();
        assertThat(content)
                .contains("\"refresh_tokens\":\"[masked]\"", "\"credentials\":\"[masked]\"", "\"output_tokens\":3")
                .doesNotContain("r1", "bob", "hunter2", "eyJzdWIiOi", "pa55", "ASIAABCD");
    }

    @Test
    void aToolsHeartbeatIsItsProgress() {
        // As Claude Code 2.1.316 sends it, every 30 s while a tool runs.
        assertThat(parser.parse("{\"type\":\"tool_progress\",\"tool_use_id\":\"toolu_01-heartbeat-1\","
                        + "\"tool_name\":\"Bash\",\"parent_tool_use_id\":\"toolu_01\",\"elapsed_time_seconds\":60,"
                        + "\"heartbeat\":true,\"session_id\":\"s\",\"uuid\":\"u\"}"))
                .containsExactly(new RuntimeEvent.ToolProgress("toolu_01", "Bash", 60));
        assertThat(parser.parse("{\"type\":\"tool_progress\",\"tool_use_id\":\"toolu_02-heartbeat-0\","
                        + "\"elapsed_time_seconds\":30}"))
                .containsExactly(new RuntimeEvent.ToolProgress("toolu_02", null, 30));
    }

    @Test
    void aLineThatIsNotJsonIsAnUnfamiliarLineMaskedNotAnException() {
        String secret = "sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789";
        List<RuntimeEvent> events = parser.parse("Warning: something odd, token " + secret);

        assertThat(events).singleElement().isInstanceOfSatisfying(Unfamiliar.class, u -> {
            assertThat(u.content()).contains("Warning").doesNotContain(secret);
        });
    }
}
