package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Company;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.PermissionRule;
import dev.floor42.domain.Priority;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * The crew of the simulated missions (the demo and the guided first mission): three agents hired when missing, and the
 * commands they and a scripted director give, every one recorded as {@link Origin#SIMULATED}.
 */
@ApplicationScoped
public class SimulatedCrew {

    static final AgentId GEOFFREY = new AgentId("geoffrey");
    static final AgentId CHRIS = new AgentId("chris");
    static final AgentId DEHREN = new AgentId("dehren");

    private final OfficeService office;

    @Inject
    public SimulatedCrew(OfficeService office) {
        this.office = office;
    }

    public void hireIfMissing() {
        hire(GEOFFREY, "Geoffrey", "Software architect", "claude-opus-5-5", "high");
        hire(CHRIS, "Chris", "Java developer", "claude-sonnet-5-5", "medium");
        hire(DEHREN, "Dehren", "Test engineer", "claude-haiku-5-5", "low");
    }

    private void hire(AgentId id, String name, String role, String model, String effort) {
        if (office.query(c -> c.staff().find(id).isPresent())) return;
        office.execute(c -> c.staff().hire(director(), id, name, role, "anthropic", model, effort, List.of()));
    }

    /** A ticket Geoffrey writes into the plan. */
    public TicketId ticket(MissionId m, String title, List<TicketId> deps, List<String> criteria) {
        return ((TicketCreated) office.execute(
                                c -> c.tickets().create(agent(GEOFFREY), m, title, "", Priority.NORMAL, deps, criteria))
                        .getFirst()
                        .event())
                .ticket();
    }

    public void allow(MissionId m, PermissionRule rule) {
        office.execute(c -> c.missions().addPermission(director(), m, rule));
    }

    public CommandContext director() {
        return office.context(Actor.director(), Origin.SIMULATED);
    }

    public CommandContext agent(AgentId id) {
        return office.context(Actor.agent(id), Origin.SIMULATED);
    }

    /**
     * Blocks the calling scenario (and only it) until {@code settled} holds, the mission ends or {@code patience} runs
     * out. A mission no longer in progress afterwards stops the scenario with {@code MISSION_NOT_ACTIVE}.
     */
    public void awaitDirector(MissionId m, Predicate<Company> settled, Duration patience)
            throws InterruptedException, TimeoutException {
        Predicate<Company> done = c ->
                settled.test(c) || c.missions().find(m).orElseThrow().status().isTerminal();
        CompletableFuture<Void> seen = new CompletableFuture<>();
        try (AutoCloseable ignored = office.subscribe(events -> {
            // Re-entrant read: subscribers run while the service lock is held.
            if (office.query(done::test)) seen.complete(null);
        })) {
            if (!office.query(done::test)) seen.get(patience.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException | TimeoutException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Waiting for the director failed", e);
        }
        requireInProgress(m);
    }

    void requireInProgress(MissionId m) {
        if (office.query(c -> c.missions().find(m).orElseThrow().status() != MissionStatus.IN_PROGRESS)) {
            throw new DomainException("MISSION_NOT_ACTIVE", m + " is no longer in progress");
        }
    }

    static void pause(Duration pace) throws InterruptedException {
        if (!pace.isZero()) Thread.sleep(pace);
    }
}
