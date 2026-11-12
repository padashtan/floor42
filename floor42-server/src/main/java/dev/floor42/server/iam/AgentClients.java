package dev.floor42.server.iam;

import dev.floor42.domain.AgentId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * The agents' Keycloak clients over their lifetime: one per agent on staff, made at hire (and at start, for the ones
 * hired before IAM was turned on), disabled at dismissal. {@link #revoked()} is Floor42's own, immediate answer to "is
 * this agent cut off?": it is read on every hook and MCP call and set before Keycloak is even asked, so a dismissal
 * takes effect at once even when Keycloak is down. It is in memory: after a restart a dismissed agent is no longer on
 * staff, hence not given a token again. A dismissal whose disable call failed is retried by the background check.
 * Every method is a no-op while IAM mode is off.
 */
@ApplicationScoped
public class AgentClients {

    private static final Logger LOG = Logger.getLogger(AgentClients.class);

    private final KeycloakClient keycloak;
    private final IamSettings settings;
    private final Supplier<Collection<AgentId>> staff;
    private final Set<AgentId> revoked = ConcurrentHashMap.newKeySet();
    private final Set<AgentId> disablePending = ConcurrentHashMap.newKeySet();

    @Inject
    AgentClients(KeycloakClient keycloak, IamSettings settings, OfficeFacts office) {
        this(keycloak, settings, office::activeStaff);
    }

    AgentClients(KeycloakClient keycloak, IamSettings settings, Supplier<Collection<AgentId>> staff) {
        this.keycloak = keycloak;
        this.settings = settings;
        this.staff = staff;
    }

    /** Every agent on staff has a client with a working secret; the first failure stops the pass. */
    public void ensureAll() {
        if (!settings.enabled()) return;
        for (AgentId a : staff.get()) ensure(a);
    }

    public KeycloakClient.AgentClient ensure(AgentId agent) {
        return keycloak.ensureAgentClient(agent, settings.maxTokenHours() * 3600);
    }

    /** A hire: the client is made now, so the agent's first session finds it. A failure is logged, startup heals it. */
    public void hired(AgentId agent) {
        if (!settings.enabled()) return;
        try {
            ensure(agent);
        } catch (RuntimeException e) {
            // Handled: the next start (or the agent's first launch) makes it again
            LOG.warn("The Keycloak client of a new agent could not be made: " + e.getMessage());
        }
    }

    /** First of the two steps of a dismissal, synchronous and local: no hook of this agent passes from now on. */
    public void revokeLocally(AgentId agent) {
        if (settings.enabled()) revoked.add(agent);
    }

    /** Second step: Keycloak disables the client, so no new token can be exchanged and the live ones turn inactive. */
    public void dismissed(AgentId agent) {
        if (!settings.enabled()) return;
        revokeLocally(agent);
        try {
            disable(agent);
        } catch (RuntimeException e) {
            // Handled: the local revocation already holds; only Keycloak's own side is late
            disablePending.add(agent);
            LOG.warn("The Keycloak client of a dismissed agent could not be disabled: " + e.getMessage());
        }
    }

    /** Dismissed agents whose client Keycloak still has enabled: disabled again; the failures stay for next time. */
    public void retryDisables() {
        for (AgentId a : Set.copyOf(disablePending)) {
            try {
                disable(a);
                disablePending.remove(a);
            } catch (RuntimeException e) {
                // Handled: kept pending, the next background check tries again
                LOG.warn("The Keycloak client of a dismissed agent is still not disabled: " + e.getMessage());
            }
        }
    }

    public void disable(AgentId agent) {
        keycloak.setEnabled(agent, false);
    }

    public void enable(AgentId agent) {
        keycloak.setEnabled(agent, true);
        revoked.remove(agent);
    }

    public void rotate(AgentId agent) {
        keycloak.rotateSecret(agent);
    }

    public Set<AgentId> revoked() {
        return Set.copyOf(revoked);
    }

    /** The director's revoke: the same two steps as a dismissal, but the Keycloak failure is the director's to see. */
    public void revoke(AgentId agent) {
        revokeLocally(agent);
        disable(agent);
    }

    public boolean onStaff(AgentId agent) {
        return staff.get().contains(agent);
    }

    /** What the identity screen lists: the agents on staff and what Keycloak holds for each. */
    public List<View> views() {
        return staff.get().stream()
                .map(a -> {
                    var c = keycloak.find(a);
                    return new View(
                            a,
                            c.map(KeycloakClient.AgentClient::clientId).orElse(null),
                            c.map(KeycloakClient.AgentClient::serviceAccountId).orElse(null),
                            c.map(KeycloakClient.AgentClient::enabled).orElse(false),
                            revoked.contains(a));
                })
                .toList();
    }

    /** One agent as the identity screen shows it. */
    public record View(AgentId agent, String clientId, String serviceAccountId, boolean enabled, boolean revoked) {}
}
