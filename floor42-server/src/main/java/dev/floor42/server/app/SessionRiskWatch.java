package dev.floor42.server.app;

import dev.floor42.domain.ActionId;
import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.DenialReason;
import dev.floor42.domain.Origin;
import dev.floor42.domain.RiskRaise;
import dev.floor42.domain.RiskReason;
import dev.floor42.domain.RiskSignal;
import dev.floor42.domain.SessionRisk;
import dev.floor42.domain.event.ActionEvent.ActionDenied;
import dev.floor42.domain.event.ActionEvent.ActionExecuted;
import dev.floor42.domain.event.ActionEvent.ActionFailed;
import dev.floor42.domain.event.ActionEvent.ActionRequested;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.SessionEvent.AgentSessionEnded;
import dev.floor42.domain.event.SessionEvent.AgentSessionStarted;
import dev.floor42.server.iam.IdentityAudit;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * Turns what the hook and the broker decided into a risk per open session. Detection only: it decides nothing, allows
 * nothing, stops nothing. Per event the work is constant (a map put or remove, one anchored regex, a bounded deque
 * inside {@link SessionRisk}); a raise is journaled on a forked task, never inside the subscriber, which runs under the
 * office monitor. Only REAL events count.
 */
@ApplicationScoped
public class SessionRiskWatch {

    private static final Logger LOG = Logger.getLogger(SessionRiskWatch.class);
    private static final Pattern CODE = Pattern.compile("^([A-Z_]+):");

    /** The session and tool of an action between its request and its end. */
    private record Flight(String session, String tool) {}

    private record Watched(AgentId agent, SessionRisk risk) {}

    /** A raise waiting to be journaled once the lock is released. */
    private record Pending(String session, RiskRaise raise) {}

    private final OfficeService office;
    private final MissionScheduler scheduler;
    private final java.time.Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Watched> bySession = new HashMap<>();
    private final Map<AgentId, String> byAgent = new HashMap<>();
    private final Map<ActionId, Flight> flights = new HashMap<>();

    SessionRiskWatch(OfficeService office, MissionScheduler scheduler, java.time.Clock clock) {
        this.clock = clock;
        this.office = office;
        this.scheduler = scheduler;
    }

    void listen(@Observes StartupEvent ignored) {
        // Subscribers run under the office monitor: map work only, journaling is forked.
        office.subscribe(this::react);
    }

    void react(List<EventEnvelope> events) {
        List<Pending> raised = new ArrayList<>();
        lock.lock();
        try {
            for (EventEnvelope e : events) {
                if (e.origin() != Origin.REAL) continue;
                try {
                    apply(e, raised);
                } catch (RuntimeException ex) {
                    // Handled: a bad rule must not break the hook path nor the other events; the watch decides nothing
                    LOG.warnf(
                            "Session risk: an event was skipped (%s)",
                            ex.getClass().getSimpleName());
                }
            }
        } finally {
            lock.unlock();
        }
        for (Pending p : raised) journal(p.session(), p.raise());
    }

    /** The brokered answer carried an injection marker: the agent's open session is tainted (the body is never kept). */
    public void injectionMarker(AgentId agent, ActionId action) {
        List<Pending> raised = new ArrayList<>();
        lock.lock();
        try {
            String session = byAgent.get(agent);
            if (session != null) signal(session, RiskReason.INJECTION_MARKER, clock.instant(), action, raised);
        } finally {
            lock.unlock();
        }
        for (Pending p : raised) journal(p.session(), p.raise());
    }

    int inFlight() {
        lock.lock();
        try {
            return flights.size();
        } finally {
            lock.unlock();
        }
    }

    private void apply(EventEnvelope e, List<Pending> raised) {
        Instant at = e.occurredAt();
        switch (e.event()) {
            case AgentSessionStarted s -> {
                bySession.put(s.session(), new Watched(s.agent(), new SessionRisk()));
                byAgent.put(s.agent(), s.session());
            }
            case AgentSessionEnded s -> {
                Watched w = bySession.remove(s.session());
                if (w != null) byAgent.remove(w.agent(), s.session());
            }
            case ActionRequested r -> requested(r, at, raised);
            case ActionDenied d -> {
                Flight f = flights.remove(d.action());
                if (f != null) signal(f.session(), denial(d.reason(), f.tool()), at, d.action(), raised);
            }
            case ActionFailed x -> {
                Flight f = flights.remove(x.action());
                if (f != null && broker(f.tool())) brokerFailure(f, x, at, raised);
            }
            case ActionExecuted x -> {
                Flight f = flights.remove(x.action());
                if (f != null && broker(f.tool())) {
                    signal(f.session(), RiskReason.UNTRUSTED_READ, at, x.action(), raised);
                }
            }
            default -> {}
        }
    }

    private void requested(ActionRequested r, Instant at, List<Pending> raised) {
        String session = byAgent.get(r.agent());
        if (session == null) return;
        flights.put(r.action(), new Flight(session, r.tool()));
        if (SessionRisk.ownLeash(r.tool(), r.summary())) {
            signal(session, RiskReason.OWN_LEASH, at, r.action(), raised);
        }
    }

    private void brokerFailure(Flight f, ActionFailed x, Instant at, List<Pending> raised) {
        Matcher m = CODE.matcher(x.error());
        if (m.find()) {
            RiskReason.broker(m.group(1)).ifPresent(r -> signal(f.session(), r, at, x.action(), raised));
        }
    }

    /** Null when the refusal is not the agent's probing (the director's no, a closed mission, an upstream block). */
    private static RiskReason denial(DenialReason why, String tool) {
        boolean shell = "Bash".equals(tool);
        return switch (why) {
            case POLICY -> shell ? RiskReason.COMMAND_DENIED : RiskReason.POLICY_DENIED;
            case OUTSIDE_WORKSPACE -> shell ? RiskReason.COMMAND_DENIED : RiskReason.OUTSIDE_WORKSPACE;
            case DIRECTOR, MISSION_NOT_ACTIVE, BLOCKED_UPSTREAM -> null;
        };
    }

    private static boolean broker(String tool) {
        return IdentityAudit.BROKER_TOOL.equals(tool);
    }

    private void signal(String session, RiskReason reason, Instant at, ActionId action, List<Pending> raised) {
        Watched w = bySession.get(session);
        if (w == null || reason == null) return;
        w.risk().add(new RiskSignal(reason, at, action)).ifPresent(r -> raised.add(new Pending(session, r)));
    }

    /** Forked: the journal write never happens in the subscriber nor under this class's lock. */
    void journal(String session, RiskRaise r) {
        scheduler.fork("risk-" + session, () -> {
            try {
                office.execute(c -> c.sessions()
                        .riskRaised(
                                office.context(Actor.system(), Origin.REAL),
                                session,
                                r.level(),
                                r.reasons(),
                                r.windowStart(),
                                r.actions()));
            } catch (RuntimeException ex) {
                // Handled: a lost raise is a lost signal, so it is logged at WARN; never retried
                LOG.debugf(
                        "Session risk %s not journaled (%s)",
                        session, ex.getClass().getSimpleName());
            }
        });
    }
}
