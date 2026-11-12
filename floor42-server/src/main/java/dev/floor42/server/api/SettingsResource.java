package dev.floor42.server.api;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Setting;
import dev.floor42.server.app.LiveMissions;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.OfficeSettings;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Company-wide settings: the value in force, the daemon's default, and whether the director changed it. Every change is
 * journaled as the director's decision; it applies from the next session on.
 */
@Path("/api/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SettingsResource {

    /** @param options the accepted values; empty when any value in range is accepted */
    public record SettingView(String key, String value, String defaultValue, boolean changed, List<String> options) {
        public SettingView {
            options = options == null ? null : Collections.unmodifiableList(new ArrayList<>(options));
        }
    }

    /** @param value null goes back to the daemon's default */
    public record NewValue(String value) {}

    @Inject
    OfficeService office;

    @Inject
    OfficeSettings settings;

    @Inject
    LiveMissions live;

    @Inject
    dev.floor42.server.runtime.Sandbox sandbox;

    @GET
    public List<SettingView> list() {
        return Arrays.stream(Setting.values())
                .map(s -> new SettingView(
                        s.name(),
                        settings.valueOf(s),
                        settings.defaultOf(s),
                        office.query(c -> c.preferences().setting(s)).isPresent(),
                        s.options()))
                .toList();
    }

    @PUT
    @Path("/{key}")
    public List<SettingView> change(@PathParam("key") String key, NewValue request) {
        Setting setting;
        try {
            setting = Setting.valueOf(key);
        } catch (IllegalArgumentException e) {
            throw dev.floor42.server.app.RefusedException.invalid("INVALID_ARGUMENT", "No setting " + key, e);
        }
        office.execute(c -> c.preferences()
                .changeSetting(
                        office.context(Actor.director(), Origin.REAL),
                        setting,
                        request == null ? null : request.value()));
        // More sessions at once may let waiting work start now.
        if (setting == Setting.PARALLEL_SESSIONS) live.staffChanged();
        // Turning the sandbox on measures it again: the director may just have installed or repaired WSL.
        if (setting == Setting.SHELL_SANDBOX && settings.sandboxEnabled()) sandbox.recheck();
        return list();
    }
}
