package dev.floor42.server.app;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The seats the director can fill on a committee (spec section 10.1). Each has its own lens: the responsibilities,
 * constraints and evidence it judges by. An architecture committee is chaired by the architect, who takes part; an idea
 * committee, convened before a new project, is chaired by a neutral moderator who does not.
 */
public final class CommitteeRoles {

    /**
     * @param panels the committees this seat sits on: {@link #ARCHITECTURE}, {@link #IDEA}
     */
    public record Role(
            String key,
            AgentId agent,
            String name,
            String title,
            String model,
            String effort,
            String lens,
            List<String> panels) {
        public Role {
            panels = panels == null ? null : Collections.unmodifiableList(new ArrayList<>(panels));
        }
    }

    public static final String ARCHITECTURE = "architecture";
    public static final String IDEA = "idea";
    public static final String CHAIR = "architect";
    public static final String MODERATOR = "moderator";
    /** The seat of the director's double: the director may speak in it in person. */
    public static final String DOUBLE = "double";
    /** Debate rounds an idea committee may hold to reach agreement before its record. */
    public static final int IDEA_ROUNDS = 3;

    private static final Map<String, Role> ROLES = new LinkedHashMap<>();

    static {
        add(new Role(
                "architect",
                new AgentId("galen"),
                "Galen",
                "Software architect",
                "claude-sonnet-5-5",
                "medium",
                "Structure, boundaries and coupling, fitness for the stated objective, cost of change, simplicity; on a"
                        + " new project, the global architecture as a whole.",
                List.of(ARCHITECTURE, IDEA)));
        add(new Role(
                "security",
                new AgentId("nora"),
                "Nora",
                "Security specialist",
                "claude-sonnet-5-5",
                "low",
                "Threats, trust boundaries, authentication and authorization, secrets, exposure, abuse cases. A risk you"
                        + " would not accept is a critical objection.",
                List.of(ARCHITECTURE, IDEA)));
        add(new Role(
                "developer",
                new AgentId("chris"),
                "Chris",
                "Senior developer",
                "claude-sonnet-5-5",
                "medium",
                "What the code really does today, effort and risk of the change, testability, migration path; on a new"
                        + " project, what it takes to build and maintain.",
                List.of(ARCHITECTURE, IDEA)));
        add(new Role(
                "operations",
                new AgentId("basile"),
                "Basile",
                "Operations and observability specialist",
                "claude-haiku-5-5",
                "medium",
                "Deployment, failure modes, monitoring, logs and metrics, rollback, operating cost.",
                List.of(ARCHITECTURE)));
        add(new Role(
                "integration",
                new AgentId("ines"),
                "Ines",
                "Integration architect",
                "claude-haiku-5-5",
                "medium",
                "Contracts between systems, protocols, compatibility, versioning, data ownership, network paths.",
                List.of(ARCHITECTURE)));
        add(new Role(
                MODERATOR,
                new AgentId("lucie"),
                "Lucie",
                "Neutral moderator",
                "claude-sonnet-5-5",
                "medium",
                "Takes no side and proposes nothing of her own. Brings the positions together, names what they share and"
                        + " where they really differ, and writes the record.",
                List.of(IDEA)));
        add(new Role(
                "lawyer",
                new AgentId("clara"),
                "Clara",
                "Lawyer",
                "claude-sonnet-5-5",
                "medium",
                "The law and contracts: personal data (GDPR), intellectual property and licences, liability, consumer"
                        + " law, terms of use, regulated activities. A legal risk you would not accept is a critical"
                        + " objection.",
                List.of(IDEA)));
        add(new Role(
                "altruist",
                new AgentId("malik"),
                "Malik",
                "Altruist",
                "claude-haiku-5-5",
                "medium",
                "The people affected: users, the vulnerable, workers, society and the environment; accessibility,"
                        + " fairness, what the project gives back. Who gains, who loses, who is left out.",
                List.of(IDEA)));
        add(new Role(
                "visionary",
                new AgentId("iris"),
                "Iris",
                "Visionary disruptor",
                "claude-sonnet-5-5",
                "medium",
                "First principles: question every requirement, delete what is not needed, aim at ten times better rather"
                        + " than ten percent, move fast and iterate, bold bets argued from the physics of the problem;"
                        + " impatient with conventions. Bold, never reckless with people or the law.",
                List.of(IDEA)));
        add(new Role(
                "sales",
                new AgentId("sofia"),
                "Sofia",
                "Salesperson",
                "claude-haiku-5-5",
                "medium",
                "Customers and the market: who buys, why, at what price, against which competitors, how it is sold and"
                        + " distributed, what makes it attractive. Brings her own ideas, not only critiques.",
                List.of(IDEA)));
        add(new Role(
                "novice",
                new AgentId("marcel"),
                "Marcel",
                "Knows nothing about IT, listens",
                "claude-haiku-5-5",
                "low",
                "Knows nothing about computing and says so. Listens, says plainly what he understood and what he did"
                        + " not, asks the naive questions, and says what it would change for ordinary people. Never"
                        + " pretends to understand: jargon nobody explained is an objection.",
                List.of(IDEA)));
        add(new Role(
                DOUBLE,
                new AgentId("double"),
                "Double",
                "The director's double",
                "claude-sonnet-5-5",
                "medium",
                "Thinks as the director does: see the director's profile in the settings (DIRECTOR_PROFILE).",
                List.of(IDEA)));
    }

