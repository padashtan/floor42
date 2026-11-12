package dev.floor42.server.api;

import dev.floor42.server.iam.IdentityRefusals;
import dev.floor42.server.runtime.HookRegistry;
import dev.floor42.server.runtime.HookRegistry.Authentication;
import dev.floor42.server.runtime.HookRegistry.Refused;
import dev.floor42.server.runtime.RuntimeEvent.ToolRequested;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Map;

/** Who calls a hook or the MCP server: the session's own token, checked, and the answer to a token that fails. */
@ApplicationScoped
public class AgentAuth {

    @Inject
    HookRegistry hooks;

    @Inject
    IdentityRefusals refusals;

    Authentication check(String authorization, HttpServerRequest request) {
        return hooks.authenticate(authorization, address(request.remoteAddress()), address(request.localAddress()));
    }

    /**
     * The 401 for a refused call. A refusal that names its reason is remembered in memory, and journaled when the token
     * is attributable to a live session and the body names a tool call (a forged token reaches no journal).
     */
    Response refuse(Refused refused, ToolRequested call) {
        if (refused.reason() == null)
            return Response.status(Response.Status.UNAUTHORIZED).build();
        String code = refused.reason().name();
        refusals.add(Instant.now(), code, refused.jti(), refused.launch());
        if (refused.governor() != null
                && call != null
                && !call.tool().isBlank()
                && !call.toolUseId().isBlank()) refused.governor().refuse(call, "identity: " + code);
        return Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("code", "TOKEN_REFUSED", "reason", code))
                .build();
    }

    /** The connection's end as an address; null when it cannot be told (then the process check refuses). */
    private static InetSocketAddress address(SocketAddress a) {
        if (a == null || !a.isInetSocket()) return null;
        try {
            return new InetSocketAddress(InetAddress.getByName(a.hostAddress()), a.port());
        } catch (UnknownHostException e) {
            // Handled: not an address; the caller cannot be tied to a process, so it is refused
            return null;
        }
    }
}
