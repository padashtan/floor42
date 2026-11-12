package dev.floor42.domain;

import java.util.Map;
import java.util.Optional;

/**
 * The company's default permission table (D3) and what an implementation ticket gets from it. The director's line for
 * a tool, where set, decides; Floor42's built-in line fills the rest.
 */
public final class DefaultPermissions {

    /**
     * Floor42's built-in starting table: reading is free, writing asks once, the shell asks for each exact command, the
     * network is forbidden; skills and subagents are open (the director's choice, 2026-10-29), a subagent's calls
     * following the same table.
     */
    public static final Map<String, Decision> BUILT_IN = Map.ofEntries(
            Map.entry("Read", Decision.ALLOW),
            Map.entry("Glob", Decision.ALLOW),
            Map.entry("Grep", Decision.ALLOW),
            Map.entry("Write", Decision.ASK),
            Map.entry("Edit", Decision.ASK),
            Map.entry("Bash", Decision.ASK),
            Map.entry("WebFetch", Decision.DENY),
            Map.entry("WebSearch", Decision.DENY),
            Map.entry("Skill", Decision.ALLOW),
            Map.entry("Agent", Decision.ALLOW));

    private DefaultPermissions() {}

    /** The default line of one tool: the director's where set, the built-in one otherwise. */
    public static Decision of(Optional<Decision> directorsDefault, String tool) {
        return directorsDefault.orElse(BUILT_IN.get(tool));
    }

    /**
     * What the implementation ticket of an adopted decision works under for one tool. A line the director set for the
     * whole mission is the director's explicit choice and decides, even against the defaults; any other line (the
     * committee's read-only table) gives way to the default.
     */
    public static Decision forImplementation(Mission m, String tool, ActionCategory category, Decision defaultLine) {
        PermissionPolicy.Evaluation line = m.policy().evaluate(null, category, tool, null);
        boolean directors = line.rule() != null
                && line.rule().coversWholeTool()
                && m.policy().author(line.rule()).map(Actor::isDirector).orElse(false);
        return directors ? line.decision() : defaultLine;
    }
}
