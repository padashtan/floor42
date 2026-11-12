package dev.floor42.server.api;

import dev.floor42.server.app.DirectorTerminals;
import dev.floor42.server.security.LocalAccessGuard;
import dev.floor42.server.security.SessionRegistry;
import dev.floor42.server.security.SessionToken;
import dev.floor42.server.security.SessionsEnded;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * Keeps a socket from outliving the session it was opened with (ADR-0044): each /ws connection is bound to the
 * director's session at the upgrade, and a sweep (after a revoke or a sign-out, and every 30 s for expiry) closes the
 * connections and the shells of a session that is gone. The lookup never marks a session used.
 */
@ApplicationScoped
public class SocketSessions {

    /** Closes a connection whose session ended. */
    static final int ENDED = 4401;

    private static final Logger LOG = Logger.getLogger(SocketSessions.class);

    private record Bound(WebSocketConnection connection, String secret, boolean director) {}

    @Inject
    SessionRegistry sessions;

    @Inject
    DirectorTerminals terminals;

    @Inject
    SessionToken token;

    private final Map<String, Bound> bound = new ConcurrentHashMap<>();
    private ScheduledExecutorService sweeper;
    private Runnable beforeBind;
    private Runnable duringSweep;

    void start(@Observes StartupEvent ignored) {
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "socket-sessions");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleWithFixedDelay(this::safeSweep, 30, 30, TimeUnit.SECONDS);
    }

    void stop(@Observes ShutdownEvent ignored) {
        if (sweeper != null) sweeper.shutdownNow();
    }

    /** Off the caller's thread: a sign-in handler runs on the event loop, the sweep writes the journal and stops shells. */
    void onEnded(@Observes SessionsEnded ignored) {
        try {
            sweeper.execute(this::safeSweep);
        } catch (RuntimeException e) {
            // Handled: shutting down, or not started; the next sweep (or none) follows.
            LOG.debug("Socket sweep not queued: " + e);
        }
    }

    /** Test seam: what runs inside the window between an upgrade and its binding, and inside a sweep. */
    void seam(Runnable beforeBind, Runnable duringSweep) {
        this.beforeBind = beforeBind;
        this.duringSweep = duringSweep;
    }

    /**
     * Ties this connection to the credential it came with (cookie, script token or the director's own token), read
     * as the guard reads it. Fails closed: a credential that is no longer live (revoked or expired between the
     * upgrade and now) closes the connection at once. False when it was closed.
     */
    public boolean bind(WebSocketConnection connection) {
        if (beforeBind != null) beforeBind.run();
        LocalAccessGuard.Credential credential = LocalAccessGuard.credentialOf(
                connection.handshakeRequest().header("Authorization"),
                connection.handshakeRequest().header("Cookie"));
        if (credential == null) return refuse(connection);
        Bound b = new Bound(connection, credential.secret(), credential.bearer() && token.matches(credential.secret()));
        bound.put(connection.id(), b);
        // Once recorded, a sweep sees it: look again, in case the credential ended just before it was recorded.
        if (!alive(b)) {
            bound.remove(connection.id());
            return refuse(connection);
        }
        return true;
    }

    private boolean refuse(WebSocketConnection connection) {
        end(connection);
        return false;
    }

    private void end(WebSocketConnection connection) {
        connection.close(new CloseReason(ENDED, "Session ended")).subscribe().with(ok -> {}, err -> {});
    }

    /** Whether the credential is live now: asked at the moment of each decision, never from an earlier snapshot. */
    private boolean alive(Bound b) {
        return b.director()
                ? token.matches(b.secret())
                : sessions.peek(b.secret()).isPresent();
    }

    private void safeSweep() {
        try {
            sweep();
        } catch (RuntimeException e) {
            // Handled: the next sweep tries again.
            LOG.warn("Socket sweep failed: " + e);
        }
    }

    /** Closes the connections and the shells of every credential that is no longer live. */
    public void sweep() {
        if (duringSweep != null) duringSweep.run();
        for (Bound b : bound.values()) {
            if (!b.connection().isOpen()) {
                bound.remove(b.connection().id());
            } else if (!alive(b)) {
                bound.remove(b.connection().id());
                end(b.connection());
            }
        }
        for (String opener : terminals.openers()) if (sessions.find(opener).isEmpty()) terminals.closeOpenedBy(opener);
    }
}
