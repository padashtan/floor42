package dev.floor42.server.app;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Company;
import dev.floor42.domain.Decision;
import dev.floor42.domain.DefaultPermissions;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.PermissionRule;
import dev.floor42.domain.TicketId;
import dev.floor42.server.runtime.ToolCatalog;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The lines of a mission's permission table Floor42 writes on the director's behalf, journaled as Floor42's (the
 * system actor), never as the director's: the screen tells the director's own choices from these. What an agent may
 * not do is enforced by a rule naming it, not just left unoffered.
 */
@ApplicationScoped
public class MissionPermissions {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(MissionPermissions.class);

    /** What a reviewer, a consulted colleague or the planner may never do: they read, they change nothing. */
    static final List<ActionCategory> CHANGES =
            List.of(ActionCategory.WRITE, ActionCategory.EXECUTE, ActionCategory.NETWORK);

    private final OfficeService office;

    @Inject
    MissionPermissions(OfficeService office) {
        this.office = office;
    }

    void set(MissionId m, String tool, ActionCategory category, Decision decision) {
        office.execute(c -> c.missions().setToolPermission(ctx(), m, tool, category, decision));
    }

    /**
     * The company's default table (D3): the director's lines where set, Floor42's built-in ones otherwise. File tools
     * are confined to the worktree by code whatever the table says (ADR-0012).
     */
    void setDefaults(MissionId m) {
        for (String tool : ToolCatalog.GOVERNED_TOOLS) {
            Decision d = office.query(c -> defaultFor(c, tool));
            set(m, tool, ToolCatalog.category(tool), d);
        }
    }

    /** The company's default line for one tool now: the director's where set, Floor42's built-in one otherwise. */
    static Decision defaultFor(Company c, String tool) {
        return DefaultPermissions.of(c.preferences().defaultPermission(tool), tool);
    }

    /** Adds a rule denying the agent this category, whatever is there already. */
    void deny(MissionId m, AgentId agent, ActionCategory category) {
        office.execute(
                c -> c.missions().addPermission(ctx(), m, new PermissionRule(agent, category, null, Decision.DENY)));
    }

    /** Denies the agent this category unless a rule of the mission already names it for that category. */
    void denyUnlessRuled(MissionId m, AgentId agent, ActionCategory category) {
        boolean exists = office.query(c -> c.missions().find(m).orElseThrow().policy().rules().stream()
                .anyMatch(r -> agent.equals(r.agent()) && r.category() == category));
        if (!exists) deny(m, agent, category);
    }

    /** Denies the agent each category it has no blanket rule for. Returns the rules it added. */
    List<PermissionRule> denyAll(MissionId m, AgentId agent, List<ActionCategory> categories) {
        List<PermissionRule> added = new ArrayList<>();
        for (ActionCategory category : categories) {
            boolean exists = office.query(c -> c.missions().find(m).orElseThrow().policy().rules().stream()
                    .anyMatch(r -> agent.equals(r.agent())
                            && r.category() == category
                            && r.tool() == null
                            && r.commandPrefix() == null));
            if (!exists) {
                PermissionRule rule = new PermissionRule(agent, category, null, Decision.DENY);
                office.execute(c -> c.missions().addPermission(ctx(), m, rule));
                added.add(rule);
            }
        }
        return added;
    }

    /**
     * Bash for this reviewer on this one ticket, on top of the blanket denial of its category: the line names the agent,
     * a tool and a ticket, so it outranks the denial for Bash only, never for another tool (PermissionRule.specificity).
     * The caller adds it only while the sandbox runs the reviewer's shell read-only, and removes it when the review
     * ends. Returns the rule when this call added it, empty when an identical one was there already (not ours to remove).
     */
    Optional<PermissionRule> allowReviewShell(MissionId m, AgentId reviewer, TicketId ticket) {
        PermissionRule rule = reviewShell(reviewer, ticket);
        boolean exists = office.query(
                c -> c.missions().find(m).orElseThrow().policy().rules().contains(rule));
        if (exists) return Optional.empty();
        office.execute(c -> c.missions().addPermission(ctx(), m, rule));
        return Optional.of(rule);
    }

    private static PermissionRule reviewShell(AgentId reviewer, TicketId ticket) {
        return new PermissionRule(reviewer, ActionCategory.EXECUTE, "Bash", null, Decision.ALLOW, ticket);
    }

    /**
     * Whether the director's own table keeps Bash from this reviewer on this ticket: a rule the director recorded that
     * applies to them (the mission, the agent or the ticket) and does not allow Bash, a command's rule included.
     * Floor42's own lines (its default Bash line, the blanket denials) are not the director's choice.
     */
    boolean directorRefusesShell(MissionId m, AgentId reviewer, TicketId ticket) {
        return office.query(c -> {
            var policy = c.missions().find(m).orElseThrow().policy();
            return policy.rules().stream()
                    .filter(r -> r.decision() != Decision.ALLOW
                            && r.category() == ActionCategory.EXECUTE
                            && (r.tool() == null || r.tool().equals("Bash"))
                            && (r.agent() == null || r.agent().equals(reviewer))
                            && (r.ticket() == null || r.ticket().equals(ticket)))
                    .anyMatch(r -> policy.author(r).map(Actor::isDirector).orElse(false));
        });
    }

    void dropAtStart(@Observes StartupEvent ignored) {
        try {
            int n = dropLeftoverReviewShells();
            if (n > 0) LOG.infof("%d review shell line(s) left by a daemon that stopped mid-review were removed", n);
        } catch (RuntimeException e) {
            // Handled: logged; a leftover line only matters while a reviewer's session runs, and decide() refuses its
            // shell
            // when the sandbox is off
            LOG.warnf("Review shell lines not swept at start: %s", e.getMessage());
        }
    }

    /** A review shell line is Floor42's, names an agent and a ticket, and lives for one session: none outlives a restart. */
    int dropLeftoverReviewShells() {
        int dropped = 0;
        for (MissionId m :
                office.query(c -> c.missions().all().stream().map(x -> x.id()).toList())) {
            var policy = office.query(c -> c.missions().find(m).orElseThrow().policy());
            for (PermissionRule r : List.copyOf(policy.rules())) {
                boolean ours = r.agent() != null
                        && r.ticket() != null
                        && "Bash".equals(r.tool())
                        && r.decision() == Decision.ALLOW
                        && policy.author(r).map(a -> !a.isDirector()).orElse(false);
                if (!ours) continue;
                office.execute(c -> c.missions().removePermission(ctx(), m, r));
                dropped++;
            }
        }
        return dropped;
    }

    void remove(MissionId m, PermissionRule rule) {
        office.execute(c -> c.missions().removePermission(ctx(), m, rule));
    }

    private CommandContext ctx() {
        return office.context(Actor.system(), Origin.REAL);
    }
}
