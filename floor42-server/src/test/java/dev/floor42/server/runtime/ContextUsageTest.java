package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.runtime.RuntimeEvent.ContextUsage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The context gauge (W18) against recorded Claude Code lines: one reading per model message, never a sum, a subagent's
 * lines left out, and no window for a model Floor42 does not know.
 */
class ContextUsageTest {

    final StreamJsonParser parser = new StreamJsonParser(new com.fasterxml.jackson.databind.ObjectMapper());

    static List<String> transcript(String name) throws IOException {
        try (InputStream in = ContextUsageTest.class.getResourceAsStream("/transcripts/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    List<ContextUsage> usages(List<String> lines) {
        return lines.stream()
                .flatMap(l -> parser.parse(l).stream())
                .filter(ContextUsage.class::isInstance)
                .map(ContextUsage.class::cast)
                .toList();
    }

    @Test
    void theLinesOfOneMessageRepeatItsUsageAndTheLastMessageIsTheContextInUse() throws IOException {
        // Recorded on 2.1.308: msg_…KJ7X came as four lines (thinking, text, two tool calls) with the same usage.
        List<ContextUsage> usages = usages(transcript("claude-2.1.308-developer.jsonl"));

        assertThat(usages).hasSize(7);
        assertThat(usages)
                .filteredOn(u -> u.messageId().equals("msg_01s3dErO7Qyyolqz7MbHiOpJ"))
                .extracting(ContextUsage::usedTokens)
                .containsExactly(9_898L, 9_898L, 9_898L, 9_898L);

        ContextGauge gauge = new ContextGauge();
        List<ContextGauge.ContextReading> pushed = new ArrayList<>();
        gauge.subscribe(pushed::add);
        usages.forEach(u -> gauge.observe("M-1:s", "chris", "M-1", "claude-sonnet-5-5", u));

        assertThat(pushed)
                .as("one push per message, duplicates dropped")
                .extracting(ContextGauge.ContextReading::usedTokens)
                .containsExactly(8_544L, 9_129L, 9_898L, 10_913L);
        ContextGauge.ContextReading last = gauge.last("M-1:s").orElseThrow();
        assertThat(last.usedTokens())
                .as("input + cache writes + cache reads of the last request, never a sum of requests")
                .isEqualTo(2L + 1_015L + 9_896L);
        assertThat(last.windowTokens()).isEqualTo(1_000_000L);
        assertThat(last.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(gauge.last("M-1:other")).isEmpty();
    }

    @Test
    void aSubagentsLinesAreAnotherContext() throws IOException {
        // Recorded on 2.1.312: lines 6 and 9 come from a subagent (parent_tool_use_id set), with its own usage.
        List<ContextUsage> usages = usages(transcript("claude-2.1.312-subagent.jsonl"));

        assertThat(usages)
                .extracting(ContextUsage::messageId)
                .containsExactly("msg_01TQXg4fdGzuew1U6NXUaCk3", "msg_01pFPjmmumSx8sB22q4TNhmg");
        assertThat(usages).extracting(ContextUsage::usedTokens).containsExactly(10_649L, 11_296L);
    }

    @Test
    void aSubagentLineGivesNoReadingEvenAlone() {
        String line = "{\"type\":\"assistant\",\"parent_tool_use_id\":\"toolu_1\",\"message\":{\"id\":\"msg_sub\","
                + "\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"done\"}],"
                + "\"usage\":{\"input_tokens\":5,\"cache_creation_input_tokens\":90000,\"cache_read_input_tokens\":0}}}";

        assertThat(parser.parse(line)).noneMatch(ContextUsage.class::isInstance);
    }

    @Test
    void anUnknownModelHasNoWindowNeverAGuess() {
        ContextGauge gauge = new ContextGauge();
        String init = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s1\",\"model\":\"claude-nova-9\"}";
        String model = ((RuntimeEvent.SessionStarted) parser.parse(init).getFirst()).model();
        String line = "{\"type\":\"assistant\",\"parent_tool_use_id\":null,\"message\":{\"id\":\"msg_1\","
                + "\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                + "\"usage\":{\"input_tokens\":3,\"cache_creation_input_tokens\":4000,\"cache_read_input_tokens\":12000,"
                + "\"output_tokens\":9}}}";
        ContextUsage usage = (ContextUsage) parser.parse(line).stream()
                .filter(ContextUsage.class::isInstance)
                .findFirst()
                .orElseThrow();

        gauge.observe("M-2:s1", "ada", "M-2", model, usage);

        ContextGauge.ContextReading reading = gauge.last("M-2:s1").orElseThrow();
        assertThat(reading.usedTokens()).isEqualTo(16_003L);
        assertThat(reading.windowTokens()).isNull();
    }

    @Test
    void windowsAreTheDocumentedOnes() {
        assertThat(ContextWindows.of("claude-opus-5-5")).isEqualTo(1_000_000L);
        assertThat(ContextWindows.of("claude-haiku-5-5")).isEqualTo(1_000_000L);
        assertThat(ContextWindows.of("claude-haiku-4-5-20251001")).isEqualTo(200_000L);
        assertThat(ContextWindows.of("us.anthropic.claude-haiku-4-5")).isEqualTo(200_000L);
        assertThat(ContextWindows.of("claude-sonnet-4-6[1m]")).isEqualTo(1_000_000L);
        assertThat(ContextWindows.of("sonnet"))
                .as("an alias resolves per Claude Code version")
                .isNull();
        assertThat(ContextWindows.of("claude-nova-9")).isNull();
        assertThat(ContextWindows.of(null)).isNull();
    }

    @Test
    void onlyTheLatestSessionsAreKept() {
        ContextGauge gauge = new ContextGauge();
        for (int i = 0; i <= ContextGauge.CAPACITY; i++) {
            gauge.observe("M-1:s" + i, "chris", "M-1", "claude-opus-5-5", new ContextUsage("m", i));
        }
        assertThat(gauge.last("M-1:s0")).isEmpty();
        assertThat(gauge.last("M-1:s" + ContextGauge.CAPACITY)).isPresent();
    }
}
