package dev.floor42.server.api;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Origin;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.OfficeSettings;
import dev.floor42.server.runtime.Sandbox;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Unsafe mode (ADR-0063, pays D16): the director's journaled choice to let a REAL mission's shell run without the WSL
 * sandbox, as the daemon's own OS user. Off by default. Like every change of the director's, it needs the full scope
 * of the credential (the access guard refuses a narrower token), and the domain refuses anyone but the director.
 */
@Path("/api/unsafe-mode")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UnsafeModeResource {

    /**
     * @param on the director allows a real mission's shell to run without the sandbox
     * @param sandboxWorks whether the sandbox works on this machine; null when nothing needs it (unsafe mode on and
     *     the setting off), so nothing was measured
     */
    public record UnsafeModeView(boolean on, Boolean sandboxWorks) {}

    /** @param reason required when switching on: why, in the director's words; it is journaled */
    public record UnsafeModeRequest(boolean on, String reason) {}

    @Inject
    OfficeService office;

    @Inject
    OfficeSettings settings;

    @Inject
    Sandbox sandbox;

    @GET
    public UnsafeModeView get() {
        return new UnsafeModeView(settings.unsafeMode(), sandbox.enabled() ? sandbox.available() : null);
    }

    @PUT
    public UnsafeModeView change(UnsafeModeRequest request) {
        if (request == null) throw dev.floor42.server.app.RefusedException.invalid("INVALID_ARGUMENT", "Say on or off");
        office.execute(c -> c.preferences()
                .setUnsafeMode(office.context(Actor.director(), Origin.REAL), request.on(), request.reason()));
        return get();
    }
}
