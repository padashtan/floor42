package dev.floor42.server.api;

import dev.floor42.domain.Actor;
import dev.floor42.domain.MissionId;
import dev.floor42.server.app.MissionAssets;
import dev.floor42.server.app.MissionAssets.AssetSelection;
import dev.floor42.server.app.MissionAssets.MissionAssetsView;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.PluginViews.PluginOutcome;
import dev.floor42.server.app.PluginViews.PluginPreview;
import dev.floor42.server.app.PluginViews.PluginRequest;
import dev.floor42.server.app.PluginViews.PluginsView;
import dev.floor42.server.app.Plugins;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Plugins from a marketplace, installed on the director's click (ADR-0040), and what each mission's sessions receive.
 * The director's routes only, under {@code /api}: an agent's credential gets 403 here (ADR-0034), and no agent tool,
 * hook or brief reaches them.
 */
@Produces(MediaType.APPLICATION_JSON)
@Path("/api")
public class PluginsResource {

    @Inject
    OfficeService office;

    @Inject
    Plugins plugins;

    @Inject
    MissionAssets assets;

    @GET
    @Path("/plugins")
    @RunOnVirtualThread
    public PluginsView plugins() {
        return plugins.view();
    }

    @POST
    @Path("/plugins/marketplaces/{name}/refresh")
    @RunOnVirtualThread
    public PluginsView refresh(@PathParam("name") String name) {
        return plugins.refresh(Actor.director(), name);
    }

    @POST
    @Path("/plugins/preview")
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public PluginPreview preview(PluginRequest request) {
        return plugins.preview(Actor.director(), request);
    }

    @POST
    @Path("/plugins/run")
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public PluginOutcome run(PluginRequest request) {
        return plugins.run(Actor.director(), request);
    }

    @GET
    @Path("/missions/{id}/assets")
    @RunOnVirtualThread
    public MissionAssetsView missionAssets(@PathParam("id") String id) {
        return assets.view(mission(id));
    }

    @PUT
    @Path("/missions/{id}/assets")
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public MissionAssetsView selectAssets(@PathParam("id") String id, AssetSelection selection) {
        return assets.select(Actor.director(), mission(id), selection);
    }

    private MissionId mission(String id) {
        MissionId m = new MissionId(id);
        if (office.query(c -> c.missions().find(m)).isEmpty()) throw new NotFoundException("No mission " + id);
        return m;
    }
}