    private CommitteeRoles() {}

    private static void add(Role r) {
        ROLES.put(r.key(), r);
    }

    /** Floor42's built-in seats only. */
    public static List<Role> all() {
        return List.copyOf(ROLES.values());
    }

    /**
     * The built-in seats, then those the director defined, but for a member who has left the company. A built-in seat
     * whose member left stays listed, {@link #vacant}: it cannot be chosen.
     */
    public static List<Role> all(Company c) {
        List<Role> roles = new java.util.ArrayList<>(ROLES.values());
        roles.addAll(custom(c));
        return roles;
    }

    /** The two chairs: committees cannot meet without them, and nobody can be hired in their place. */
    public static boolean chair(AgentId agent) {
        return ROLES.get(CHAIR).agent().equals(agent)
                || ROLES.get(MODERATOR).agent().equals(agent);
    }

    /** A seat whose member has left the company: nobody sits in it. */
    public static boolean vacant(Company c, Role r) {
        return left(c, r.agent());
    }

    private static boolean left(Company c, AgentId agent) {
        return c.staff().find(agent).map(dev.floor42.domain.Agent::dismissed).orElse(false);
    }

    /** The seats the director defined whose member has not left the company. */
    public static List<Role> custom(Company c) {
        return c.committees().seats().stream()
                .filter(s -> c.staff().find(s.agent()).map(a -> !a.dismissed()).orElse(true))
                .map(s ->
                        new Role(s.key(), s.agent(), s.name(), s.title(), s.model(), s.effort(), s.lens(), s.panels()))
                .toList();
    }

    /** True when {@code key} or {@code agent} is taken by one of Floor42's built-in seats. */
    public static boolean builtIn(String key) {
        return ROLES.containsKey(key)
                || ROLES.values().stream().anyMatch(r -> r.agent().value().equals(key));
    }

    /** The seat of this agent, built-in or defined by the director, or null. */
    public static Role of(AgentId agent, Company c) {
        return all(c).stream().filter(r -> r.agent().equals(agent)).findFirst().orElse(null);
    }

    /** The roles chosen by the director for an architecture committee, chair first. */
    public static List<Role> select(List<String> keys) {
        return select(keys, ARCHITECTURE);
    }

    /**
     * The roles chosen by the director, chair first (the architect, or the moderator of an idea committee), the
     * director's double last so the director has time to speak in its place; unknown keys and seats of another panel
     * refused, duplicates ignored.
     */
    public static List<Role> select(List<String> keys, String panel) {
        return select(keys, panel, ROLES);
    }

    /** {@link #select(List, String)} among the built-in seats and those the director defined. */
    public static List<Role> select(List<String> keys, String panel, Company c) {
        if (keys.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("A committee role key is empty");
        Map<String, Role> seats = new LinkedHashMap<>(ROLES);
        custom(c).forEach(r -> seats.putIfAbsent(r.key(), r));
        for (String k : keys) {
            Role r = seats.get(k.strip().toLowerCase(java.util.Locale.ROOT));
            if (r != null && left(c, r.agent())) {
                throw new IllegalArgumentException(
                        r.name() + " has left the company: their " + r.key() + " seat is empty, choose another one");
            }
        }
        return select(keys, panel, seats);
    }

    private static List<Role> select(List<String> keys, String panel, Map<String, Role> seats) {
        if (!panel.equals(ARCHITECTURE) && !panel.equals(IDEA)) {
            throw new IllegalArgumentException("Unknown committee: " + panel + " (architecture or idea)");
        }
        String chair = panel.equals(IDEA) ? MODERATOR : CHAIR;
        List<String> chosen = new java.util.ArrayList<>(List.of(chair));
        for (String k : keys) {
            String key = k.strip().toLowerCase(java.util.Locale.ROOT);
            Role r = seats.get(key);
            if (r == null) throw new IllegalArgumentException("Unknown committee role: " + k);
            if (!r.panels().contains(panel)) {
                throw new IllegalArgumentException("No " + key + " seat on the " + panel + " committee");
            }
            if (!chosen.contains(key)) chosen.add(key);
        }
        if (chosen.remove(DOUBLE)) chosen.add(DOUBLE);
        int needed = panel.equals(IDEA) ? 3 : 2;
        if (chosen.size() < needed) {
            throw new IllegalArgumentException(
                    panel.equals(IDEA)
                            ? "An idea committee needs at least two members besides the moderator"
                            : "A committee needs at least one member besides the chair");
        }
        return chosen.stream().map(seats::get).toList();
    }

    public static Role of(AgentId agent) {
        return ROLES.values().stream()
                .filter(r -> r.agent().equals(agent))
                .findFirst()
                .orElse(null);
    }
}
