package dev.floor42.server.api;

import static dev.floor42.server.api.DirectorRequests.parse;

import dev.floor42.domain.AgentId;
import dev.floor42.server.app.RefusedException;
import dev.floor42.server.iam.AgentClients;
import dev.floor42.server.iam.ResourceCatalogue;
import dev.floor42.server.iam.ResourceCatalogue.Resource;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The broker's catalogue and the agents' grants (director only: an agent's hook token never reaches /api). Files, not
 * journaled. The agent can neither read nor change them; it can only call a granted resource.
 */
@Path("/api/iam")
@Produces(MediaType.APPLICATION_JSON)
public class BrokerCatalogueResource {

    /** The catalogue and who may use what. */
    public record ResourceCatalogueView(List<Resource> resources, Map<String, List<String>> grants) {
        public ResourceCatalogueView {
            grants = grants == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(grants));
            resources = resources == null ? null : Collections.unmodifiableList(new ArrayList<>(resources));
        }
    }

    /** {@code names} are catalogue resource names. */
    public record GrantsRequest(@NoNullItems List<String> names) {
        public GrantsRequest {
            names = names == null ? null : Collections.unmodifiableList(new ArrayList<>(names));
        }
    }

    private final ResourceCatalogue catalogue;
    private final AgentClients clients;

    public BrokerCatalogueResource(ResourceCatalogue catalogue, AgentClients clients) {
        this.catalogue = catalogue;
        this.clients = clients;
    }

    @GET
    @Path("/resources")
    public ResourceCatalogueView list() {
        return new ResourceCatalogueView(catalogue.all(), catalogue.grantsOf());
    }

    @PUT
    @Path("/resources")
    @Consumes(MediaType.APPLICATION_JSON)
    public Resource put(Resource resource) {
        try {
            catalogue.put(resource);
        } catch (IllegalArgumentException e) {
            throw RefusedException.invalid("INVALID_RESOURCE", e.getMessage(), e);
        }
        return catalogue.find(resource.name()).orElseThrow();
    }

    @DELETE
    @Path("/resources/{name}")
    public ResourceCatalogueView remove(@PathParam("name") String name) {
        if (catalogue.find(name).isEmpty()) throw RefusedException.notFound("UNKNOWN_RESOURCE", "No resource " + name);
        catalogue.remove(name);
        return list();
    }

    @PUT
    @Path("/agents/{id}/grants")
    @Consumes(MediaType.APPLICATION_JSON)
    public ResourceCatalogueView grant(@PathParam("id") String id, GrantsRequest request) {
        AgentId agent = parse(() -> new AgentId(id));
        if (!clients.onStaff(agent)) throw RefusedException.notFound("UNKNOWN_AGENT", "No agent " + id);
        List<String> names = request == null || request.names() == null ? List.of() : request.names();
        for (String n : names)
            if (catalogue.find(n).isEmpty()) throw RefusedException.invalid("UNKNOWN_RESOURCE", "No resource " + n);
        catalogue.grant(agent, names);
        return list();
    }
}
