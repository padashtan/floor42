package dev.floor42.server.iam;

import dev.floor42.server.runtime.ClaudeCodeRuntime;
import dev.floor42.server.runtime.Sandbox;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.jboss.logging.Logger;

/**
 * Whether IAM mode works, decided once at start and by the preconditions that make it safe: a sound vault key, the WSL
 * sandbox (without it the agent's shell could read the vault key), a way to tell which process owns a hook's socket,
 * and a reachable Keycloak. Anything short of {@code UP} while IAM is on blocks sessions: fail closed.
 */
@ApplicationScoped
public class IamStatus {

    private static final Logger LOG = Logger.getLogger(IamStatus.class);

    public enum Health {
        OFF,
        UP,
        UNREACHABLE,
        REFUSED_TO_START
    }

    public static final String NEEDS_SANDBOX = "IAM_NEEDS_SANDBOX";
    public static final String NEEDS_PROCESS_BINDING = "IAM_NEEDS_PROCESS_BINDING";
    public static final String API_CREDENTIAL_REFUSED = "IAM_API_CREDENTIAL_REFUSED";
    public static final String API_SECRET_MISSING = "IAM_API_SECRET_MISSING";
    public static final String VAULT_KEY_INVALID = "IAM_VAULT_KEY_INVALID";

    /** {@code code} names a refusal to start (or null); {@code reason} is the readable text, null when UP or OFF. */
    public record View(
            Health health, String code, String reason, Instant lastCheck, int failedChecks, boolean restartNeeded) {}

    private final IamSettings settings;
    private final SecretVault vault;
    private final SocketOwners owners;
    private final Jwks jwks;
    private final AgentClients clients;
    private final KeycloakClient keycloak;
    private final BooleanSupplier sandboxWorking;
    private volatile Health health = Health.OFF;
    private volatile String code;
    private volatile String reason;
    private volatile Instant lastCheck;
    private final AtomicInteger failedChecks = new AtomicInteger();

    @Inject
    IamStatus(
            IamSettings settings,
            SecretVault vault,
            SocketOwners owners,
            Jwks jwks,
            AgentClients clients,
            KeycloakClient keycloak,
            Sandbox sandbox) {
        this(settings, vault, owners, jwks, clients, keycloak, () -> sandbox.enabled() && sandbox.available());
    }

    IamStatus(
            IamSettings settings,
            SecretVault vault,
            SocketOwners owners,
            Jwks jwks,
            AgentClients clients,
            KeycloakClient keycloak,
            BooleanSupplier sandboxWorking) {
        this.settings = settings;
        this.vault = vault;
        this.owners = owners;
        this.jwks = jwks;
        this.clients = clients;
        this.keycloak = keycloak;
        this.sandboxWorking = sandboxWorking;
    }

    /** After the runtime's own start-up work (which includes the sandbox's settings being readable). */
    void onStart(@Observes @jakarta.annotation.Priority(ClaudeCodeRuntime.STARTUP_PRIORITY + 10) StartupEvent ignored) {
        start();
    }

    void start() {
        if (!settings.enabled()) {
            set(Health.OFF, null, null, true);
            return;
        }
        Optional<String> keyProblem = vault.keyProblem();
        if (keyProblem.isPresent()) {
            set(Health.REFUSED_TO_START, VAULT_KEY_INVALID, keyProblem.get(), false);
        } else if (!sandboxWorking.getAsBoolean()) {
            set(
                    Health.REFUSED_TO_START,
                    NEEDS_SANDBOX,
                    "IAM mode needs the WSL sandbox turned on and working: without it an agent's shell could read the"
                            + " vault key",
                    false);
        } else if (!owners.supported()) {
            set(
                    Health.REFUSED_TO_START,
                    NEEDS_PROCESS_BINDING,
                    "process binding unavailable on " + System.getProperty("os.name", "this system"),
                    false);
        } else {
            connect();
        }
        if (health != Health.UP) LOG.warn("IAM mode is on but not usable: " + health + ", " + reason);
    }

