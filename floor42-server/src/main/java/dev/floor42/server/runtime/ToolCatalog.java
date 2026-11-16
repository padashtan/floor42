package dev.floor42.server.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import dev.floor42.domain.ActionCategory;
import dev.floor42.server.security.SecretMasker;
import java.util.List;
import java.util.Objects;

/** Maps runtime tools to permission categories, and tool inputs to the one-line summary policies match against. */
public final class ToolCatalog {

    public static final String FLOOR42_TOOL_PREFIX = "mcp__floor42__";
    /** A tool of a remote MCP server through Floor42 (ADR-0072): reaches outside, so NETWORK and in the table. */
    public static final String CALL_MCP = FLOOR42_TOOL_PREFIX + "call_mcp";
    /** The MCP server was named "office" before 1.5.0: the journal keeps the tool names as they were called. */
    static final String LEGACY_TOOL_PREFIX = "mcp__office__";

    /**
     * Tools the director can set one by one in the permission table, in display order. Skill loads one of the skills
     * Claude Code finds; Agent starts a subagent, whose own tool calls reach Floor42's hook like the agent's (checked on
     * Claude Code 2.1.312: same session, with the subagent's id and type).
     */
    public static final List<String> GOVERNED_TOOLS = List.of(
            "Read", "Glob", "Grep", "Write", "Edit", "Bash", "WebFetch", "WebSearch", "Skill", "Agent", CALL_MCP);

    private ToolCatalog() {}

    public static ActionCategory category(String tool) {
        if (tool.equals(CALL_MCP)) return ActionCategory.NETWORK;
        if (tool.startsWith(FLOOR42_TOOL_PREFIX) || tool.startsWith(LEGACY_TOOL_PREFIX)) return ActionCategory.FLOOR42;
        return switch (tool) {
            case "Read", "Glob", "Grep", "LS" -> ActionCategory.READ;
            case "Write", "Edit", "MultiEdit", "NotebookEdit" -> ActionCategory.WRITE;
            case "Bash", "PowerShell" -> ActionCategory.EXECUTE;
            case "WebFetch", "WebSearch" -> ActionCategory.NETWORK;
            default -> ActionCategory.OTHER;
        };
    }

    /**
     * The summary is what the director reads; a shell call is decided on its full {@link #command}. Paths are shown relative to the
     * agent's working directory when they are inside it.
     */
    public static String summary(String tool, JsonNode input, String workdir) {
        String s = switch (category(tool)) {
            // A line break separates commands in a shell: shown as "; " so the summary cannot hide a second command.
            case EXECUTE -> input.path("command").asText().replaceAll("\\s*[\\r\\n]+\\s*", "; ");
            // For searches the pattern says what is looked for, and the root where, when it is not the working
            // directory: a search refused for leaving the worktree must show where it was aimed.
            case READ, WRITE ->
                tool.equals("Glob") || tool.equals("Grep")
                        ? searchSummary(input, workdir)
                        : firstOf(input, "file_path", "notebook_path", "path", "pattern");
            case NETWORK -> tool.equals(CALL_MCP) ? callMcp(input) : firstOf(input, "url", "query");
            // Asking the director reads as the question itself (ADR-0027).
            case FLOOR42 -> tool.endsWith("ask_director") ? firstOf(input, "question") : input.toString();
            case OTHER ->
                switch (tool) {
                    case "Skill" -> firstOf(input, "skill", "command");
                    // Claude Code lists the subagent tool as Task and names it Agent in its hook.
                    case "Agent", "Task" ->
                        input.path("subagent_type").asText("subagent") + ": " + firstOf(input, "description", "prompt");
                    default -> input.toString();
                };
        };
        // Only a path is shown relative: a command starting with the worktree's path is another program.
        ActionCategory c = category(tool);
        if (c == ActionCategory.READ || c == ActionCategory.WRITE)
            s = Objects.requireNonNullElse(inside(s, workdir), s);
        return StreamJsonParser.abbreviate(s, 300);
    }

    /** "server tool arguments", the arguments with every secret masked: the journal row never holds what the agent typed. */
    private static String callMcp(JsonNode input) {
        return SecretMasker.maskText(input.path("server").asText("") + " "
                + input.path("tool").asText("") + " " + SecretMasker.mask(input.path("arguments")));
    }

    /** A shell call's command exactly as sent, which rules and approvals decide on; null for any other tool. */
    public static String command(String tool, JsonNode input) {
        return category(tool) == ActionCategory.EXECUTE ? input.path("command").asText() : null;
    }

    /**
     * The path relative to the working directory when it is inside it, else null. A Windows path ignores case as
     * Windows does: "c:/users/x/wt/a.txt" is inside "C:\Users\x\wt", so a rule naming "a.txt" applies to it.
     */
    static String inside(String path, String workdir) {
        if (path == null || workdir == null || workdir.isBlank()) return null;
        String dir = workdir.replace('\\', '/');
        while (dir.length() > 1 && dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
        String p = path.replace('\\', '/');
        boolean windows = dir.matches("^[A-Za-z]:.*");
        int n = dir.length() + 1;
        if (p.length() <= n || p.charAt(n - 1) != '/') return null;
        return p.regionMatches(windows, 0, dir, 0, dir.length()) ? p.substring(n) : null;
    }

    private static String searchSummary(JsonNode input, String workdir) {
        String pattern = firstOf(input, "pattern", "path");
        if (!input.hasNonNull("path") || !input.hasNonNull("pattern")) return pattern;
        String root = input.path("path").asText().replace('\\', '/');
        String dir = workdir == null ? "" : workdir.replace('\\', '/');
        if (root.isBlank() || root.equals(".") || root.equals(dir)) return pattern;
        String inside = inside(root, workdir);
        return pattern + " in " + (inside != null ? inside : root);
    }

    private static String firstOf(JsonNode input, String... fields) {
        for (String f : fields) {
            if (input.hasNonNull(f)) return input.path(f).asText();
        }
        return input.toString();
    }
}
