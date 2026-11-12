package dev.floor42.server.api;

import dev.floor42.server.app.DirectorTerminals;
import dev.floor42.server.app.DirectorTerminals.TerminalView;
import dev.floor42.server.security.LocalAccessGuard;
import dev.floor42.server.security.SessionRegistry;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The director's own Claude Code in a terminal (ADR-0041): opened on the director's click only, one at a time per
 * project; the screen goes through {@code /ws/terminal/{id}} with the single-use ticket these calls return. Director
 * only, as every route under {@code /api}: an agent's credential gets 403.
 */
@Path("/api/terminals")
@Produces(MediaType.APPLICATION_JSON)
public class TerminalResource {

    /** @param reason why no terminal can be opened on this machine, null when one can */
    public record TerminalsView(boolean available, String reason, List<TerminalView> terminals) {
        public TerminalsView {
            terminals = terminals == null ? null : Collections.unmodifiableList(new ArrayList<>(terminals));
        }
    }

    /**
     * @param project the folder to open the director's Claude Code in
     * @param columns the terminal's width in characters, rows its height
     */
    public record OpenTerminal(String project, Integer columns, Integer rows) {}

    /** @param ticket opens {@code /ws/terminal/{id}?ticket=...} once, within a minute */
    public record TerminalAccess(TerminalView terminal, String ticket) {}

    @Inject
    DirectorTerminals terminals;

    @Inject
    CurrentVertxRequest current;

    @GET
    public TerminalsView list() {
        var reason = terminals.unavailable();
        return new TerminalsView(reason.isEmpty(), reason.orElse(null), terminals.list());
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public TerminalAccess open(OpenTerminal request) {
        TerminalView view = terminals.open(
                request == null ? null : request.project(),
                size(request == null ? null : request.columns(), 100),
                size(request == null ? null : request.rows(), 30),
                LocalAccessGuard.sessionOf(current.getCurrent())
                        .map(SessionRegistry.Session::id)
                        .orElse(null));
        return new TerminalAccess(view, terminals.ticket(view.id()));
    }

    /** A new ticket for an open terminal: a page reloaded, or a second tab, attaches again. */
    @POST
    @Path("/{id}/attach")
    public TerminalAccess attach(@PathParam("id") String id) {
        TerminalView view =
                terminals.find(id).orElseThrow(() -> new jakarta.ws.rs.NotFoundException("No open terminal " + id));
        return new TerminalAccess(view, terminals.ticket(id));
    }

    @DELETE
    @Path("/{id}")
    public TerminalView close(@PathParam("id") String id) {
        return terminals.close(id);
    }

    private static int size(Integer value, int otherwise) {
        return value == null ? otherwise : Math.max(2, Math.min(value, 1000));
    }
}
