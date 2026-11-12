package dev.floor42.server.runtime;

import static dev.floor42.server.runtime.ToolCatalog.FLOOR42_TOOL_PREFIX;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.server.runtime.RuntimeEvent.AgentText;
import dev.floor42.server.runtime.RuntimeEvent.ContextUsage;
import dev.floor42.server.runtime.RuntimeEvent.SessionEnded;
import dev.floor42.server.runtime.RuntimeEvent.SessionStarted;
import dev.floor42.server.runtime.RuntimeEvent.ToolCompleted;
import dev.floor42.server.runtime.RuntimeEvent.ToolRequested;
import dev.floor42.server.runtime.RuntimeEvent.Unfamiliar;
import dev.floor42.server.security.SecretMasker;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reads Claude Code's {@code --output-format stream-json} lines. The protocol evolves with Claude Code: an unknown line
 * or block is not rejected, it is reported as {@link Unfamiliar}, so a change shows instead of being silently dropped.
 * Everything protocol-specific stays in this class; what is known is checked against recorded transcripts.
 */
public final class StreamJsonParser {

    /** System lines seen in real transcripts that carry nothing Floor42 records. */
    static final Set<String> QUIET_SYSTEM =
            Set.of("thinking_tokens", "task_started", "task_progress", "task_updated", "task_notification");

    /** The line with what looks like a secret replaced by {@code [masked]} ({@link SecretMasker}), as compact JSON. */
    static String masked(JsonNode node) {
        return SecretMasker.mask(node).toString();
    }

    private final ObjectMapper json;
    /** Tool name by tool-use id until its result arrives: some results are journaled in part only. */
    private final java.util.Map<String, String> pending = new java.util.concurrent.ConcurrentHashMap<>();

    /** @param json Quarkus's mapper, given by the runtime that owns this parser: one configuration everywhere */
    public StreamJsonParser(ObjectMapper json) {
        this.json = json;
    }

    /**
     * The keys of a line and the JSON type of each value, nested objects one level down, values left out but for the
     * {@code type} and {@code subtype} names: enough to see what a new line is, nothing an agent wrote.
     */
    static String shape(JsonNode node, int depth) {
        if (node.isObject()) {
            if (depth > 1) return "{…}";
            StringBuilder s = new StringBuilder("{");
            node.properties().stream().limit(20).forEach(p -> {
                if (s.length() > 1) s.append(", ");
                s.append(p.getKey()).append(": ");
                JsonNode v = p.getValue();
                boolean name = (p.getKey().equals("type") || p.getKey().equals("subtype"))
                        && v.isTextual()
                        && v.asText().matches("[A-Za-z0-9_./:-]{1,40}");
                s.append(name ? "\"" + v.asText() + "\"" : shape(v, depth + 1));
            });
            if (node.size() > 20) s.append(", …");
            return s.append("}").toString();
        }
        if (node.isArray()) return node.isEmpty() ? "[]" : "[" + shape(node.get(0), depth + 1) + "]";
        if (node.isTextual()) return "string";
        if (node.isNumber()) return "number";
        if (node.isBoolean()) return "boolean";
        return "null";
    }

    public List<RuntimeEvent> parse(String line) {
        if (line == null || line.isBlank()) return List.of();
        JsonNode event;
        try {
            event = json.readTree(line);
        } catch (JsonProcessingException e) {
            // Handled: not JSON (a warning on stdout): an unfamiliar line, masked here before it can reach any message.
            return List.of(new Unfamiliar("(not json)", "text", abbreviate(SecretMasker.maskText(line), 500)));
        }
        List<RuntimeEvent> out = new ArrayList<>();
        switch (event.path("type").asText()) {
            case "system" -> system(event, out);
            case "assistant" -> assistant(event, out);
            case "user" -> user(event, out);
            case "result" -> out.add(result(event));
            case "rate_limit_event" -> rateLimit(event, out);
            case "tool_progress" -> toolProgress(event, out);
            default -> out.add(new Unfamiliar(event.path("type").asText("(no type)"), shape(event, 0), masked(event)));
        }
        return out;
    }

    private void system(JsonNode event, List<RuntimeEvent> out) {
        String subtype = event.path("subtype").asText();
        if (subtype.equals("init")) {
            out.add(new SessionStarted(
                    event.path("session_id").asText(),
                    event.path("model").asText(),
                    event.path("claude_code_version").asText(null)));
        } else if (!QUIET_SYSTEM.contains(subtype)) {
            out.add(new Unfamiliar("system/" + subtype, shape(event, 0), masked(event)));
        }
    }

    private void assistant(JsonNode event, List<RuntimeEvent> out) {
        for (JsonNode block : event.path("message").path("content")) {
            switch (block.path("type").asText()) {
                case "tool_use" -> {
                    pending.put(block.path("id").asText(), block.path("name").asText());
                    out.add(new ToolRequested(
                            block.path("id").asText(), block.path("name").asText(), block.path("input")));
                }
                case "text" -> out.add(new AgentText(block.path("text").asText()));
                case "thinking", "redacted_thinking" -> {} // no governance fact
                default ->
                    out.add(new Unfamiliar("assistant:" + block.path("type").asText(), shape(block, 0), masked(block)));
            }
        }
        ContextUsage context = contextUsage(event);
        if (context != null) out.add(context);
    }

