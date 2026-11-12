package dev.floor42.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.server.app.OfficeService;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Live stream of the company's events for the UI (path protected by {@code LocalAccessGuard}). The stream only carries
 * what was stored in the journal; the UI reloads state through the REST API, so a missed message is never a lost fact.
 */
@WebSocket(path = "/ws/events", endpointId = EventStream.ID)
public class EventStream {

    static final String ID = "events";

    private static final Logger LOG = Logger.getLogger(EventStream.class);

    @Inject
    OpenConnections connections;

    @Inject
    OfficeService office;

    @Inject
    ObjectMapper json;

    @Inject
    SocketSessions sockets;

    private AutoCloseable subscription;

    void start(@Observes StartupEvent ignored) {
        subscription = office.subscribe(this::broadcast);
    }

    void stop(@Observes ShutdownEvent ignored) throws Exception {
        if (subscription != null) subscription.close();
    }

    @OnOpen
    public String onOpen(WebSocketConnection connection) {
        if (!sockets.bind(connection)) return null;
        return json.createObjectNode().put("type", "hello").toString();
    }

    /** A browser tab closing resets its socket: expected, not an error worth an alarm in the logs. */
    @OnError
    public void onError(WebSocketConnection connection, Throwable failure) {
        LOG.debugf("Event stream %s closed abruptly: %s", connection.id(), failure.toString());
    }

    /** Called under the OfficeService lock: builds views (re-entrant query), then sends asynchronously. */
    void broadcast(List<EventEnvelope> events) {
        // Open connections span every endpoint: only this stream's own subscribers get company events.
        var subscribers = connections.findByEndpointId(ID);
        if (subscribers.isEmpty()) return;
        List<EventView> views =
                office.query(c -> events.stream().map(e -> EventView.of(c, e)).toList());
        for (EventView view : views) {
            String message;
            try {
                message = json.writeValueAsString(view);
            } catch (JsonProcessingException e) {
                // Handled: logged; the page misses this event and reads the state again on its next request
                LOG.error("Cannot serialize event " + view.type(), e);
                continue;
            }
            // shortcut: websockets-next 3.40 has no send-buffer limit nor backpressure on sendText (only
            // max-frame-size),
            // so a client that stops reading queues messages in memory until its socket closes; the UI reloads state
            // through REST, so dropping a slow client would be safe. Upgrade when the API offers writeQueueFull.
            subscribers.forEach(c -> c.sendText(message)
                    .subscribe()
                    .with(ok -> {}, err -> LOG.debugf("Event not delivered to %s: %s", c.id(), err.getMessage())));
        }
    }
}
