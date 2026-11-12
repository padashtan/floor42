package dev.floor42.domain;

import java.util.Objects;

/**
 * One permission line of a mission policy.
 *
 * @param agent the agent it applies to, or null for every agent of the mission
 * @param tool narrows the rule to one tool (Write, Bash...), or null for every tool of the category
 * @param commandPrefix narrows the rule to actions whose summary starts with this command (on a word boundary), or null
 * @param ticket narrows the rule to the work of one ticket (D15), or null for the whole mission; nullable in rules
 *     journaled before 1.8.0, which all covered the whole mission
 */
public record PermissionRule(
        AgentId agent, ActionCategory category, String tool, String commandPrefix, Decision decision, TicketId ticket) {

    public PermissionRule {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(decision, "decision");
        if (tool != null && tool.isBlank()) {
            throw new IllegalArgumentException("tool must be null or non-blank");
        }
        if (commandPrefix != null && commandPrefix.isBlank()) {
            throw new IllegalArgumentException("commandPrefix must be null or non-blank");
        }
        if (ticket != null && tool == null) {
            throw new IllegalArgumentException("A ticket's rule names its tool: a whole category is the mission's");
        }
    }

    /** A rule for the whole mission. */
    public PermissionRule(
            AgentId agent, ActionCategory category, String tool, String commandPrefix, Decision decision) {
        this(agent, category, tool, commandPrefix, decision, null);
    }

    /** A rule for a whole category. */
    public PermissionRule(AgentId agent, ActionCategory category, String commandPrefix, Decision decision) {
        this(agent, category, null, commandPrefix, decision, null);
    }

    /** The mission-wide setting of one tool, as chosen in the director's permission table. */
    public static PermissionRule forTool(String tool, ActionCategory category, Decision decision) {
        return new PermissionRule(null, category, tool, null, decision, null);
    }

    /** One tool for the work of one ticket only, whoever does it: what that ticket needs, not the mission (D15). */
    public static PermissionRule forTicket(TicketId ticket, String tool, ActionCategory category, Decision decision) {
        return new PermissionRule(null, category, tool, null, decision, Objects.requireNonNull(ticket, "ticket"));
    }

    public boolean coversWholeTool() {
        return agent == null && tool != null && commandPrefix == null && ticket == null;
    }

    /** A ticket's line for one tool, whoever works on it ({@link #forTicket}): no agent, no command. */
    public boolean coversToolOnTicket() {
        return agent == null && tool != null && commandPrefix == null && ticket != null;
    }

    /** Whether the rule applies to this call; {@code onTicket} is the ticket the call works on, or null for none. */
    public boolean matches(AgentId who, TicketId onTicket, ActionCategory what, String usedTool, String summary) {
        if (ticket != null && !ticket.equals(onTicket)) return false;
        return matchesCall(who, what, usedTool, summary);
    }

    /** Whether a mission-wide rule applies; a ticket's rule never applies without its ticket. */
    public boolean matches(AgentId who, ActionCategory what, String usedTool, String summary) {
        if (ticket != null) return false;
        return matchesCall(who, what, usedTool, summary);
    }

    private boolean matchesCall(AgentId who, ActionCategory what, String usedTool, String summary) {
        if (agent != null && !agent.equals(who)) return false;
        if (category != what) return false;
        if (tool != null && !tool.equals(usedTool)) return false;
        if (commandPrefix == null) return true;
        String s = summary == null ? "" : summary.strip();
        // "git diff" must match "git diff --stat" but not "git diff-index".
        if (s.equals(commandPrefix)) return true;
        // An allowed prefix must not smuggle a second command: "git status; rm -rf x" is not "git status".
        // A summary cut at its length cap ("…") hides its end: it never matches an allowed prefix.
        return s.startsWith(commandPrefix + " ")
                && !s.endsWith("…")
                && !hasShellOperator(s.substring(commandPrefix.length()));
    }

    private static boolean hasShellOperator(String rest) {
        // "&" covers "&&" and background jobs, "|" covers "||", ">" and "<" redirections and process substitution.
        return rest.contains(";")
                || rest.contains("&")
                || rest.contains("|")
                || rest.contains(">")
                || rest.contains("<")
                || rest.contains("`")
                || rest.contains("$(")
                || rest.contains("\n")
                || rest.contains("\r");
    }

    /**
     * The most specific matching rule decides, compared on what it names, in this order: an agent, a tool, a command,
     * a ticket; between equals the most restrictive decides (PermissionPolicy). So a ticket's rule (always a tool's)
     * outranks only the mission's line for that same tool; a mission rule naming a command of that tool, or any rule
     * naming the agent (a reviewer's denial), outranks it. Weights 8, 4, 2, 1: each outweighs all those after it.
     */
    int specificity() {
        return (agent != null ? 8 : 0)
                + (tool != null ? 4 : 0)
                + (commandPrefix != null ? 2 : 0)
                + (ticket != null ? 1 : 0);
    }

    @Override
    public String toString() {
        return decision + " " + (tool != null ? tool : category.toString())
                + (commandPrefix != null ? " '" + commandPrefix + "'" : "") + " for "
                + (agent != null ? agent : "any agent")
                + (ticket != null ? " on " + ticket : "");
    }
}
