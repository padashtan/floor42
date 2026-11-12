package dev.floor42.server.api;

import dev.floor42.domain.DomainException;
import dev.floor42.domain.Refusal;
import dev.floor42.server.app.RefusedException;
import dev.floor42.server.journal.JournalException;
import dev.floor42.server.runtime.GitFailure;
import dev.floor42.server.runtime.OutsideWorkspace;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Set;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Refusals are explained, not hidden: every error body is {@code {code, message}}, with a stable code and a readable
 * message. 400 when the request itself is wrong, 404 when what it names does not exist, 409 when the state of things
 * refuses it, 413 when it is too large, 503 when the journal cannot be written.
 */
public class ErrorMappers {

    private final AllowedMethods allowed;

    @jakarta.inject.Inject
    ErrorMappers(AllowedMethods allowed) {
        this.allowed = allowed;
    }

    public record Problem(String code, String message) {}

    /** Domain codes that say the request itself is wrong, whatever the state: 400, not 409. */
    static final Set<String> VALIDATION = Set.of(
            "INVALID_ARGUMENT",
            "INVALID_ANSWER",
            "INVALID_COMMIT",
            "PLAN_TOO_LONG",
            "OUTSIDE_WORKSPACE",
            // A ticket's body names a dependency that is not one: the request is wrong, the route exists.
            "UNKNOWN_DEPENDENCY",
            "ASK_BACK_TEXT");

    /** Domain codes that say the request is too large: 413. */
    static final Set<String> TOO_LARGE = Set.of("FILE_TOO_LARGE");

    static int status(DomainException e) {
        return Refusal.kindOf(e.code()).map(ErrorMappers::status).orElseGet(() -> byName(e.code()));
    }

    static int status(Refusal.Kind kind) {
        return switch (kind) {
            case INVALID -> RefusedException.BAD_REQUEST;
            case NOT_FOUND -> RefusedException.NOT_FOUND;
            case CONFLICT -> RefusedException.CONFLICT;
        };
    }

    /** A code the domain does not give (the server's own, FILE_TOO_LARGE...): its status follows from its name. */
    static int byName(String code) {
        if (VALIDATION.contains(code) || code.startsWith("MISSING_")) return RefusedException.BAD_REQUEST;
        if (TOO_LARGE.contains(code)) return RefusedException.TOO_LARGE;
        // What the request names does not exist (UNKNOWN_MISSION, UNKNOWN_TICKET...): 404, as the API docs say.
        if (code.startsWith("UNKNOWN_") || code.equals("NO_FILE")) return RefusedException.NOT_FOUND;
        return RefusedException.CONFLICT;
    }

    private static Response problem(int status, String code, String message) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(new Problem(code, message))
                .build();
    }

    @ServerExceptionMapper
    public Response refused(DomainException e) {
        return problem(status(e), e.code(), e.getMessage());
    }

    @ServerExceptionMapper
    public Response refused(RefusedException e) {
        return problem(e.status(), e.code(), e.getMessage());
    }

    /**
     * A JAX-RS exception thrown without a body (a {@code NotFoundException} with a message, a body that cannot be
     * read) gets the same shape: its code is the status's name, NOT_FOUND or BAD_REQUEST. One that carries a body
     * keeps it.
     */
    @ServerExceptionMapper
    public Response web(WebApplicationException e, jakarta.ws.rs.core.UriInfo uri) {
        Response r = e.getResponse();
        if (r != null && r.hasEntity()) return r;
        // Outside the API a browser asked for a page (the UI's routes serve the real ones): it gets a page, not JSON.
        String path = uri == null ? "" : uri.getPath();
        if (!path.startsWith("/api") && !path.startsWith("/hooks") && !path.startsWith("/q/")) {
            int s = r == null ? 500 : r.getStatus();
            return Response.status(s)
                    .type(MediaType.TEXT_HTML)
                    .entity("<!DOCTYPE html><title>Floor42</title><p>HTTP " + s + "</p>")
                    .build();
        }
        int status = r == null ? 500 : r.getStatus();
        Response.Status known = Response.Status.fromStatusCode(status);
        String code = known == null ? "HTTP_" + status : known.name();
        String message = e.getMessage() == null || e.getMessage().isBlank()
                ? (known == null ? code : known.getReasonPhrase())
                : e.getMessage();
        Response.ResponseBuilder b = Response.fromResponse(
                        r == null ? Response.status(status).build() : r)
                .type(MediaType.APPLICATION_JSON)
                .entity(new Problem(code, message));
        if (status == 405 && allowed != null && (r == null || r.getHeaderString("Allow") == null)) {
            String allow = allowed.allow(path);
            if (allow != null) b.header("Allow", allow);
        }
        return b.build();
    }

    /** An id of a path or a body that is not an id (a character outside the format) names nothing: 404, as parse does. */
    @ServerExceptionMapper
    public Response unknownId(dev.floor42.domain.InvalidIdentifierException e) {
        return problem(RefusedException.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    /** A path the file system cannot hold (a NUL character, a character the platform forbids): the request is wrong, 400. */
    @ServerExceptionMapper
    public Response unusablePath(java.nio.file.InvalidPathException e) {
        return problem(RefusedException.BAD_REQUEST, "INVALID_ARGUMENT", "Not a usable path");
    }

    /**
     * A body that is JSON but not the shape the route reads (a number where an object goes): 400 with the usual body.
     * Quarkus has its own mapper for the mismatch, which answers 400 with no body; this one is asked first.
     */
    @ServerExceptionMapper(priority = jakarta.ws.rs.Priorities.USER - 1)
    public Response unreadableBody(com.fasterxml.jackson.databind.exc.MismatchedInputException e) {
        return problem(RefusedException.BAD_REQUEST, "BAD_REQUEST", "The body is not the JSON this route reads");
    }

    /**
     * Git that had to succeed and did not (a commit, a merge, a read the request needs): 409 {@code GIT_FAILED}, the
     * message naming the command and what git said. The one place a {@link GitFailure} becomes a status.
     */
    @ServerExceptionMapper
    public Response git(GitFailure e) {
        LOG.warnf("%s (exit %d)", e.getMessage(), e.exitCode());
        return problem(RefusedException.CONFLICT, "GIT_FAILED", e.getMessage());
    }

    /** A path the workshop, the deliverables or the director's files refused: 400, as an agent's would be. */
    @ServerExceptionMapper
    public Response outside(OutsideWorkspace e) {
        return problem(RefusedException.BAD_REQUEST, "OUTSIDE_WORKSPACE", e.getMessage());
    }

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(ErrorMappers.class);

    /** Another process holds the journal file: said as such, so the director knows what to close. */
    @ServerExceptionMapper
    public Response locked(dev.floor42.server.journal.JournalLockedException e) {
        return problem(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), "JOURNAL_LOCKED", e.getMessage());
    }

    /**
     * Anything no other mapper took: 500 {@code INTERNAL_ERROR} with a generic sentence. The detail (class, message,
     * stack) goes to the log, never to the body.
     */
    @ServerExceptionMapper
    public Response unexpected(Throwable e) {
        LOG.error("Unhandled exception", e);
        return problem(500, "INTERNAL_ERROR", "Something went wrong on the daemon; the details are in its log");
    }

    @ServerExceptionMapper
    public Response storage(JournalException e) {
        return problem(
                Response.Status.SERVICE_UNAVAILABLE.getStatusCode(),
                "JOURNAL_UNAVAILABLE",
                "The event journal could not be written; nothing was recorded");
    }
}
