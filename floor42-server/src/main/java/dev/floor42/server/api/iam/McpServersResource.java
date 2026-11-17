package dev.floor42.server.api.iam;

import static dev.floor42.server.api.DirectorRequests.parse;

import com.fasterxml.jackson.databind.JsonNode;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.AssurancePolicy;
import dev.floor42.domain.MissionId;
import dev.floor42.server.api.NoNullItems;
import dev.floor42.server.app.core.RefusedException;
import dev.floor42.server.iam.AgentClients;
import dev.floor42.server.iam.IamSettings;
import dev.floor42.server.iam.McpClient;
import dev.floor42.server.iam.McpClient.Called;
import dev.floor42.server.iam.McpServers;
import dev.floor42.server.iam.McpServers.McpServer;
import dev.floor42.server.iam.ResourceBroker.Refusal;
import dev.floor42.server.iam.SessionClaims;
import dev.floor42.server.iam.fga.FgaException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The remote MCP servers agents may use and the tools the director ticked (ADR-0072), director only like the rest of
 * {@code /api/iam}. Refresh and Try use the chosen agent's own token for the chosen mission, on the director's behalf:
 * the remote server sees what it would see from that agent. Recorded by the API console and the Keycloak exchanges list;
 * a tick is journaled as {@code AuthorizationChanged} (ADR-0080).
 */
@Path("/api/iam/mcp-servers")
@Produces(MediaType.APPLICATION_JSON)
public class McpServersResource {

    public record McpServersView(List<McpServer> servers) {
        public McpServersView {
            servers = servers == null ? null : Collections.unmodifiableList(new ArrayList<>(servers));
        }
    }

    public record DeclareRequest(String resource, String path) {}

    public record RefreshRequest(String agent, String mission) {}

    /** A tool to allow, with the SHA-256 of the definition the director saw (from the server's view). */
    public record Tick(String name, String sha256) {}

    public record TicksRequest(@NoNullItems List<Tick> ticks) {
        public TicksRequest {
            ticks = ticks == null ? null : Collections.unmodifiableList(new ArrayList<>(ticks));
        }
    }

    public record TryRequest(String agent, String mission, String tool, JsonNode arguments) {}

    /** {@code refusal} is the code of the refusal, null when the server answered; {@code body} is the server's own text. */
    public record TryAnswer(boolean ok, String firstLine, String body, boolean truncated, String refusal) {}

    @Inject
    IamActGuard guard;

    private final McpServers servers;
    private final McpClient client;
    private final AgentClients agents;
    private final IamSettings settings;

    public McpServersResource(McpServers servers, McpClient client, AgentClients agents, IamSettings settings) {
        this.servers = servers;
        this.client = client;
        this.agents = agents;
        this.settings = settings;
    }

