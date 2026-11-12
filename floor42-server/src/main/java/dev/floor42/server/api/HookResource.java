package dev.floor42.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.floor42.server.runtime.AgentRuntime.Governor;
import dev.floor42.server.runtime.AgentRuntime.Verdict;
import dev.floor42.server.runtime.ClaudeCodeConfig;
import dev.floor42.server.runtime.HookRegistry.Accepted;
import dev.floor42.server.runtime.HookRegistry.Authentication;
import dev.floor42.server.runtime.HookRegistry.Refused;
import dev.floor42.server.runtime.RuntimeEvent.ToolRequested;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;

/**
 * Claude Code's PreToolUse hook for live sessions. Each call becomes a domain action decided by the mission policy;
 * when the policy asks the director, the call waits here (on a virtual thread) like the agent waits for it, until the
 * director answers or Claude Code's own hook timeout is near. Authenticated by the session's own hook token, not by
 * the director's session token: agents never hold the latter.
 */
@Path("/hooks")
public class HookResource {

    /** Leave Claude Code time to receive the answer before its own hook timeout fires. */
    private static final Duration MARGIN = Duration.ofSeconds(15);

    @Inject
    AgentAuth auth;

    @Inject
    ClaudeCodeConfig config;

    @Inject
    ObjectMapper json;

    @POST
    @Path("/pre-tool-use")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public Response preToolUse(
            @HeaderParam("Authorization") String authorization, @Context HttpServerRequest request, JsonNode input) {
        JsonNode body = input == null ? json.nullNode() : input;
        ToolRequested call = new ToolRequested(
                body.path("tool_use_id").asText(), body.path("tool_name").asText(), body.path("tool_input"));
        Authentication who = auth.check(authorization, request);
        if (who instanceof Refused refused) return auth.refuse(refused, call);
        Governor governor = ((Accepted) who).governor();
        Verdict verdict = governor.decide(call);
        if (verdict == Verdict.PENDING) {
            Duration patience = Duration.ofSeconds(config.hookTimeoutSeconds()).minus(MARGIN);
            verdict = governor.awaitDecision(call, patience.isNegative() ? Duration.ZERO : patience);
        }
        String reason = governor.explain(call.toolUseId());
        if (verdict == Verdict.PENDING) reason = "The director has not decided in time; the call is refused for now.";

        return answer(verdict, reason, verdict == Verdict.ALLOW ? governor.updatedInput(call) : null);
    }

    private Response answer(Verdict verdict, String reason, JsonNode updated) {
        ObjectNode out = json.createObjectNode();
        ObjectNode specific = out.putObject("hookSpecificOutput");
        specific.put("hookEventName", "PreToolUse");
        specific.put("permissionDecision", verdict == Verdict.ALLOW ? "allow" : "deny");
        specific.put("permissionDecisionReason", reason == null ? "Floor42 mission policy" : reason);
        if (updated != null) specific.set("updatedInput", updated);
        return Response.ok(out).build();
    }
}
