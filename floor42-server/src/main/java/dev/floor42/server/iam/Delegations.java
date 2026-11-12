package dev.floor42.server.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.floor42.domain.MissionId;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.runtime.ClaudeCodeRuntime;
import dev.floor42.server.security.OwnerOnly;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The director's delegation to each mission, taken at plan approval after a password step-up: one offline refresh
 * token per mission (its own Keycloak offline session), kept only in the vault; {@code delegations.json} holds metadata
 * and never a token. It ends with its mission: {@link #revokeLocally} flips the flag at once (every later
 * {@link #subjectTokenFor} and {@link #blocks} sees it, whatever Keycloak answers), {@link #revokeRemote} then revokes
 * the token at Keycloak (RFC 7009) and deletes the vault entry. A revoke that Keycloak did not take is retried by
 * {@link #retryRevocations}; until then the entry stays encrypted in the vault, unreachable through this class.
 * Every method that reads or changes a delegation is serialised on this object, so a revoke racing a session start
 * either comes first (the start is refused) or the start already holds a 300 s access token.
 */
@ApplicationScoped
public class Delegations {

    private static final Logger LOG = Logger.getLogger(Delegations.class);
    private static final String DESK = "floor42-desk";
    private static final Pattern RESOURCE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    static final int MAX_RESOURCES = 20;
    /** Keycloak's default offline idle timeout: a longer-lived delegation would not be refreshable anyway. */
    public static final Duration LIFETIME = Duration.ofDays(30);

    private final Path file;
    private final SecretVault vault;
    private final KeycloakClient keycloak;
    private final BooleanSupplier enabled;
    private final Supplier<Optional<String>> keycloakBlocks;
    private final DirectorTokens directorTokens;
    private final ObjectMapper json;
    private final Map<MissionId, MissionDelegation> held = new LinkedHashMap<>();
    /** In memory: sessions die with the daemon, so a restart loses nothing that matters. */
    private final Map<MissionId, Instant> revokedAt = new java.util.concurrent.ConcurrentHashMap<>();

    Clock clock = Clock.systemUTC();

    @Inject
    Delegations(
            @ConfigProperty(name = "floor42.data-dir") Path dataDir,
            SecretVault vault,
            KeycloakClient keycloak,
            IamSettings settings,
            IamStatus status,
            DirectorTokens directorTokens,
            ObjectMapper json) {
        this(
                dataDir.resolve("iam").resolve("delegations.json"),
                vault,
                keycloak,
                settings::enabled,
                status::blocksSessions,
                directorTokens,
                json);
    }

    Delegations(
            Path file,
            SecretVault vault,
            KeycloakClient keycloak,
            BooleanSupplier enabled,
            Supplier<Optional<String>> keycloakBlocks,
            DirectorTokens directorTokens,
            ObjectMapper json) {
        this.file = file;
        this.vault = vault;
        this.keycloak = keycloak;
        this.enabled = enabled;
        this.keycloakBlocks = keycloakBlocks;
        this.directorTokens = directorTokens;
        this.json = json;
        load();
    }

    public static String entryOf(MissionId m) {
        return "delegation-" + m.toString().toLowerCase(Locale.ROOT);
    }

    /** The resources the director lets the mission's agents reach: a short list of catalogue names, or none. */
    public static List<String> cleanResources(List<String> resources) {
        if (resources == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String r : resources) {
            String name = r == null ? "" : r.strip();
            if (!RESOURCE.matcher(name).matches()) throw new IllegalArgumentException("Not a resource name: " + name);
            if (!out.contains(name)) out.add(name);
        }
        if (out.size() > MAX_RESOURCES) throw new IllegalArgumentException("At most " + MAX_RESOURCES + " resources");
        return List.copyOf(out);
    }

    /** Stores the mission's offline token (vault only) and its metadata. A delegation it replaces is revoked. */
    public synchronized MissionDelegation grant(MissionId m, Tokens offline, List<String> resources) {
        if (offline == null || offline.refreshToken() == null)
            throw new IllegalArgumentException("A delegation needs an offline refresh token");
        List<String> clean = cleanResources(resources);
        Optional<String> replaced = held.containsKey(m) && !held.get(m).revoked() ? token(m) : Optional.empty();
        Instant now = clock.instant();
        vault.put(entryOf(m), offline.refreshToken().getBytes(StandardCharsets.UTF_8));
        MissionDelegation d = new MissionDelegation(m, entryOf(m), now, now.plus(LIFETIME), clean, false);
        MissionDelegation before = held.put(m, d);
        try {
            save();
        } catch (UncheckedIOException e) {
            // Handled: nothing is granted, and no token stays in the vault.
            if (before == null) held.remove(m);
            else held.put(m, before);
            publishRevoked();
            vault.delete(entryOf(m));
            throw e;
        }
        replaced.ifPresent(this::revokeAtKeycloak);
        return d;
    }

    public synchronized Optional<MissionDelegation> find(MissionId m) {
        return Optional.ofNullable(held.get(m));
    }

    /**
     * A fresh director access token for this mission, from its own offline token (refresh through the public desk
     * client); the stored token is replaced when Keycloak rotates it. Refused once the delegation is revoked or
     * expired, and when Keycloak says the grant is gone (then the delegation is revoked here too).
     */
    public synchronized String subjectTokenFor(MissionId m) {
        MissionDelegation d = held.get(m);
        if (d == null) throw new IdpRefusedException("No delegation for " + m);
        if (d.revoked()) throw new IdpRefusedException("The delegation of " + m + " is revoked");
        if (!d.expiresAt().isAfter(clock.instant()))
            throw new IdpRefusedException("The delegation of " + m + " expired");
        String stored = token(m).orElseThrow(() -> new IdpRefusedException("The delegation of " + m + " has no token"));
        try {
            Tokens fresh = keycloak.refresh(stored, DESK);
            if (fresh.refreshToken() != null && !fresh.refreshToken().equals(stored))
                vault.put(d.vaultEntry(), fresh.refreshToken().getBytes(StandardCharsets.UTF_8));
            return fresh.accessToken();
        } catch (IdpRefusedException e) {
            // Handled: Keycloak ended the offline session (revoked elsewhere, expired): over here too, then rethrown.
            markRevoked(m);
            throw e;
        }
    }

    /** The missions whose delegation is not revoked. */
    public synchronized List<MissionId> liveMissions() {
        return held.values().stream()
                .filter(d -> !d.revoked())
                .map(MissionDelegation::mission)
                .toList();
    }

    /** Both steps of the end of a delegation; used when the caller can wait for Keycloak. */
    public void revoke(MissionId m) {
        revokeLocally(m);
        revokeRemote(m);
    }

    /** Step one, local and immediate: from here on no token is given for the mission. */
    public synchronized void revokeLocally(MissionId m) {
        markRevoked(m);
    }

    /** Step two: Keycloak revokes the offline token, then the vault entry goes. Unreachable Keycloak: kept, retried. */
    public void revokeRemote(MissionId m) {
        Optional<String> stored;
        synchronized (this) {
            MissionDelegation d = held.get(m);
            if (d == null || !d.revoked()) return;
            stored = token(m);
        }
        if (stored.isEmpty() || !revokeAtKeycloak(stored.get())) return;
        synchronized (this) {
            vault.delete(entryOf(m));
        }
    }

    /** Once Keycloak is known usable at start, the revocations it missed are retried. The background check retries every 60 s. */
    void onStart(@Observes @Priority(ClaudeCodeRuntime.STARTUP_PRIORITY + 20) StartupEvent ignored) {
        retryAtStart();
    }

    void retryAtStart() {
        if (enabled.getAsBoolean() && keycloakBlocks.get().isEmpty()) retryRevocations();
    }

    /** Revocations Keycloak did not take yet (it was down or refused): tried again. */
    public void retryRevocations() {
        List<MissionId> pending;
        synchronized (this) {
            pending = held.values().stream()
                    .filter(d -> d.revoked() && vault.get(d.vaultEntry()).isPresent())
                    .map(MissionDelegation::mission)
                    .toList();
        }
        pending.forEach(this::revokeRemote);
    }

    /**
     * Why the mission's agents must not start now (no delegation although the plan is approved, revoked, expired,
     * Keycloak unusable), or empty; always empty while IAM mode is off. Before the plan is approved there is no
     * delegation yet and none is needed (planning and committees use the director's sign-in).
     */
    public synchronized Optional<String> blocks(MissionId m, boolean pastApproval) {
        if (!enabled.getAsBoolean()) return Optional.empty();
        MissionDelegation d = held.get(m);
        if (d == null) {
            return pastApproval
                    ? Optional.of("no delegation: approve the plan again with a Keycloak sign-in")
                    : keycloakBlocks.get();
        }
        if (d.revoked()) return Optional.of("delegation revoked");
        if (!d.expiresAt().isAfter(clock.instant())) return Optional.of("delegation expired");
        return keycloakBlocks.get();
    }
    /** A director access token for sessions the daemon plans itself, from a live Keycloak sign-in; else empty. */
    public Optional<String> forPlanning() {
        return directorTokens.any().map(Tokens::accessToken);
    }

    private void markRevoked(MissionId m) {
        MissionDelegation d = held.get(m);
        if (d == null || d.revoked()) return;
        revokedAt.put(m, clock.instant());
        // In memory first: the refusal is immediate even if the file cannot be written.
        held.put(m, new MissionDelegation(m, d.vaultEntry(), d.grantedAt(), d.expiresAt(), d.resources(), true));
        try {
            save();
        } catch (UncheckedIOException e) {
            // Handled: the revoked flag stays in memory (and is refused at once); the file is saved at the next change.
            LOG.warn("The revocation of " + m + " is held in memory only: " + e.getMessage());
        }
    }

    private boolean revokeAtKeycloak(String refreshToken) {
        try {
            keycloak.revoke(refreshToken);
            return true;
        } catch (IdpRefusedException | IdpUnavailableException e) {
            // Handled: only a 2xx counts (RFC 7009 answers 200 for an unknown token); a refusal is kept in the vault
            // and retried like an outage.
            LOG.warn("A delegation could not be revoked at Keycloak yet: " + e.getMessage());
            return false;
        }
    }

    private Optional<String> token(MissionId m) {
        return vault.get(entryOf(m)).map(b -> new String(b, StandardCharsets.UTF_8));
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            for (JsonNode n : json.readTree(Files.readString(file, StandardCharsets.UTF_8))) {
                List<String> resources = new ArrayList<>();
                n.path("resources").forEach(r -> resources.add(r.asText()));
                MissionId m = new MissionId(n.path("mission").asText());
                held.put(
                        m,
                        new MissionDelegation(
                                m,
                                entryOf(m),
                                Instant.parse(n.path("grantedAt").asText()),
                                Instant.parse(n.path("expiresAt").asText()),
                                List.copyOf(resources),
                                n.path("revoked").asBoolean(true)));
            }
        } catch (IOException | RuntimeException e) {
            // Handled: an unreadable file means no delegation is trusted; every mission waits for a new step-up.
            LOG.warn("delegations.json is unreadable, no delegation is kept: " + e.getMessage());
        }
        publishRevoked();
    }

    /** Missions whose delegation is revoked, readable without any lock: the hook path never waits on a refresh. */
    private volatile Set<MissionId> revokedView = Set.of();

    /** In memory only, no lock: true from the instant the delegation is marked revoked. */
    public boolean isRevoked(MissionId m) {
        return revokedView.contains(m);
    }

    /** True when the mission's delegation was revoked, or the mission closed to work, at or after {@code since}. */
    public boolean revokedSince(MissionId m, Instant since) {
        Instant at = revokedAt.get(m);
        Instant closed = closedAt.get(m);
        return (at != null && !at.isBefore(since)) || (closed != null && !closed.isBefore(since));
    }

    /** When missions completed and kept their delegation for the retrospective only (memory: sessions do not survive). */
    private final Map<MissionId, Instant> closedAt = new java.util.concurrent.ConcurrentHashMap<>();

    /** The mission is completed: sessions that were running are cut, and only a retrospective may use the delegation. */
    public void closeToWork(MissionId m) {
        closedAt.put(m, clock.instant());
    }

    /** True once the mission is completed: no resource token for the agents' tools any more. */
    public boolean isClosed(MissionId m) {
        return closedAt.containsKey(m);
    }

    private void publishRevoked() {
        revokedView = held.values().stream()
                .filter(MissionDelegation::revoked)
                .map(MissionDelegation::mission)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void save() {
        publishRevoked();
        ArrayNode all = json.createArrayNode();
        for (MissionDelegation d : held.values()) {
            ObjectNode n = all.addObject();
            n.put("mission", d.mission().toString());
            n.put("grantedAt", d.grantedAt().toString());
            n.put("expiresAt", d.expiresAt().toString());
            n.put("revoked", d.revoked());
            ArrayNode r = n.putArray("resources");
            d.resources().forEach(r::add);
        }
        try {
            Files.createDirectories(file.getParent());
            OwnerOnly.restrict(file.getParent());
            Path tmp = file.resolveSibling("delegations.json.tmp");
            Files.write(tmp, json.writeValueAsBytes(all));
            OwnerOnly.restrict(tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save the delegations", e);
        }
    }
}
