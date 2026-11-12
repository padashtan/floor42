package dev.floor42.server.api;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.Actor;
import dev.floor42.domain.Company;
import dev.floor42.domain.Decision;
import dev.floor42.domain.DecisionBasis;
import dev.floor42.domain.DefaultPermissions;
import dev.floor42.domain.Mission;
import dev.floor42.domain.PermissionRule;
import dev.floor42.domain.Phrase;
import dev.floor42.server.runtime.ToolCatalog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read models of the director's permission tables: a mission's, and the company's defaults (D3). */
public final class PermissionViews {

    private PermissionViews() {}

    /** A remembered answer the director can take back. */
    public record RememberedView(String question, String agent, String command, String answer) {}

    /**
     * A rule of the mission beyond its line for a tool: a ticket's, an agent's, or one naming a command. It outranks the
     * line where it applies (PermissionRule.specificity), so the table shows it.
     *
     * @param code the rule as a phrase ({@code rulefor.<code>} on the screen): rule, ruleAgent, ruleTicket,
     *     ruleAgentTicket
     * @param by who recorded it: {@link #DIRECTOR}, {@link #FLOOR42}, or {@link #COMMITTEE_TABLE} (a mission line only)
     */
    public record RuleView(String code, Map<String, String> params, String by) {
        public RuleView {
            params = params == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        }

        static RuleView of(Company c, Mission m, PermissionRule rule) {
            Phrase p = DecisionBasis.read(DecisionBasis.RULE_PREFIX + rule).orElseThrow();
            return new RuleView(p.code(), p.params(), PermissionViews.by(c, m, rule));
        }
    }

    /** The director recorded the rule. */
    public static final String DIRECTOR = "DIRECTOR";
    /** Floor42 recorded it on the director's behalf: a work mission's defaults, a ticket's lines, a reviewer's denial. */
    public static final String FLOOR42 = "FLOOR42";
    /** A committee mission's line, which Floor42 sets read only whatever the director's defaults say. */
    public static final String COMMITTEE_TABLE = "COMMITTEE_TABLE";

    /**
     * Who recorded the rule, as the screen names it. Journals written before 1.14.0 recorded Floor42's own lines with
     * the director as actor: they still read as the director's, as they always did.
     */
    static String by(Company c, Mission m, PermissionRule rule) {
        Actor author = m.policy().author(rule).orElse(null);
        if (author == null || author.isDirector()) return DIRECTOR;
        boolean committee = !c.committees().of(m.id()).isEmpty();
        return committee && rule.coversWholeTool() ? COMMITTEE_TABLE : FLOOR42;
    }

    /**
     * One row of the director's permission table: the decision in force for the tool across the mission, where it
     * comes from, the answers already remembered for it, and every rule beyond the line that outranks it somewhere.
     *
     * @param lineBy who recorded the line in force ({@link #by}); null when no line exists
     * @param rules the ticket, agent and command rules for this tool (an agent's rule for its whole category too)
     */
    public record ToolSettingView(
            String tool,
            String category,
            String decision,
            String source,
            String warning,
            List<RememberedView> remembered,
            String lineBy,
            List<RuleView> rules) {
        public ToolSettingView {
            remembered = remembered == null ? null : Collections.unmodifiableList(new ArrayList<>(remembered));
            rules = rules == null ? null : Collections.unmodifiableList(new ArrayList<>(rules));
        }

        static ToolSettingView of(Company c, Mission m, String tool) {
            ActionCategory category = ToolCatalog.category(tool);
            var eval = m.policy().evaluate(null, category, tool, null);
            return new ToolSettingView(
                    tool,
                    category.name(),
                    eval.decision().name(),
                    source(eval.rule()),
                    warning(c, eval.decision(), category),
                    remembered(c, m, tool, category),
                    eval.rule() == null ? null : by(c, m, eval.rule()),
                    beyondTheLine(c, m, tool, category));
        }

        private static List<RuleView> beyondTheLine(Company c, Mission m, String tool, ActionCategory category) {
            return m.policy().rules().stream()
                    .filter(r -> r.category() == category && !r.coversWholeTool())
                    .filter(r -> r.tool() == null || r.tool().equals(tool))
                    .distinct()
                    .map(r -> RuleView.of(c, m, r))
                    .toList();
        }

        private static String source(PermissionRule rule) {
            if (rule == null) return "default: nothing allows it";
            return rule.coversWholeTool() ? "your setting" : "mission rule: " + rule;
        }

        private static String warning(Company c, Decision decision, ActionCategory category) {
            boolean sandboxed = dev.floor42.server.app.OfficeSettings.sandboxed(c);
            if (decision == Decision.ALLOW && category == ActionCategory.EXECUTE && !sandboxed) {
                return "Full shell access: every command runs without asking, beyond the worktree. Turn the WSL"
                        + " sandbox on in the settings to confine it.";
            }
            if (decision == Decision.ALLOW && category == ActionCategory.NETWORK)
                return "Network access without asking.";
            return null;
        }

        private static List<RememberedView> remembered(Company c, Mission m, String tool, ActionCategory category) {
            return c.desk().of(m.id()).stream()
                    .filter(q -> c.desk().remembered(q.id()))
                    .flatMap(q -> c.actions().find(q.action()).stream()
                            .filter(a -> a.tool().equals(tool))
                            .map(a -> new RememberedView(
                                    q.id().value(),
                                    a.agent().value(),
                                    category == ActionCategory.EXECUTE ? a.summary() : null,
                                    q.answer())))
                    .toList();
        }
    }

    /**
     * A line of the company's default permission table (D3): what new work missions start from.
     *
     * @param decision in force: the director's, or Floor42's built-in one
     * @param builtIn Floor42's own line, which a reset goes back to
     */
    public record DefaultPermissionView(
            String tool, String category, String decision, String builtIn, boolean changed) {
        static DefaultPermissionView of(Company c, String tool) {
            Decision builtIn = DefaultPermissions.BUILT_IN.get(tool);
            Optional<Decision> set = c.preferences().defaultPermission(tool);
            return new DefaultPermissionView(
                    tool,
                    ToolCatalog.category(tool).name(),
                    set.orElse(builtIn).name(),
                    builtIn.name(),
                    set.isPresent());
        }
    }
}
