package dev.floor42.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Live feed of the API console (path protected by {@code LocalAccessGuard}): one message per call, as it ends. */
@WebSocket(path = "/ws/api-calls", endpointId = ApiCallStream.ID)
public class ApiCallStream {

    static final String ID = "api-calls";
    private static final Logger LOG = Logger.getLogger(ApiCallStream.class);

    @Inject
    OpenConnections connections;

    @Inject
    ApiCalls calls;

    @Inject
    ObjectMapper json;

    @Inject
    SocketSessions sockets;

    private AutoCloseable subscription;

    void start(@Observes StartupEvent ignored) {
        subscription = calls.subscribe(this::broadcast);
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
        LOG.debugf("API console %s closed abruptly: %s", connection.id(), failure.toString());
    }

    void broadcast(ApiCalls.Call call) {
        var subscribers = connections.findByEndpointId(ID);
        if (subscribers.isEmpty()) return;
        String message;
        try {
            message = json.writeValueAsString(call);
        } catch (JsonProcessingException e) {
            // Handled: logged; the console misses this one call, the API answered it anyway
            LOG.error("Cannot serialize an API call", e);
            return;
        }
        subscribers.forEach(c -> c.sendText(message).subscribe().with(ok -> {}, err -> {}));
    }
}
