package dev.floor42.server.api.office;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AssurancePolicy;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Setting;
import dev.floor42.server.api.AssuranceResponses;
import dev.floor42.server.app.core.OfficeService;
import dev.floor42.server.app.core.OfficeSettings;
import dev.floor42.server.app.mission.LiveMissions;
import dev.floor42.server.iam.AssuredActs;
import dev.floor42.server.iam.IamSettings;
import dev.floor42.server.security.LocalAccessGuard;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
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

    @Inject
    IamSettings iam;

    @Inject
    AssuredActs acts;

    @Inject
    CurrentVertxRequest current;

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
            throw dev.floor42.server.app.core.RefusedException.invalid("INVALID_ARGUMENT", "No setting " + key, e);
        }
        String value = request == null ? null : request.value();
        if (turnsFourEyesOff(setting, value)) throw stepUp(value);
        office.execute(
                c -> c.preferences().changeSetting(office.context(Actor.director(), Origin.REAL), setting, value));
        // More sessions at once may let waiting work start now.
        if (setting == Setting.PARALLEL_SESSIONS) live.staffChanged();
        // Turning the sandbox on measures it again: the director may just have installed or repaired WSL.
        if (setting == Setting.SHELL_SANDBOX && settings.sandboxEnabled()) sandbox.recheck();
        return list();
    }

    /** In IAM mode, turning four eyes off is an act of two signatures (ADR-0081); every other change keeps the click. */
    private boolean turnsFourEyesOff(Setting setting, String value) {
        return iam.enabled()
                && setting == Setting.FOUR_EYES
                && "on".equals(settings.valueOf(Setting.FOUR_EYES))
                && !"on".equalsIgnoreCase(value == null ? "" : value.strip());
    }

    /** A token cannot do it (403 HUMAN_ONLY); a browser follows the step-up, which ends with the second person. */
    private RuntimeException stepUp(String value) {
        if (LocalAccessGuard.viaOf(current.getCurrent()) != LocalAccessGuard.Via.COOKIE)
            return AssuranceResponses.humanOnly();
        var session = LocalAccessGuard.sessionOf(current.getCurrent());
        if (!acts.mayAssure(session)) return AssuranceResponses.needsSignIn();
        String path = acts.record(
                new AssurancePolicy.Act(AssurancePolicy.ActKind.SETTING, Setting.FOUR_EYES.name()),
                true,
                session.get(),
                signed -> office.execute(c -> c.preferences()
                        .changeSetting(
                                office.context(Actor.director(), Origin.REAL), Setting.FOUR_EYES, value, signed)));
        return AssuranceResponses.followStepUp(path);
    }
}
