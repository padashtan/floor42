package dev.floor42.domain;

import dev.floor42.domain.workflow.StepText;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the plan's approval settled about one node of a graph mission (ADR-0062): its risk, its tier and the commands
 * Floor42 runs once the node is delivered. Journaled as {@code NodePlanned}.
 *
 * @param tests the approved test commands; a test the planner wrote as {@code <command> => <result>} gives its command
 */
public record NodePlan(Risk risk, Tier tier, List<String> tests) {

    /** High: the workflow's checks run on the node. Low: the review alone. */
    public enum Risk {
        LOW,
        HIGH;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Light: a cheaper developer and reviewer when one is free. Standard: as before. */
    public enum Tier {
        LIGHT,
        STANDARD;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Separates a test's command from the result it must show. */
    public static final String EXPECTS = "=>";

    private static final Pattern RISK_LINE = Pattern.compile("(?im)^Risk: (low|high)\\b.*$\\R?");
    private static final Pattern RISK_REASON = Pattern.compile("(?im)^Risk: (?:low|high)\\b[\\s:.,;-]*(.*)$");
    private static final Pattern TIER_LINE = Pattern.compile("(?im)^Tier: (light|standard)\\s*$\\R?");
    private static final String TEST_PREFIX = "Test: ";

    public NodePlan {
        tests = List.copyOf(tests);
    }

    public boolean high() {
        return risk == Risk.HIGH;
    }

    public NodePlan withRisk(Risk r) {
        return new NodePlan(r, tier, tests);
    }

    /** The node as the plan proposed it: its Risk and Tier lines, its Test criteria; high and standard otherwise. */
    public static NodePlan proposed(String description, List<String> criteria) {
        String text = description == null ? "" : description;
        Matcher risk = RISK_LINE.matcher(text);
        Matcher tier = TIER_LINE.matcher(text);
        return new NodePlan(
                risk.find() && risk.group(1).equalsIgnoreCase("low") ? Risk.LOW : Risk.HIGH,
                tier.find() && tier.group(1).equalsIgnoreCase("light") ? Tier.LIGHT : Tier.STANDARD,
                testsOf(criteria));
    }

    /** Whether the description still carries the planner's Risk or Tier line. */
    public static boolean hasNotes(String description) {
        return description != null
                && (RISK_LINE.matcher(description).find()
                        || TIER_LINE.matcher(description).find());
    }

    /** The description without the Risk and Tier lines: once the plan is approved they are the node's record. */
    public static String stripNotes(String description) {
        return TIER_LINE
                .matcher(RISK_LINE.matcher(description).replaceAll(""))
                .replaceAll("")
                .strip();
    }

    /** The rationale the planner gave after the Risk line's level, empty when it gave none. */
    public static String riskReason(String description) {
        if (description == null) return "";
        Matcher m = RISK_REASON.matcher(description);
        return m.find() ? m.group(1).strip() : "";
    }

    /**
     * The first test command refused by the step text rule (no bidirectional control, invisible or default-ignorable
     * character, StepText), empty when every command may run. A refused command is a problem at approval, never run.
     */
    public static Optional<StepText.Problem> testProblem(List<String> criteria) {
        return testsOf(criteria).stream()
                .map(c -> StepText.check(StepText.Field.WHY, c))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** The commands of the criteria written {@code Test: <command> => <result>}; a test with no command is not run. */
    static List<String> testsOf(List<String> criteria) {
        return criteria.stream()
                .map(NodePlan::command)
                .flatMap(Optional::stream)
                .toList();
    }

    private static Optional<String> command(String criterion) {
        if (!criterion.startsWith(TEST_PREFIX)) return Optional.empty();
        int at = criterion.indexOf(EXPECTS);
        String command = (at < 0 ? "" : criterion.substring(TEST_PREFIX.length(), at)).strip();
        return command.isEmpty() ? Optional.empty() : Optional.of(command);
    }
}