    @GET
    public McpServersView list() {
        return new McpServersView(servers.all());
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public McpServer declare(DeclareRequest request) {
        if (request == null) throw RefusedException.invalid("INVALID_MCP_SERVER", "A resource and a path are required");
        try {
            return servers.put(request.resource(), request.path());
        } catch (IllegalArgumentException e) {
            throw refused(e);
        } catch (FgaException e) {
            throw unavailable(e);
        }
    }

    @DELETE
    @Path("/{resource}")
    public McpServersView remove(@PathParam("resource") String resource) {
        if (servers.find(resource).isEmpty())
            throw RefusedException.notFound("UNKNOWN_MCP_SERVER", "No MCP server " + resource);
        try {
            servers.remove(resource);
        } catch (IllegalArgumentException e) {
            throw refused(e);
        } catch (FgaException e) {
            throw unavailable(e);
        }
        return list();
    }

    @POST
    @Path("/{resource}/tools/refresh")
    @Consumes(MediaType.APPLICATION_JSON)
    public McpServer refresh(@PathParam("resource") String resource, RefreshRequest request) {
        SessionClaims who =
                claims(resource, request == null ? null : request.agent(), request == null ? null : request.mission());
        try {
            return servers.pin(resource, client.listTools(who, resource), Instant.now());
        } catch (IllegalArgumentException e) {
            throw refused(e);
        } catch (Refusal e) {
            // Handled: the refusal's code is the answer's code, so the screen can say why
            throw RefusedException.conflict(e.code(), e.getMessage(), e);
        } catch (FgaException e) {
            throw unavailable(e);
        }
    }

    @PUT
    @Path("/{resource}/tools")
    @Consumes(MediaType.APPLICATION_JSON)
    public McpServer tick(@PathParam("resource") String resource, TicksRequest request) {
        if (servers.find(resource).isEmpty())
            throw RefusedException.notFound("UNKNOWN_MCP_SERVER", "No MCP server " + resource);
        try {
            Map<String, String> ticks = new LinkedHashMap<>();
            if (request != null && request.ticks() != null)
                request.ticks().forEach(t -> ticks.put(t.name(), t.sha256()));
            servers.checkTicks(resource, ticks);
            // Only a tick that widens needs the proof: unticking a tool keeps the click
            boolean widens = ticks.entrySet().stream()
                    .anyMatch(t -> servers.all().stream()
                            .filter(s -> s.resource().equals(resource))
                            .flatMap(s -> s.tools().stream())
                            .noneMatch(k -> k.ticked()
                                    && k.name().equals(t.getKey())
                                    && k.sha256().equals(t.getValue())));
            return guard.run(
                    AssurancePolicy.ActKind.GRANT, "mcp:" + resource, widens, () -> servers.tick(resource, ticks));
        } catch (IllegalArgumentException e) {
            throw refused(e);
        } catch (FgaException e) {
            throw unavailable(e);
        }
    }

    /** A tick is a tuple in OpenFGA: with OpenFGA out of reach nothing is written. */
    private static RefusedException unavailable(FgaException e) {
        return RefusedException.unavailable(e.code(), "OpenFGA could not write the ticks: " + e.getMessage(), e);
    }

    /** The refusal's code is the message of the exception; a damaged file is a conflict, the rest a bad request. */
    private static RefusedException refused(IllegalArgumentException e) {
        return e.getMessage().equals("MCP_CATALOGUE_DAMAGED") || e.getMessage().equals("MCP_ENDPOINT_CHANGED")
                ? RefusedException.conflict(e.getMessage(), "Refused: " + e.getMessage(), e)
                : RefusedException.invalid(e.getMessage(), "Refused: " + e.getMessage(), e);
    }

    /** The same code as an agent's call, without the hook: it is the director's own request. */
    @POST
    @Path("/{resource}/try")
    @Consumes(MediaType.APPLICATION_JSON)
    public TryAnswer tryCall(@PathParam("resource") String resource, TryRequest request) {
        if (request == null)
            throw RefusedException.invalid("INVALID_TRY", "An agent, a mission and a tool are required");
        SessionClaims who = claims(resource, request.agent(), request.mission());
        String tool = request.tool() == null ? "" : request.tool();
        try {
            Called c = client.callTool(who, resource, tool, request.arguments());
            return new TryAnswer(!c.error(), c.firstLine(resource, tool), c.text(), c.truncated(), null);
        } catch (Refusal e) {
            // Handled: a refusal is an answer of this route, with its code
            return new TryAnswer(false, e.code() + ": " + e.getMessage(), "", false, e.code());
        }
    }

    private SessionClaims claims(String resource, String agent, String mission) {
        if (servers.find(resource).isEmpty())
            throw RefusedException.notFound("UNKNOWN_MCP_SERVER", "No MCP server " + resource);
        if (!settings.enabled()) throw RefusedException.conflict("IAM_OFF", "IAM mode is off");
        if (agent == null || mission == null)
            throw RefusedException.invalid("INVALID_MCP_CALLER", "An agent and a mission are required");
        AgentId a = parse(() -> new AgentId(agent));
        if (!agents.onStaff(a)) throw RefusedException.notFound("UNKNOWN_AGENT", "No agent " + agent);
        return new SessionClaims("director-" + UUID.randomUUID(), a, parse(() -> new MissionId(mission)), null);
    }
}
