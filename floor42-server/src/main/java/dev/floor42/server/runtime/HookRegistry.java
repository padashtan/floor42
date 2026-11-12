package dev.floor42.server.runtime;

import dev.floor42.server.iam.IamSessions;
import dev.floor42.server.iam.IamSettings;
import dev.floor42.server.iam.SessionClaims;
import dev.floor42.server.iam.SessionIdentity.Minted;
import dev.floor42.server.iam.TokenRefusal;
import dev.floor42.server.runtime.AgentRuntime.Governor;
import dev.floor42.server.security.AgentCredentials;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Live sessions waiting for hook calls. Each session gets its own token, passed to Claude Code through its environment
 * only; a hook call without a known token is refused. The token lets a call reach its own session's governor, and
 * nothing else: it grants no permission by itself, and the access guard refuses it on every director route (ADR-0034).
 *
 * <p>IAM mode off: a random token per session. IAM mode on: the session's Keycloak token, verified on every call and
 * bound to the process Floor42 launched ({@link IamSessions}); the random token is never used then.
 */
@ApplicationScoped
public class HookRegistry implements AgentCredentials {

    /** The answer to "who is calling": a session's governor, or a refusal (off mode: a null reason, no more). */
    public sealed interface Authentication permits Accepted, Refused {}

    /** @param claims what the verified token states; null in off mode */
    public record Accepted(Governor governor, SessionClaims claims) implements Authentication {}

    /**
     * @param reason why; null in off mode, where an unknown token is just unknown
     * @param governor the live session the token is attributable to, or null (forged, unknown or ended)
     * @param jti the token's id when its signature held, else null
     * @param launch the launch the token named when its signature held, else null
     */
    public record Refused(TokenRefusal reason, Governor governor, String jti, String launch)
            implements Authentication {}

    private final Map<String, Governor> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private BooleanSupplier iamOn = () -> false;
    private IamSessions iam;

    public HookRegistry() {}

    HookRegistry(BooleanSupplier iamOn, IamSessions iam) {
        this.iamOn = iamOn;
        this.iam = iam;
    }

    @Inject
    void wire(IamSettings settings, IamSessions iamSessions) {
        this.iamOn = settings::enabled;
        this.iam = iamSessions;
    }

    public String register(Governor governor) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, governor);
        return token;
    }

    /** IAM mode: the session registers with the token Keycloak gave it and the claims Floor42 expects it to carry. */
    public String register(Governor governor, SessionClaims claims, Minted minted) {
        return iam.register(governor, claims, minted);
    }

    /** Right after {@code builder.start()}: the only process the session's calls may come from. No-op in off mode. */
    public void bindProcess(String token, long pid) {
        if (iamOn.getAsBoolean()) iam.bindProcess(token, pid);
    }

    public void unregister(String token) {
        sessions.remove(token);
        if (iamOn.getAsBoolean()) iam.unregister(token);
    }

    /** True for a live session's credential; in IAM mode verified and live, the process not checked. */
    @Override
    public boolean isLive(String candidate) {
        return find(candidate).isPresent();
    }

    public Optional<Governor> find(String token) {
        if (token == null) return Optional.empty();
        if (iamOn.getAsBoolean()) return iam.governorOf(token);
        // Constant-time comparison against each live token: there are only a handful.
        for (Map.Entry<String, Governor> e : sessions.entrySet()) {
            if (MessageDigest.isEqual(
                    e.getKey().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
                return Optional.of(e.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * Who is calling, from the Authorization header and the connection's two ends. Off mode: the token's session, or a
     * refusal without a reason. IAM mode: every check of {@link IamSessions}; the caller's process included.
     */
    public Authentication authenticate(String authorization, InetSocketAddress remote, InetSocketAddress local) {
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        if (iamOn.getAsBoolean()) return iam.authenticate(token, remote, local);
        return find(token)
                .<Authentication>map(g -> new Accepted(g, null))
                .orElseGet(() -> new Refused(null, null, null, null));
    }
}
