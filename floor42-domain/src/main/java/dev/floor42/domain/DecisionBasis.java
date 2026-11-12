package dev.floor42.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Why Floor42 allowed or denied a tool call, as the English sentence it records ({@code ActionAuthorized.basis},
 * {@code ActionDenied.detail}). The journal keeps the sentence; {@link #read} gives back its code and values, so a
 * screen writes it in its own language (ADR-0021). Text from elsewhere (a guard's finding, a tool's own error) has no
 * code and is shown as recorded.
 */
public final class DecisionBasis {

    /** Written before 1.10.0, still read: the journal keeps them. */
    static final String ANSWERED = "director answered ";

    static final String ALREADY = "director already answered ";
    static final String ALLOWED_IT = "director allowed it (";
    static final String DENIED_IT = "director denied it (";
    static final String ALREADY_ALLOWED = "director already allowed ";
    static final String ALREADY_DENIED = "director already denied ";
    static final String FLOOR42_TOOLS = "Floor42 tools are always available";
    static final String DEFAULT_DENY = "no rule allows it (default deny)";
    static final String RULE = "rule: ";

    /** What a rule's basis starts with: a rule's own text after it reads as one ({@link #read}). */
    public static final String RULE_PREFIX = RULE;

    static final String MISSION_IS = "mission is ";
    static final String MISSION_CANCELLED = "mission cancelled";
    static final String NOT_ON_TICKET = " is neither working on nor reviewing ";

    public static final String CODE_ANSWERED = "directorAnswered";
    public static final String CODE_ALREADY_COMMAND = "alreadyAnsweredCommand";
    public static final String CODE_ALREADY_TOOL = "alreadyAnsweredTool";
    public static final String CODE_ALLOWED = "directorAllowed";
    public static final String CODE_DENIED = "directorDenied";
    public static final String CODE_ALREADY_ALLOWED_COMMAND = "alreadyAllowedCommand";
    public static final String CODE_ALREADY_DENIED_COMMAND = "alreadyDeniedCommand";
    public static final String CODE_ALREADY_ALLOWED_TOOL = "alreadyAllowedTool";
    public static final String CODE_ALREADY_DENIED_TOOL = "alreadyDeniedTool";
    public static final String CODE_FLOOR42_TOOLS = "floor42Tools";
    public static final String CODE_DEFAULT_DENY = "defaultDeny";
    public static final String CODE_RULE = "rule";
    public static final String CODE_RULE_AGENT = "ruleAgent";
    public static final String CODE_RULE_TICKET = "ruleTicket";
    public static final String CODE_RULE_AGENT_TICKET = "ruleAgentTicket";
    public static final String CODE_MISSION_NOT_ACTIVE = "missionNotActive";
    public static final String CODE_MISSION_CANCELLED = "missionCancelled";
    public static final String CODE_NOT_ON_TICKET = "notOnTicket";

    private static final Pattern QUESTION = Pattern.compile("Q-\\d+");
    private static final Pattern ALREADY_SCOPE = Pattern.compile("(Q-\\d+) \\((.+)\\)");
    private static final Pattern SCOPE_THEN_QUESTION = Pattern.compile("(.+) \\((Q-\\d+)\\)");
    /** {@link PermissionRule#toString()}: decision, tool or category (and a command prefix), agent, ticket. */
    private static final Pattern RULE_TEXT =
            Pattern.compile("(ALLOW|ASK|ASK_ALWAYS|DENY) (.+) for (any agent|\\S+)(?: on (\\S+))?");

    private DecisionBasis() {}

    /** The director's answer to the question decides: "director allowed it (Q-4)", "director denied it (Q-4)". */
    static String directorAllowed(QuestionId q) {
        return ALLOWED_IT + q + ")";
    }

    static String directorDenied(QuestionId q) {
        return DENIED_IT + q + ")";
    }

    /**
     * A remembered answer decides: what it decided, the scope it covers ({@link ApprovalScope#of}), and the question,
     * "director already allowed every Write request from chris (Q-1)".
     */
    static String alreadyAnswered(QuestionId q, Action a, boolean allowed) {
        return (allowed ? ALREADY_ALLOWED : ALREADY_DENIED) + ApprovalScope.of(a) + " (" + q + ")";
    }

    static String floor42Tools() {
        return FLOOR42_TOOLS;
    }

    static String defaultDeny() {
        return DEFAULT_DENY;
    }

    static String ofRule(PermissionRule rule) {
        return RULE + rule;
    }

    static String missionNotActive(MissionStatus status) {
        return MISSION_IS + status;
    }

    static String missionCancelled() {
        return MISSION_CANCELLED;
    }

    static String notOnTicket(AgentId agent, TicketId ticket) {
        return agent + NOT_ON_TICKET + ticket;
    }

    /** The code and values of a sentence this class wrote; empty for any other text. */
    public static Optional<Phrase> read(String text) {
        if (text == null) return Optional.empty();
        if (text.equals(FLOOR42_TOOLS)) return Optional.of(Phrase.of(CODE_FLOOR42_TOOLS));
        if (text.equals(DEFAULT_DENY)) return Optional.of(Phrase.of(CODE_DEFAULT_DENY));
        if (text.equals(MISSION_CANCELLED)) return Optional.of(Phrase.of(CODE_MISSION_CANCELLED));
        if (text.startsWith(ANSWERED) && QUESTION.matcher(rest(text, ANSWERED)).matches()) {
            return Optional.of(Phrase.of(CODE_ANSWERED, "question", rest(text, ANSWERED)));
        }
        if (text.startsWith(ALREADY)) return already(rest(text, ALREADY));
        Optional<Phrase> decided = decided(text);
        if (decided.isPresent()) return decided;
        if (text.startsWith(RULE)) return rule(rest(text, RULE));
        if (text.startsWith(MISSION_IS)) {
            String status = rest(text, MISSION_IS);
            for (MissionStatus s : MissionStatus.values()) {
                if (s.name().equals(status)) return Optional.of(Phrase.of(CODE_MISSION_NOT_ACTIVE, "status", status));
            }
            return Optional.empty();
        }
        int on = text.indexOf(NOT_ON_TICKET);
        if (on > 0) {
            String agent = text.substring(0, on);
            String ticket = text.substring(on + NOT_ON_TICKET.length());
            if (!agent.contains(" ") && !ticket.isEmpty() && !ticket.contains(" ")) {
                return Optional.of(Phrase.of(CODE_NOT_ON_TICKET, "agent", agent, "ticket", ticket));
            }
        }
        return Optional.empty();
    }

    private static Optional<Phrase> already(String rest) {
        Matcher m = ALREADY_SCOPE.matcher(rest);
        if (!m.matches()) return Optional.empty();
        return scoped(m.group(1), m.group(2), CODE_ALREADY_COMMAND, CODE_ALREADY_TOOL);
    }

    /** The sentences written since 1.10.0, which say what the director decided ({@link #directorAllowed}...). */
    private static Optional<Phrase> decided(String text) {
        if (text.startsWith(ALREADY_ALLOWED)) {
            return alreadyDecided(rest(text, ALREADY_ALLOWED), CODE_ALREADY_ALLOWED_COMMAND, CODE_ALREADY_ALLOWED_TOOL);
        }
        if (text.startsWith(ALREADY_DENIED)) {
            return alreadyDecided(rest(text, ALREADY_DENIED), CODE_ALREADY_DENIED_COMMAND, CODE_ALREADY_DENIED_TOOL);
        }
        return decidedIt(text, ALLOWED_IT, CODE_ALLOWED).or(() -> decidedIt(text, DENIED_IT, CODE_DENIED));
    }

    /** "director allowed it (Q-4)": the code and its question; empty when the text is not this sentence. */
    private static Optional<Phrase> decidedIt(String text, String prefix, String code) {
        if (!text.startsWith(prefix) || !text.endsWith(")")) return Optional.empty();
        String question = text.substring(prefix.length(), text.length() - 1);
        return QUESTION.matcher(question).matches()
                ? Optional.of(Phrase.of(code, "question", question))
                : Optional.empty();
    }

    /** "every Write request from chris (Q-1)", after "director already allowed " or "director already denied ". */
    private static Optional<Phrase> alreadyDecided(String rest, String commandCode, String toolCode) {
        Matcher m = SCOPE_THEN_QUESTION.matcher(rest);
        if (!m.matches()) return Optional.empty();
        return scoped(m.group(2), m.group(1), commandCode, toolCode);
    }

    private static Optional<Phrase> scoped(String question, String scope, String commandCode, String toolCode) {
        if (scope.startsWith(ApprovalScope.COMMAND) && scope.length() > ApprovalScope.COMMAND.length()) {
            return Optional.of(Phrase.of(
                    commandCode, "question", question, "agent", scope.substring(ApprovalScope.COMMAND.length())));
        }
        int from = scope.lastIndexOf(ApprovalScope.REQUEST_FROM);
        if (scope.startsWith(ApprovalScope.EVERY) && from > ApprovalScope.EVERY.length()) {
            return Optional.of(Phrase.of(
                    toolCode,
                    "question",
                    question,
                    "tool",
                    scope.substring(ApprovalScope.EVERY.length(), from),
                    "agent",
                    scope.substring(from + ApprovalScope.REQUEST_FROM.length())));
        }
        return Optional.empty();
    }

    private static Optional<Phrase> rule(String rest) {
        Matcher m = RULE_TEXT.matcher(rest);
        if (!m.matches()) return Optional.empty();
        boolean anyAgent = m.group(3).equals("any agent");
        boolean ticket = m.group(4) != null;
        String code = anyAgent
                ? (ticket ? CODE_RULE_TICKET : CODE_RULE)
                : (ticket ? CODE_RULE_AGENT_TICKET : CODE_RULE_AGENT);
        return Optional.of(Phrase.of(
                code,
                "decision",
                m.group(1),
                "target",
                m.group(2),
                "agent",
                anyAgent ? "" : m.group(3),
                "ticket",
                ticket ? m.group(4) : ""));
    }

    private static String rest(String text, String prefix) {
        return text.substring(prefix.length());
    }
}
