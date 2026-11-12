package dev.floor42.server.iam;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.server.iam.AgentTokenVerifier.Ok;
import dev.floor42.server.iam.AgentTokenVerifier.Refused;
import dev.floor42.server.iam.SessionIdentity.Minted;
import dev.floor42.server.runtime.AgentRuntime.Governor;
import dev.floor42.server.runtime.HookRegistry.Accepted;
import dev.floor42.server.runtime.HookRegistry.Authentication;
import dev.floor42.server.runtime.RunningSessions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The live sessions of IAM mode and the checks a hook or MCP call passes: the token verified locally, then compared
 * with Floor42's own record of the session (the {@code floor42} claim is a self-assertion of the agent's client, never
 * trusted alone), then revocation, then the process: the socket's owner must be the very process Floor42 launched. A
 * token that fails any check reaches no governor. Nothing here calls the network.
 */
@ApplicationScoped
public class IamSessions {

    static final Duration BIND_WAIT = Duration.ofSeconds(2);
    /** Two failed background checks (about 2 minutes) and running sessions are refused (the background check feeds the count). */
    static final int FAILED_CHECKS_LIMIT = 2;

    /** One live session: its governor, what Floor42 expects its token to say, and its process once started. */
    record Live(
            Governor governor,
            SessionClaims claims,
            String jti,
            String token,
            CompletableFuture<Long> pid,
            Instant since) {}

    private final Map<String, Live> byLaunch = new ConcurrentHashMap<>();
    /** Launches whose token Keycloak reported inactive: refused for good, whatever Floor42's own records say. */
    private final Set<String> inactive = ConcurrentHashMap.newKeySet();

    private final AgentTokenVerifier verifier;
    private final SocketOwners owners;
    private final Supplier<Set<AgentId>> revokedAgents;
    private final BiPredicate<MissionId, Instant> delegationRevoked;
    private final IntSupplier failedChecks;
    private final java.util.function.BooleanSupplier credentialRefused;
    private final Consumer<SessionClaims> stop;

    @Inject
    IamSessions(
            AgentTokenVerifier verifier,
            SocketOwners owners,
            AgentClients clients,
            Delegations delegations,
            IamStatus status,
            RunningSessions running) {
        this(
                verifier,
                owners,
                clients::revoked,
                delegations::revokedSince,
                () -> status.view().failedChecks(),
                () -> IamStatus.apiBlocked(status.view().code()),
                c -> running.stop(c.agent(), "its identity was revoked"));
    }

    public IamSessions(
            AgentTokenVerifier verifier,
            SocketOwners owners,
            Supplier<Set<AgentId>> revokedAgents,
            BiPredicate<MissionId, Instant> delegationRevoked,
            IntSupplier failedChecks,
            Consumer<SessionClaims> stop) {
        this(verifier, owners, revokedAgents, delegationRevoked, failedChecks, () -> false, stop);
    }

    public IamSessions(
            AgentTokenVerifier verifier,
            SocketOwners owners,
            Supplier<Set<AgentId>> revokedAgents,
            BiPredicate<MissionId, Instant> delegationRevoked,
            IntSupplier failedChecks,
            java.util.function.BooleanSupplier credentialRefused,
            Consumer<SessionClaims> stop) {
        this.credentialRefused = credentialRefused;
        this.verifier = verifier;
        this.owners = owners;
        this.revokedAgents = revokedAgents;
        this.delegationRevoked = delegationRevoked;
        this.failedChecks = failedChecks;
        this.stop = stop;
    }

    public String register(Governor governor, SessionClaims claims, Minted minted) {
        byLaunch.put(
                claims.launch(),
                new Live(governor, claims, minted.jti(), minted.token(), new CompletableFuture<>(), minted.since()));
        return minted.token();
    }

    /** The launched process, from {@code Process.pid()} in memory: the only pid the session's calls may come from. */
    public void bindProcess(String token, long pid) {
        byLaunch.values().stream()
                .filter(l -> l.token().equals(token))
                .forEach(l -> l.pid().complete(pid));
    }

