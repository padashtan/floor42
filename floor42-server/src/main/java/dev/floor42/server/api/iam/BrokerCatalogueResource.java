package dev.floor42.server.api.iam;

import static dev.floor42.server.api.DirectorRequests.parse;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.AssurancePolicy;
import dev.floor42.server.api.IamActGuard;
import dev.floor42.server.api.NoNullItems;
import dev.floor42.server.app.core.RefusedException;
import dev.floor42.server.iam.AgentClients;
import dev.floor42.server.iam.ResourceCatalogue;
import dev.floor42.server.iam.ResourceCatalogue.Resource;
import dev.floor42.server.iam.fga.FgaException;
import jakarta.inject.Inject;
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
 * The broker's catalogue and the agents' grants (director only: an agent's hook token never reaches /api). The
 * catalogue is a file; a grant is a tuple in OpenFGA, written here and journaled as {@code AuthorizationChanged}
 * (ADR-0080). The agent can neither read nor change them; it can only call a granted resource.
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

    @Inject
    IamActGuard guard;

    private final ResourceCatalogue catalogue;
    private final AgentClients clients;

    public BrokerCatalogueResource(ResourceCatalogue catalogue, AgentClients clients) {
        this.catalogue = catalogue;
        this.clients = clients;
    }

    @GET
    @Path("/resources")
    public ResourceCatalogueView list() {
        try {
            return new ResourceCatalogueView(catalogue.all(), catalogue.grantsOf());
        } catch (FgaException e) {
            // Handled: the catalogue is the director's file and still shows; nobody holds anything while OpenFGA is out
            return new ResourceCatalogueView(catalogue.all(), Map.of());
        }
    }

    @PUT
    @Path("/resources")
    @Consumes(MediaType.APPLICATION_JSON)
    public Resource put(Resource resource) {
        Resource clean;
        try {
            clean = catalogue.validate(resource);
        } catch (IllegalArgumentException e) {
            throw RefusedException.invalid("INVALID_RESOURCE", e.getMessage(), e);
        }
        // Another address for a resource agents already use sends their calls elsewhere: an assured act (ADR-0081)
        boolean moves = catalogue
                .find(clean.name())
                .filter(old -> !old.baseUrl().equals(clean.baseUrl()))
                .isPresent();
        return guard.run(AssurancePolicy.ActKind.GRANT, "resource:" + clean.name(), moves, () -> {
            catalogue.put(clean);
            return catalogue.find(clean.name()).orElseThrow();
        });
    }

    @DELETE
    @Path("/resources/{name}")
    public ResourceCatalogueView remove(@PathParam("name") String name) {
        if (catalogue.find(name).isEmpty()) throw RefusedException.notFound("UNKNOWN_RESOURCE", "No resource " + name);
        try {
            catalogue.remove(name);
        } catch (FgaException e) {
            throw unavailable(e);
        }
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
        // Only a grant that widens needs the proof: taking one back keeps the click
        List<String> held;
        try {
            held = catalogue.grantsOf().getOrDefault(id, List.of());
        } catch (FgaException e) {
            throw unavailable(e);
        }
        boolean widens = !held.containsAll(names);
        guard.run(AssurancePolicy.ActKind.GRANT, "agent:" + id, widens, () -> {
            try {
                catalogue.grant(agent, names);
            } catch (FgaException e) {
                throw unavailable(e);
            }
            return null;
        });
        return list();
    }

    /** A grant is a tuple in OpenFGA: with OpenFGA out of reach nothing is written (409 would blame the director). */
    private static RefusedException unavailable(FgaException e) {
        return RefusedException.unavailable(e.code(), "OpenFGA could not write the grant: " + e.getMessage(), e);
    }
}
