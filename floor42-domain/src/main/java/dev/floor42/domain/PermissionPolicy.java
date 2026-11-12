package dev.floor42.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Permissions of one mission. Default is deny: an action that nothing allows is outside the agent's contract. The most
 * specific matching rule wins; between equally specific rules, the most restrictive one wins.
 */
public final class PermissionPolicy {

    public record Evaluation(Decision decision, PermissionRule rule) {
        public String basis() {
            return rule == null ? DecisionBasis.defaultDeny() : DecisionBasis.ofRule(rule);
        }
    }

    private final List<PermissionRule> rules = new ArrayList<>();
    /** Who recorded each rule, at the same index: the journal's actor of its {@code PermissionRuleAdded}. */
    private final List<Actor> authors = new ArrayList<>();

    public List<PermissionRule> rules() {
        return List.copyOf(rules);
    }

    void add(PermissionRule rule, Actor author) {
        rules.add(rule);
        authors.add(author);
    }

    boolean remove(PermissionRule rule) {
        int at = rules.indexOf(rule);
        if (at < 0) return false;
        rules.remove(at);
        authors.remove(at);
        return true;
    }

    /** Who recorded this rule: the director, or Floor42 on the director's behalf; empty for a rule not in force. */
    public Optional<Actor> author(PermissionRule rule) {
        int at = rules.lastIndexOf(rule);
        return at < 0 ? Optional.empty() : Optional.ofNullable(authors.get(at));
    }

    /** The mission-wide decision: what applies outside any ticket. A ticket's own rules are left out. */
    public Evaluation evaluate(AgentId agent, ActionCategory category, String tool, String summary) {
        return evaluate(agent, null, category, tool, summary);
    }

    /**
     * The decision for a call made in the work of {@code ticket} (null: outside any ticket). A rule of that ticket
     * outranks the mission's table for its tool; a rule naming the agent outranks both (D15).
     */
    public Evaluation evaluate(AgentId agent, TicketId ticket, ActionCategory category, String tool, String summary) {
        return rules.stream()
                .filter(r -> r.matches(agent, ticket, category, tool, summary))
                .max(Comparator.comparingInt(PermissionRule::specificity)
                        .thenComparingInt(r -> r.decision().ordinal()))
                .map(r -> new Evaluation(r.decision(), r))
                .orElse(new Evaluation(Decision.DENY, null));
    }
}