    private void user(JsonNode event, List<RuntimeEvent> out) {
        for (JsonNode block : event.path("message").path("content")) {
            String type = block.path("type").asText();
            if (type.equals("tool_result")) {
                boolean error = block.path("is_error").asBoolean(false);
                String output = text(block.path("content"));
                String id = block.path("tool_use_id").asText();
                // A brokered resource's body is the resource's data, not Floor42's record: only the first line is kept.
                if ((FLOOR42_TOOL_PREFIX + "call_resource").equals(pending.remove(id)) && !error)
                    output = output.lines().findFirst().orElse("");
                out.add(new ToolCompleted(id, error, error && isRefusal(output), output));
            } else if (!type.equals("text")) {
                out.add(new Unfamiliar("user:" + type, shape(block, 0), masked(block)));
            }
        }
    }

    private static SessionEnded result(JsonNode event) {
        JsonNode usage = event.path("usage");
        return new SessionEnded(
                event.path("is_error").asBoolean(false),
                event.has("num_turns") ? event.path("num_turns").asInt() : null,
                event.has("duration_ms") ? event.path("duration_ms").asLong() : null,
                usage.has("input_tokens") ? inputTokens(usage) : null,
                usage.has("output_tokens") ? usage.path("output_tokens").asLong() : null,
                event.has("total_cost_usd") ? event.path("total_cost_usd").asDouble() : null,
                event.path("subtype").asText(null));
    }

    private static void rateLimit(JsonNode event, List<RuntimeEvent> out) {
        JsonNode info = event.path("rate_limit_info");
        String about = info.path("rateLimitType").asText(null);
        List<RuntimeEvent.UsageWindow> windows = new ArrayList<>();
        info.path("unifiedWindows").properties().forEach(w -> {
            JsonNode v = w.getValue();
            if (!v.path("utilization").isNumber() || !v.path("resetsAt").isNumber()) return;
            windows.add(new RuntimeEvent.UsageWindow(
                    w.getKey(),
                    v.path("utilization").asDouble(),
                    v.path("resetsAt").asLong(),
                    w.getKey().equals(about) ? info.path("status").asText(null) : null));
        });
        if (!windows.isEmpty()) out.add(new RuntimeEvent.PlanUsageReported(List.copyOf(windows)));
    }

    /** A tool still running: Claude Code's heartbeat, every 30 s or so, for the call it started. */
    private static void toolProgress(JsonNode event, List<RuntimeEvent> out) {
        String call = event.path("parent_tool_use_id").asText("");
        if (call.isEmpty()) call = event.path("tool_use_id").asText("").replaceFirst("-heartbeat-\\d+$", "");
        if (!call.isEmpty() && event.path("elapsed_time_seconds").isNumber()) {
            out.add(new RuntimeEvent.ToolProgress(
                    call,
                    event.path("tool_name").asText(null),
                    event.path("elapsed_time_seconds").asLong()));
        }
    }

    /**
     * How Claude Code reports a call that was refused before running: a PreToolUse hook decision or failure, or a
     * permission prompt nobody granted. Checked against recorded transcripts in the tests.
     */
    static boolean isRefusal(String output) {
        return output.startsWith("PreToolUse:") || output.contains("haven't granted it yet");
    }

    /**
     * The context of the request an assistant line answers: input, cache writes and cache reads of its usage. Null for
     * a subagent's line (a non-null {@code parent_tool_use_id}: its own context, not the session's) and for a line
     * without usage.
     */
    static ContextUsage contextUsage(JsonNode event) {
        JsonNode parent = event.path("parent_tool_use_id");
        if (!parent.isMissingNode() && !parent.isNull()) return null;
        JsonNode message = event.path("message");
        JsonNode usage = message.path("usage");
        if (!usage.path("input_tokens").isNumber()) return null;
        return new ContextUsage(message.path("id").asText(null), inputTokens(usage));
    }

    /** Input tokens include cache reads and writes: they are all tokens the model processed. */
    private static long inputTokens(JsonNode usage) {
        return usage.path("input_tokens").asLong()
                + usage.path("cache_creation_input_tokens").asLong()
                + usage.path("cache_read_input_tokens").asLong();
    }

    private static String text(JsonNode content) {
        if (content.isTextual()) return content.asText();
        StringBuilder s = new StringBuilder();
        for (JsonNode part : content) {
            if (part.path("type").asText().equals("text"))
                s.append(part.path("text").asText());
        }
        return s.toString();
    }

    static String abbreviate(String s, int max) {
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() > max ? flat.substring(0, max) + "…" : flat;
    }
}
