package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Origin;
import dev.floor42.domain.WorkRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;

/**
 * Floor42's first staff, hired on first need. Once one has left, the director hires someone for the role: nothing is
 * recorded meanwhile.
 */
@ApplicationScoped
public class StaffProvisioner {

    static final AgentId PRODUCER = new AgentId("chris");
    static final AgentId REVIEWER = new AgentId("dehren");
    /** Plans a mission into tickets the director validates (ADR-0017); the committee's architect. */
    static final AgentId PLANNER = new AgentId("geoffrey");
    /** Researches the web for a reviewer, without access to the repository (ADR-0018). */
    static final AgentId RESEARCHER = new AgentId("noe");
    /** Takes a committee's questions to the director before the ruling, and the answers back (WorkRole.ANALYST). */
    static final AgentId ANALYST = new AgentId("linnea");

    private final OfficeService office;

    @Inject
    StaffProvisioner(OfficeService office) {
        this.office = office;
    }

    /** The first agent of the role who can take work, the first developer or reviewer hired if there never was one. */
    AgentId staff(WorkRole role) {
        AgentId first = role == WorkRole.DEVELOPER ? PRODUCER : REVIEWER;
        AgentId found = office.query(
                c -> Staff.active(c, role).stream().map(Agent::id).findFirst().orElse(null));
        if (found != null) return found;
        if (office.query(c -> c.staff().find(first).isEmpty())) {
            if (role == WorkRole.DEVELOPER) {
                hireIfMissing(PRODUCER, "Chris", "Java developer", "claude-sonnet-5-5", "medium");
            } else {
                hireIfMissing(REVIEWER, "Dehren", "Test engineer", "claude-haiku-5-5", "low");
            }
            return first;
        }
        throw new DomainException(
                "NO_" + role.name(), "No " + role.name().toLowerCase(Locale.ROOT) + " can take work: hire one first");
    }

    /** Every active reviewer, at least the first one. */
    List<AgentId> reviewers() {
        AgentId any = staff(WorkRole.REVIEWER);
        List<AgentId> all = office.query(
                c -> Staff.active(c, WorkRole.REVIEWER).stream().map(Agent::id).toList());
        return all.isEmpty() ? List.of(any) : all;
    }

    /** A mission that needs this agent is refused once it has left the company. */
    void requirePresent(AgentId agent, String who) {
        if (office.query(c -> c.staff().find(agent).map(Agent::dismissed).orElse(false))) {
            throw new DomainException("AGENT_DISMISSED", who + " has left the company");
        }
    }

    /** Geoffrey, the planner, is hired on first need. */
    public void ensurePlanner() {
        hireIfMissing(PLANNER, "Geoffrey", "Software architect", "claude-sonnet-5-5", "medium");
    }

    /** Noe researches for reviewers. */
    void ensureResearcher() {
        hireIfMissing(RESEARCHER, "Noe", "Researcher", "claude-sonnet-5-5", "low");
    }

    /** The business analyst sits in when committee members raise questions: hired once, with the first committee. */
    void ensureAnalyst() {
        boolean analyst = office.query(
                c -> c.staff().all().stream().anyMatch(a -> !a.dismissed() && Staff.roleOf(a) == WorkRole.ANALYST));
        if (analyst || office.query(c -> c.staff().find(ANALYST).isPresent())) return;
        office.execute(c -> c.staff()
                .hire(
                        office.context(Actor.director(), Origin.REAL),
                        ANALYST,
                        "Linnea",
                        "Business analyst",
                        WorkRole.ANALYST,
                        "claude-sonnet-5-5",
                        "medium"));
    }

    void hireIfMissing(AgentId id, String name, String role, String model, String effort) {
        if (office.query(c -> c.staff().find(id).isPresent())) return;
        office.execute(c -> c.staff()
                .hire(
                        office.context(Actor.director(), Origin.REAL),
                        id,
                        name,
                        role,
                        "anthropic",
                        model,
                        effort,
                        List.of()));
    }
}
