package dev.floor42.server.api;

import static dev.floor42.server.api.DirectorRequests.asDirector;
import static dev.floor42.server.api.DirectorRequests.parse;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Decision;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.server.api.PermissionViews.DefaultPermissionView;
import dev.floor42.server.api.PermissionViews.ToolSettingView;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.RefusedException;
import dev.floor42.server.runtime.ToolCatalog;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * The director's permission tables: a mission's, and the company's defaults new work missions start from (D3).
 * Permissions are enforced by the policy, never by prompt text.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class PermissionsResource {

    public record ToolSettingRequest(String decision) {}

    private final OfficeService office;

    public PermissionsResource(OfficeService office) {
        this.office = office;
    }

    @GET
    @Path("/missions/{id}/tools")
    public List<ToolSettingView> toolSettings(@PathParam("id") String id) {
        MissionId mission = parse(() -> new MissionId(id));
        return office.query(c -> {
            Mission m = c.missions().find(mission).orElseThrow(() -> new NotFoundException("No mission " + id));
            return ToolCatalog.GOVERNED_TOOLS.stream()
                    .map(t -> ToolSettingView.of(c, m, t))
                    .toList();
        });
    }

    /** The director sets one line of the permission table: ALLOW, ASK (once), ASK_ALWAYS or DENY. */
    @PUT
    @Path("/missions/{id}/tools/{tool}")
    public ToolSettingView setToolSetting(
            @PathParam("id") String id, @PathParam("tool") String tool, ToolSettingRequest request) {
        MissionId mission = parse(() -> new MissionId(id));
        governed(tool);
        Decision decision = decision(request == null || request.decision() == null ? "" : request.decision());
        var origin = office.query(c -> c.missions()
                .find(mission)
                .orElseThrow(() -> new NotFoundException("No mission " + id))
                .origin());
        office.execute(c -> c.missions()
                .setToolPermission(
                        office.context(Actor.director(), origin), mission, tool, ToolCatalog.category(tool), decision));
        return office.query(
                c -> ToolSettingView.of(c, c.missions().find(mission).orElseThrow(), tool));
    }

    @GET
    @Path("/permissions/defaults")
    public List<DefaultPermissionView> defaultPermissions() {
        return office.query(c -> ToolCatalog.GOVERNED_TOOLS.stream()
                .map(t -> DefaultPermissionView.of(c, t))
                .toList());
    }

    /** The director sets a line of the default table; a null decision goes back to Floor42's built-in line. */
    @PUT
    @Path("/permissions/defaults/{tool}")
    public DefaultPermissionView setDefaultPermission(@PathParam("tool") String tool, ToolSettingRequest request) {
        governed(tool);
        Decision decision = request == null || request.decision() == null ? null : decision(request.decision());
        office.execute(c ->
                c.preferences().setDefaultPermission(asDirector(office), tool, ToolCatalog.category(tool), decision));
        return office.query(c -> DefaultPermissionView.of(c, tool));
    }

    private static void governed(String tool) {
        if (!ToolCatalog.GOVERNED_TOOLS.contains(tool)) throw new NotFoundException("Not a governed tool: " + tool);
    }

    private static Decision decision(String name) {
        try {
            return Decision.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw RefusedException.invalid("INVALID_DECISION", "Use one of ALLOW, ASK, ASK_ALWAYS, DENY", e);
        }
    }
}