    /** The issuer answers (the key cache is warm for the hook path) and every agent on staff has its client. */
    private void connect() {
        try {
            jwks.refresh();
            if (!jwks.reachable()) {
                set(Health.UNREACHABLE, null, "Keycloak does not answer at " + settings.issuer(), false);
                return;
            }
            try {
                keycloak.probeIntrospection();
            } catch (IdpRefusedException e) {
                if (!e.aboutCaller()) throw e;
                // Handled: told at start, not at the first session; the watch keeps asking and clears it when fixed
                set(Health.REFUSED_TO_START, API_CREDENTIAL_REFUSED, API_CREDENTIAL_REASON, false);
                return;
            } catch (ApiSecretMissingException e) {
                // Handled: told at start; the watch keeps asking and clears it when the secret is saved
                set(Health.REFUSED_TO_START, API_SECRET_MISSING, API_SECRET_REASON, false);
                return;
            }
            clients.ensureAll();
            set(Health.UP, null, null, true);
        } catch (RuntimeException e) {
            // Handled: reported as the state, sessions are refused until a restart finds Keycloak well
            set(Health.UNREACHABLE, null, "Keycloak is not usable: " + e.getMessage(), false);
        }
    }

    private static final String API_CREDENTIAL_REASON =
            "Keycloak refuses Floor42's floor42-api credential: re-enter its secret in Settings";

    private static final String API_SECRET_REASON =
            "IAM mode has no floor42-api secret: enter it in Settings, no session can be checked without it";

    /** The status is stuck on the floor42-api credential (refused or absent): only a good probe frees it. */
    static boolean apiBlocked(String code) {
        return API_CREDENTIAL_REFUSED.equals(code) || API_SECRET_MISSING.equals(code);
    }

    private synchronized void set(Health h, String c, String r, boolean ok) {
        health = h;
        code = c;
        reason = r;
        lastCheck = Instant.now();
        if (ok) failedChecks.set(0);
        else failedChecks.incrementAndGet();
    }

    /** A background check that got no answer: new sessions are blocked at once, running ones after the second. */
    void checkFailed() {
        checkFailed("Keycloak did not answer the background check");
    }

    /** A pass that ran out of its time: the same failure, told as what it is. */
    void checkTimedOut() {
        checkFailed("The identity check ran out of time before it asked every session");
    }

    private synchronized void checkFailed(String why) {
        if (health != Health.UP && health != Health.UNREACHABLE && !apiBlocked(code)) return;
        health = Health.UNREACHABLE;
        code = null;
        reason = why;
        lastCheck = Instant.now();
        failedChecks.incrementAndGet();
    }

    /**
     * Keycloak answers but refuses Floor42's own floor42-api credential: a failure of the status, like an unreachable
     * Keycloak (new sessions blocked at once, running ones after the second miss), never a verdict on any session.
     */
    void credentialRefused() {
        if (health != Health.UP && health != Health.UNREACHABLE && !apiBlocked(code)) return;
        set(Health.REFUSED_TO_START, API_CREDENTIAL_REFUSED, API_CREDENTIAL_REASON, false);
    }

    /** IAM is on and the vault holds no floor42-api secret: a failure of the status, never UP, never a revocation. */
    void apiSecretMissing() {
        if (health != Health.UP && health != Health.UNREACHABLE && !apiBlocked(code)) return;
        set(Health.REFUSED_TO_START, API_SECRET_MISSING, API_SECRET_REASON, false);
    }

    /** A background check that got its answers: a Keycloak that was unreachable is well again. */
    void checkOk() {
        if (health == Health.UNREACHABLE || apiBlocked(code)) set(Health.UP, null, null, true);
        else if (health == Health.UP) lastCheck = Instant.now();
    }

    public View view() {
        return new View(health, code, reason, lastCheck, failedChecks.get(), settings.restartNeeded());
    }

    /** The reason sessions cannot start (IAM is on and not UP); empty when IAM is off or well. */
    public Optional<String> blocksSessions() {
        if (!settings.enabled() || health == Health.UP) return Optional.empty();
        return Optional.of(reason == null ? "IAM mode is not ready" : reason);
    }
}
