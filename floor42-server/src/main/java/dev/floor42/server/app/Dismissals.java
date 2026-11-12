package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Origin;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.EventEnvelope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The one place that decides whether an agent may leave now, for a single dismissal and for "everyone except the core
 * members" alike. Refused only for the two chairs (nobody can be hired in their place) and while the agent is at work:
 * a task chosen for it by the dispatcher, or one of its sessions open. Each dismissal goes through
 * {@link dev.floor42.domain.StaffFacade#dismiss}, inside the service lock, so a session cannot open between the check
 * and the record.
 */
@ApplicationScoped
public class Dismissals {

    /** Why an agent cannot leave now: the code the API answers with, and a sentence saying what to do. */
    public record Refusal(String code, String message) {}

    /** What "everyone except the core members" would do to one agent. */
    public enum Fate {
        LEAVES,
        STAYS,
        BLOCKED
    }

    public record Entry(Agent agent, Fate fate, Refusal refusal, List<TicketId> handsBack) {
        public Entry {
            handsBack = handsBack == null ? null : Collections.unmodifiableList(new ArrayList<>(handsBack));
        }
    }

    /** What happened to one agent the director confirmed. */
    public enum Result {
        DISMISSED,
        REFUSED,
        SKIPPED
    }

    public record Outcome(
            AgentId agent, String name, Result result, String code, String message, List<TicketId> handsBack) {
        public Outcome {
            handsBack = handsBack == null ? null : Collections.unmodifiableList(new ArrayList<>(handsBack));
        }
    }

    /** Nothing to do rather than refused: a core member, someone already gone, an id nobody has. */
    private static final Set<String> SKIPPED = Set.of("CORE", "ALREADY_DISMISSED", "UNKNOWN_AGENT");

    @Inject
    OfficeService office;

    @Inject
    LiveMissions live;

    /** Empty when the agent may leave now; the domain still refuses an agent that has already left. */
    public Optional<Refusal> refusal(Company c, AgentId agent) {
        if (CommitteeRoles.chair(agent)) {
            return Optional.of(new Refusal(
                    "AGENT_NEEDED",
                    agent + " chairs Floor42's committees and nobody can be hired in their place: they stay. To make"
                            + " them cost less, choose a lighter model in their settings"));
        }
        // Nobody leaves mid-task: a task chosen for the agent, or its session running, keeps it.
        if (live.busy(agent) || c.queries().atWork(agent)) {
            return Optional.of(new Refusal(
                    "AGENT_BUSY",
                    agent + " is at work right now; dismiss it once its session has ended (suspend it first so it"
                            + " takes nothing new)"));
        }
        return Optional.empty();
    }

    /** One dismissal, as the director asks for it from the menu or the profile; its colleagues take up its tickets. */
    public void dismiss(AgentId agent, String reason) {
        office.execute(c -> record(c, agent, reason));
        live.staffChanged();
    }

    /** Inside the service lock: these rules, then the domain's own (an agent already gone, a session open). */
    private List<EventEnvelope> record(Company c, AgentId agent, String reason) {
        refusal(c, agent).ifPresent(r -> {
            throw new DomainException(r.code(), r.message());
        });
        return c.staff().dismiss(office.context(Actor.director(), Origin.REAL), agent, reason);
    }

    /** Everyone on staff, sorted into who would leave, who stays (starred core) and who cannot leave now, and why. */
    public List<Entry> preview() {
        return office.query(c -> c.staff().all().stream()
                .filter(a -> !a.dismissed())
                .map(a -> entry(c, a))
                .toList());
    }

    private Entry entry(Company c, Agent a) {
        if (a.core()) return new Entry(a, Fate.STAYS, null, List.of());
        Optional<Refusal> refused = refusal(c, a.id());
        return refused.isPresent()
                ? new Entry(a, Fate.BLOCKED, refused.get(), List.of())
                : new Entry(a, Fate.LEAVES, null, c.queries().handedBackOnDismissal(a.id()));
    }

    /**
     * Dismisses, one by one, the agents the director confirmed from the preview, each through the single dismissal's
     * path and journaled as its own {@code AgentDismissed}. Each one is judged again when its turn comes, under the
     * lock that records it: a core member is never dismissed here, and one who went to work since the preview is
     * refused, not interrupted. Agents not listed are left alone, whatever changed since the preview.
     */
    public List<Outcome> dismissAllButCore(List<AgentId> confirmed, String reason) {
        List<Outcome> outcomes = new ArrayList<>();
        try {
            for (AgentId id : new LinkedHashSet<>(confirmed)) outcomes.add(dismissOne(id, reason));
        } finally {
            // Their tickets go to colleagues once, after the last of them has left.
            live.staffChanged();
        }
        return outcomes;
    }

    private Outcome dismissOne(AgentId id, String reason) {
        String name =
                office.query(c -> c.staff().find(id).map(Agent::displayName).orElse(id.value()));
        List<TicketId> handsBack = new ArrayList<>();
        try {
            office.execute(c -> {
                // A star given since the preview keeps the agent.
                if (c.staff().find(id).map(Agent::core).orElse(false)) {
                    throw new DomainException("CORE", id + " is a core member: they stay");
                }
                handsBack.addAll(c.queries().handedBackOnDismissal(id));
                return record(c, id, reason);
            });
            return new Outcome(id, name, Result.DISMISSED, null, null, List.copyOf(handsBack));
        } catch (DomainException e) {
            // Handled: the refusal is reported in the outcome, agent by agent
            Result result = SKIPPED.contains(e.code()) ? Result.SKIPPED : Result.REFUSED;
            return new Outcome(id, name, result, e.code(), e.getMessage(), List.of());
        }
    }
}
