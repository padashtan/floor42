package dev.floor42.server.iam;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.jboss.logging.Logger;

/**
 * The check behind the hook path, every 60 s on its own daemon thread (never a hook, never the event loop): fresh keys,
 * every live session token asked of Keycloak, revocations and disables Keycloak missed retried. A Keycloak that does
 * not answer blocks new sessions at the first miss and refuses running ones from the second (fail closed).
 */
@ApplicationScoped
public class IdentityWatch {

    public static final Duration PERIOD = Duration.ofSeconds(60);
    /** A pass stops asking after this; the sessions it did not reach are asked first by the next one. */
    public static final Duration DEADLINE = Duration.ofSeconds(50);

    public static final int FAILURES_TO_REFUSE = IamSessions.FAILED_CHECKS_LIMIT;

    private static final Logger LOG = Logger.getLogger(IdentityWatch.class);

    private final IamSettings settings;
    private final IamStatus status;
    private final Jwks jwks;
    private final KeycloakClient keycloak;
    private final IamSessions sessions;
    private final Delegations delegations;
    private final AgentClients clients;
    private final Event<IamRecovered> recovered;
    private ScheduledExecutorService timer;
    private volatile LongSupplier nanos = System::nanoTime;
    private final Set<String> behind = ConcurrentHashMap.newKeySet();

    /** For tests: the clock the deadline reads. */
    void clock(LongSupplier value) {
        nanos = value;
    }

    /** For tests: the launches the last pass did not reach. */
    Set<String> behind() {
        return Set.copyOf(behind);
    }

    @Inject
    IdentityWatch(
            IamSettings settings,
            IamStatus status,
            Jwks jwks,
            KeycloakClient keycloak,
            IamSessions sessions,
            Delegations delegations,
            AgentClients clients,
            Event<IamRecovered> recovered) {
        this.settings = settings;
        this.status = status;
        this.jwks = jwks;
        this.keycloak = keycloak;
        this.sessions = sessions;
        this.delegations = delegations;
        this.clients = clients;
        this.recovered = recovered;
    }

    void start(@Observes StartupEvent ignored) {
        if (!settings.enabled()) return;
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "identity-watch");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::safeCheck, PERIOD.toSeconds(), PERIOD.toSeconds(), TimeUnit.SECONDS);
    }

    void stop(@Observes ShutdownEvent ignored) {
        if (timer != null) timer.shutdownNow();
    }

    private void safeCheck() {
        try {
            checkNow();
        } catch (RuntimeException e) {
            // Handled: one bad pass must not end the schedule; the next one runs in 60 s
            LOG.warn("The identity check failed: " + e.getClass().getSimpleName());
        }
    }

    /** One pass, as the timer runs it; tests call it directly. */
    public void checkNow() {
        if (!settings.enabled()) return;
        sessions.stopRevoked();
        Pass pass = introspectAll();
        if (pass != Pass.OK) {
            if (pass == Pass.CREDENTIAL_REFUSED) status.credentialRefused();
            else if (pass == Pass.API_SECRET_MISSING) status.apiSecretMissing();
            else if (pass == Pass.OUT_OF_TIME) status.checkTimedOut();
            else status.checkFailed();
            return;
        }
        boolean wasBlocked = status.view().health() != IamStatus.Health.UP;
        status.checkOk();
        // Sessions that could not start while Keycloak was away wait for a nudge; nothing else would send one
        if (wasBlocked && status.view().health() == IamStatus.Health.UP) recovered.fire(new IamRecovered());
        delegations.retryRevocations();
        clients.retryDisables();
    }

    private enum Pass {
        OK,
        NO_ANSWER,
        CREDENTIAL_REFUSED,
        API_SECRET_MISSING,
        OUT_OF_TIME
    }

    /**
     * Only Keycloak's own 200 {@code active:false} ends a session. A refusal is never a verdict on a token (a bad token
     * is a 200 {@code active:false}): a 401/403 or invalid_client is Floor42's floor42-api credential refused (the
     * pass stops, the status says so); any other 4xx counts the pass as failed, the session is left alone. A pass that
     * reaches {@link #DEADLINE} counts as failed too: the sessions it did not ask about are asked first next time and
     * are never ended on that account.
     */
    private Pass introspectAll() {
        jwks.refresh();
        if (!jwks.reachable()) return Pass.NO_ANSWER;
        try {
            Pass probe = probe();
            return probe != null ? probe : introspectSessions();
        } catch (IdpUnavailableException e) {
            // Handled: counted as a failed check by the caller
            return Pass.NO_ANSWER;
        }
    }

    /** The caller's own credential is checked every pass, sessions or none: only a passing probe clears a refusal. */
    private Pass probe() {
        try {
            keycloak.probeIntrospection();
            return null;
        } catch (IdpRefusedException r) {
            // Handled: the status says it; a refusal not about the caller counts the pass as failed
            LOG.warn("Keycloak refused the introspection probe: " + r.getMessage());
            return r.aboutCaller() ? Pass.CREDENTIAL_REFUSED : Pass.NO_ANSWER;
        } catch (ApiSecretMissingException m) {
            // Handled: the status says it; the sessions are left alone
            return Pass.API_SECRET_MISSING;
        }
    }

    private Pass introspectSessions() {
        boolean refusedOne = false;
        boolean late = false;
        long end = nanos.getAsLong() + DEADLINE.toNanos();
        var live = new ArrayList<>(sessions.liveTokens().entrySet());
        behind.retainAll(live.stream().map(e -> e.getKey().launch()).toList());
        live.sort(Comparator.comparing(e -> !behind.contains(e.getKey().launch())));
        for (var e : live) {
            String launch = e.getKey().launch();
            if (nanos.getAsLong() - end >= 0) {
                behind.add(launch);
                late = true;
                continue;
            }
            behind.remove(launch);
            try {
                if (!keycloak.active(e.getValue())) sessions.markInactive(e.getKey());
            } catch (IdpRefusedException r) {
                // Handled: counted below; the status is told, the session is not ended on an unproven cause
                LOG.warn("Keycloak refused an introspection: " + r.getMessage());
                if (r.aboutCaller()) return Pass.CREDENTIAL_REFUSED;
                refusedOne = true;
            }
        }
        if (late) return Pass.OUT_OF_TIME;
        return refusedOne ? Pass.NO_ANSWER : Pass.OK;
    }
}