    /** What the screen may show of a live session's token: decoded header and claims, 8 characters of the signature. */
    public record Shown(
            String launch,
            SessionClaims claims,
            String jti,
            Map<String, Object> header,
            Map<String, Object> payload,
            String signaturePrefix) {
        public Shown {
            header = header == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(header));
            payload = payload == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }
    }

    public java.util.List<Shown> shown() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var type = new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {};
        return byLaunch.values().stream()
                .map(l -> {
                    var parsed = Jwts.parse(l.token());
                    return new Shown(
                            l.claims().launch(),
                            l.claims(),
                            l.jti(),
                            mapper.convertValue(parsed.header(), type),
                            mapper.convertValue(parsed.claims(), type),
                            Jwts.shortSignature(l.token()));
                })
                .toList();
    }

    /** The process a session is bound to, once launched. */
    public OptionalLong boundPid(String launch) {
        Live l = byLaunch.get(launch);
        return l == null || !l.pid().isDone() || l.pid().isCompletedExceptionally()
                ? OptionalLong.empty()
                : OptionalLong.of(l.pid().join());
    }

    public void unregister(String token) {
        byLaunch.values().removeIf(l -> {
            boolean mine = l.token().equals(token);
            if (mine) inactive.remove(l.claims().launch());
            return mine;
        });
    }

    /** The live session tokens, for the background introspection. Never log them. */
    Map<SessionClaims, String> liveTokens() {
        Map<SessionClaims, String> out = new java.util.HashMap<>();
        byLaunch.values().forEach(l -> out.put(l.claims(), l.token()));
        return out;
    }

    /** Keycloak says this session's token is no longer active: refused from now on and the session stopped. */
    void markInactive(SessionClaims claims) {
        if (inactive.add(claims.launch())) stop.accept(claims);
    }

    /** The governor of a verified, live session; the process is not checked (the access guard's question). */
    public Optional<Governor> governorOf(String jwt) {
        return jwt != null && verifier.verify(jwt) instanceof Ok ok ? live(ok).map(Live::governor) : Optional.empty();
    }

    private Optional<Live> live(Ok ok) {
        return Optional.ofNullable(byLaunch.get(ok.claims().launch()))
                .filter(l -> l.jti().equals(ok.jti()));
    }

    public Authentication authenticate(String jwt, InetSocketAddress remote, InetSocketAddress local) {
        if (jwt == null) return refused(TokenRefusal.MALFORMED, null, null, null);
        var verified = verifier.verify(jwt);
        if (verified instanceof Refused r) return refused(r.reason(), null, r.jti(), null);
        Ok ok = (Ok) verified;
        Live live = byLaunch.get(ok.claims().launch());
        String launch = ok.claims().launch();
        if (live == null) return refused(TokenRefusal.SESSION_ENDED, null, ok.jti(), launch);
        TokenRefusal bad = compared(ok, live);
        if (bad == null) bad = revocation(live);
        if (bad == TokenRefusal.REVOKED) stop.accept(live.claims());
        if (bad == null) bad = process(live, remote, local);
        return bad == null
                ? new Accepted(live.governor(), ok.claims())
                : refused(bad, live.governor(), ok.jti(), launch);
    }

    private static Authentication refused(TokenRefusal why, Governor g, String jti, String launch) {
        return new dev.floor42.server.runtime.HookRegistry.Refused(why, g, jti, launch);
    }

    /** What the token says against what Floor42 itself knows of the session. */
    private static TokenRefusal compared(Ok ok, Live live) {
        SessionClaims said = ok.claims();
        SessionClaims known = live.claims();
        if (!AgentTokenVerifier.onlyOurAudience(ok.payload().path("aud"))) return TokenRefusal.WRONG_AUDIENCE;
        if (!live.jti().equals(ok.jti())) return TokenRefusal.SESSION_ENDED;
        if (!said.agent().equals(known.agent())
                || !ok.azp().equals("floor42-agent-" + known.agent().value())) return TokenRefusal.WRONG_AGENT;
        if (!said.mission().equals(known.mission())) return TokenRefusal.WRONG_MISSION;
        return said.ticket().equals(known.ticket()) ? null : TokenRefusal.WRONG_TICKET;
    }

    private TokenRefusal revocation(Live live) {
        SessionClaims known = live.claims();
        if (inactive.contains(known.launch())
                || revokedAgents.get().contains(known.agent())
                || delegationRevoked.test(known.mission(), live.since())) return TokenRefusal.REVOKED;
        if (failedChecks.getAsInt() < FAILED_CHECKS_LIMIT) return null;
        return credentialRefused.getAsBoolean() ? TokenRefusal.KEYCLOAK_REFUSED : TokenRefusal.KEYCLOAK_UNREACHABLE;
    }

    private TokenRefusal process(Live live, InetSocketAddress remote, InetSocketAddress local) {
        try {
            long bound = live.pid().get(BIND_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            OptionalLong owner = remote == null || local == null ? OptionalLong.empty() : owners.owner(remote, local);
            return owner.isPresent() && owner.getAsLong() == bound ? null : TokenRefusal.WRONG_PROCESS;
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
            // Handled: the process was not bound in time; a caller that cannot be tied to it is refused
            return TokenRefusal.WRONG_PROCESS;
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept for the caller; refused meanwhile
            Thread.currentThread().interrupt();
            return TokenRefusal.WRONG_PROCESS;
        }
    }

    /** Stops every live session whose agent or delegation was revoked, without waiting for its next call. */
    public int stopRevoked() {
        int stopped = 0;
        for (Live l : byLaunch.values()) {
            if (revocation(l) != TokenRefusal.REVOKED) continue;
            stop.accept(l.claims());
            stopped++;
        }
        return stopped;
    }
}
