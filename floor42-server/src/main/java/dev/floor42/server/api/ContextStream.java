package dev.floor42.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.server.runtime.ContextGauge;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Live context gauge of the agents (path protected by {@code LocalAccessGuard}): one message per new reading of a
 * session's context in use. Not journaled: a reading is a measurement of the moment, kept in memory only.
 */
@WebSocket(path = "/ws/context", endpointId = ContextStream.ID)
public class ContextStream {

    static final String ID = "context";
    private static final Logger LOG = Logger.getLogger(ContextStream.class);

    @Inject
    OpenConnections connections;

    @Inject
    ContextGauge gauge;

    @Inject
    ObjectMapper json;

    @Inject
    SocketSessions sockets;

    private AutoCloseable subscription;

    void start(@Observes StartupEvent ignored) {
        subscription = gauge.subscribe(this::broadcast);
    }

    void stop(@Observes ShutdownEvent ignored) throws Exception {
        if (subscription != null) subscription.close();
    }

    @OnOpen
    public String onOpen(WebSocketConnection connection) {
        if (!sockets.bind(connection)) return null;
        return json.createObjectNode().put("type", "hello").toString();
    }

    @OnError
    public void onError(WebSocketConnection connection, Throwable failure) {
        LOG.debugf("Context gauge %s closed abruptly: %s", connection.id(), failure.toString());
    }

    void broadcast(ContextGauge.ContextReading reading) {
        var subscribers = connections.findByEndpointId(ID);
        if (subscribers.isEmpty()) return;
        String message;
        try {
            message = json.writeValueAsString(reading);
        } catch (JsonProcessingException e) {
            // Handled: logged; the gauge misses this one reading, the next one follows
            LOG.error("Cannot serialize a context reading", e);
            return;
        }
        subscribers.forEach(c -> c.sendText(message).subscribe().with(ok -> {}, err -> {}));
    }
}
