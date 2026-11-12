package dev.floor42.server.api;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Origin;
import dev.floor42.server.app.DirectorTerminals;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.RefusedException;
import dev.floor42.server.security.LocalAccessGuard;
import dev.floor42.server.security.SessionRegistry;
import dev.floor42.server.security.SessionRegistry.Scope;
import dev.floor42.server.security.SessionRegistry.Session;
import dev.floor42.server.security.SessionToken;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * The director's sessions and script tokens (ADR-0044): who is signed in, signing out, and tokens for scripts, shown
 * once. Everything here lives in the daemon's memory; the journal records only a token's SHA-256. Documented in
 * {@code api-docs.json}.
 */
@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {

    /**
     * One open session. No secret, ever: the value was shown once, when it was issued.
     *
     * @param kind {@code BROWSER} or {@code SCRIPT}
     * @param scope {@code read}, {@code missions:write} or {@code all}
     * @param expires ISO-8601; null for a browser, which ends after its idle or absolute limit instead
     * @param idleUntil ISO-8601, when a browser session ends unless used; null for a script token
     * @param userAgent nullable: the browser or client that opened it
     * @param label nullable: what the director named a script token
     * @param current the session this very request came with
     * @param breakGlass IAM mode is on and this browser session was opened with the console link: it holds no Keycloak
     *     sign-in, so it cannot delegate
     * @param keycloakUntil ISO-8601, when this browser's Keycloak sign-in stops being usable for approvals; null outside
     *     IAM mode, for the console link and for a script token
     */
    public record DirectorSessionView(
            String id,
            String kind,
            String scope,
            String created,
            String lastUsed,
            String expires,
            String idleUntil,
            String userAgent,
            String label,
            boolean current,
            boolean breakGlass,
            String keycloakUntil) {}

    /**
     * @param scope {@code read}, {@code missions:write} or {@code all}
     * @param days 1 to 30
     * @param label nullable
     */
    public record ScriptTokenRequest(String scope, Integer days, String label) {}

    /** @param token the value, shown this once and never again */
    public record IssuedScriptToken(DirectorSessionView session, String token) {}

    @Inject
    SessionRegistry sessions;

    @Inject
    SessionToken directorToken;

    @Inject
    dev.floor42.server.iam.IamSettings iam;

    @Inject
    dev.floor42.server.iam.DirectorTokens directorTokens;

    @Inject
    OfficeService office;

    @Inject
    DirectorTerminals terminals;

    @Inject
    SocketSessions sockets;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "floor42.sessions.idle-hours", defaultValue = "8")
    long idleHours;

    @GET
    @Path("/sessions")
    public List<DirectorSessionView> list(
            @CookieParam(LocalAccessGuard.COOKIE) String cookie, @HeaderParam("Authorization") String authorization) {
        String mine = mine(cookie, authorization);
        return sessions.list().stream().map(s -> view(s, mine)).toList();
    }

    @DELETE
    @Path("/sessions/{id}")
    public Response revoke(@PathParam("id") String id) {
        Session s = sessions.revoke(id).orElseThrow(() -> new NotFoundException("No open session " + id));
        if (s.kind() == SessionRegistry.Kind.SCRIPT) journalRevoked(s, "revoked");
        directorTokens.signOut(s.id());
        terminals.closeOpenedBy(s.id());
        sockets.sweep();
        return Response.noContent().build();
    }

    @POST
    @Path("/tokens")
    @Consumes(MediaType.APPLICATION_JSON)
    @org.jboss.resteasy.reactive.ResponseStatus(201)
    public IssuedScriptToken issue(ScriptTokenRequest request, @HeaderParam("User-Agent") String userAgent) {
        if (request == null) throw new RefusedException(400, "INVALID_ARGUMENT", "scope and days are required");
        Scope scope = Scope.parse(request.scope())
                .orElseThrow(
                        () -> new RefusedException(400, "INVALID_ARGUMENT", "scope is read, missions:write or all"));
        int days = request.days() == null ? 0 : request.days();
        if (days < 1 || days > SessionRegistry.MAX_SCRIPT_DAYS) {
            throw new RefusedException(
                    400, "INVALID_ARGUMENT", "A token lasts 1 to " + SessionRegistry.MAX_SCRIPT_DAYS + " days");
        }
        String label = request.label() == null || request.label().isBlank()
                ? null
                : request.label().strip();
        if (label != null && label.length() > 80) {
            throw new RefusedException(400, "INVALID_ARGUMENT", "A label is at most 80 characters");
        }
        var issued = sessions.issueScript(scope, days, label, userAgent);
        Session s = issued.session();
        try {
            office.execute(c -> c.terminals()
                    .recordApiTokenIssued(
                            office.context(Actor.director(), Origin.REAL),
                            s.id(),
                            s.hash(),
                            scope.wire(),
                            s.expires().toString(),
                            label));
        } catch (RuntimeException e) {
            // Not journaled, not issued: a token the journal does not know of must not work.
            sessions.revoke(s.id());
            throw e;
        }
        return new IssuedScriptToken(view(s, null), issued.secret());
    }

    /**
     * Ends this browser's session and stops the shells it opened; another signed-in browser keeps its own. A script
     * token is refused: it ends with {@code DELETE /api/auth/sessions/{id}}, and stops no shell.
     */
    @POST
    @Path("/sign-out")
    public Response signOut(
            @CookieParam(LocalAccessGuard.COOKIE) String cookie, @HeaderParam("Authorization") String authorization) {
        refuseAScriptToken(authorization);
        sessions.revokeSecret(cookie).ifPresent(s -> {
            terminals.closeOpenedBy(s.id());
            directorTokens.signOut(s.id());
        });
        sockets.sweep();
        return Response.noContent().cookie(cleared()).build();
    }

    /** Ends every session, replaces the director's token and stops every shell. Refused to a script token. */
    @POST
    @Path("/sign-out-everywhere")
    public Response signOutEverywhere(@HeaderParam("Authorization") String authorization) {
        refuseAScriptToken(authorization);
        List<Session> ended = sessions.clear();
        directorToken.rotate();
        terminals.closeAll();
        for (Session s : ended) {
            directorTokens.signOut(s.id());
            if (s.kind() == SessionRegistry.Kind.SCRIPT) journalRevoked(s, "everywhere");
        }
        sockets.sweep();
        return Response.noContent().cookie(cleared()).build();
    }

    /** Signing out is the director's, from a browser or with the director's own token; never a script's. */
    private void refuseAScriptToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return;
        if (directorToken.matches(authorization.substring(7))) return;
        throw new RefusedException(
                403,
                "SCOPE",
                "A script token cannot sign out: revoke it with DELETE /api/auth/sessions/{id} or from the desk");
    }

    private void journalRevoked(Session s, String reason) {
        office.execute(c -> c.terminals()
                .recordApiTokenRevoked(office.context(Actor.director(), Origin.REAL), s.id(), s.hash(), reason));
    }

    private static NewCookie cleared() {
        return new NewCookie.Builder(LocalAccessGuard.COOKIE)
                .value("")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.STRICT)
                .build();
    }

    /** The hash of the secret this request came with, to mark the caller's own session. */
    private static String mine(String cookie, String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return SessionRegistry.sha256(authorization.substring(7));
        }
        return cookie == null ? null : SessionRegistry.sha256(cookie);
    }

    private DirectorSessionView view(Session s, String mine) {
        boolean browser = s.kind() == SessionRegistry.Kind.BROWSER;
        return new DirectorSessionView(
                s.id(),
                s.kind().name(),
                s.scope().wire(),
                s.created().toString(),
                s.lastUsed().toString(),
                s.expires() == null ? null : s.expires().toString(),
                browser ? s.lastUsed().plusSeconds(idleHours * 3600).toString() : null,
                s.userAgent(),
                s.label(),
                s.hash().equals(mine),
                s.breakGlass() && iam.enabled(),
                browser && iam.enabled()
                        ? directorTokens
                                .loginEndsAt(s.id())
                                .map(Object::toString)
                                .orElse(null)
                        : null);
    }
}
