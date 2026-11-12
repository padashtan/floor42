package dev.floor42.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.floor42.server.app.DirectorTerminals;
import dev.floor42.server.app.DirectorTerminals.TerminalView;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * The screen of the director's own terminal (ADR-0041), under {@code /ws} so the access guard lets only the director in
 * (an agent's credential gets 403). It also needs the single-use ticket {@code POST /api/terminals} returned, and a
 * browser's Origin: a page of another local port cannot reach it with the director's cookie alone.
 *
 * <p>Down: binary frames are the program's output, unchanged; text frames are JSON, {@code {"type":"status",...}} (the
 * cost Claude Code reports) and {@code {"type":"ended",...}}. Up: {@code {"type":"input","data":"..."}} (what the
 * director typed or pasted) and {@code {"type":"resize","columns":n,"rows":n}}. Nothing of it is journaled or logged.
 */
@WebSocket(path = "/ws/terminal/{id}", endpointId = TerminalSocket.ID)
public class TerminalSocket {

    static final String ID = "terminal";
    /** Closes a connection without a valid ticket or Origin. */
    static final int REFUSED = 4403;

    private static final Logger LOG = Logger.getLogger(TerminalSocket.class);

    @Inject
    DirectorTerminals terminals;

    @Inject
    ObjectMapper json;

    @Inject
    SocketSessions sockets;

    private final Map<String, AutoCloseable> attached = new ConcurrentHashMap<>();
    private final Map<String, String> terminalOf = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        String id = connection.pathParam("id");
        String origin = connection.handshakeRequest().header("Origin");
        String ticket = param(connection.handshakeRequest().query(), "ticket");
        // The ticket is spent whatever else is wrong: a refused attempt cannot be replayed.
        boolean redeemed = terminals.redeem(id, ticket).isPresent();
        if (origin == null || !redeemed) {
            connection
                    .close(new CloseReason(REFUSED, "A ticket from POST /api/terminals is needed"))
                    .subscribe()
                    .with(ok -> {}, err -> {});
            return;
        }
        if (!sockets.bind(connection)) return;
        terminalOf.put(connection.id(), id);
        try {
            attached.put(connection.id(), terminals.attach(id, sink(connection)));
        } catch (RuntimeException e) {
            // Handled: the socket is closed with the reason, which the browser shows
            connection
                    .close(new CloseReason(REFUSED, "No open terminal " + id))
                    .subscribe()
                    .with(ok -> {}, err -> {});
        }
    }

    private DirectorTerminals.Sink sink(WebSocketConnection connection) {
        return new DirectorTerminals.Sink() {
            @Override
            public void output(byte[] bytes) {
                connection.sendBinary(bytes).subscribe().with(ok -> {}, err -> {});
            }

            @Override
            public void status(TerminalView view) {
                send(connection, "status", view);
            }

            @Override
            public void ended(TerminalView view) {
                send(connection, "ended", view);
            }
        };
    }

    @OnTextMessage
    public void onMessage(WebSocketConnection connection, String message) {
        String id = terminalOf.get(connection.id());
        if (id == null) return;
        try {
            JsonNode m = json.readTree(message);
            switch (m.path("type").asText()) {
                case "input" -> terminals.input(id, m.path("data").asText("").getBytes(StandardCharsets.UTF_8));
                case "resize" ->
                    terminals.resize(
                            id, m.path("columns").asInt(100), m.path("rows").asInt(30));
                default -> {
                    // unknown messages are ignored
                }
            }
        } catch (Exception e) {
            // Handled: What was typed is never logged: only that it could not be passed on.
            LOG.debugf(
                    "Terminal %s did not take a message: %s", id, e.getClass().getSimpleName());
        }
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        terminalOf.remove(connection.id());
        AutoCloseable a = attached.remove(connection.id());
        if (a != null) {
            try {
                a.close();
            } catch (Exception ignored) {
                // Handled: nothing more to detach
            }
        }
    }

    @OnError
    public void onError(WebSocketConnection connection, Throwable failure) {
        LOG.debugf(
                "Terminal socket %s closed abruptly: %s",
                connection.id(), failure.getClass().getSimpleName());
    }

    private void send(WebSocketConnection connection, String type, TerminalView view) {
        ObjectNode node = json.createObjectNode();
        node.put("type", type);
        node.set("terminal", json.valueToTree(view));
        connection.sendText(node.toString()).subscribe().with(ok -> {}, err -> {});
    }

    static String param(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
