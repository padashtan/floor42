package dev.floor42.server.api;

import dev.floor42.domain.AssurancePolicy;
import dev.floor42.server.app.core.RefusedException;
import dev.floor42.server.iam.AssuredFailure;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

/** The answers of a route that guards an assured act (ADR-0081): the step-up to follow, or the human-only refusal. */
public final class AssuranceResponses {

    /** RFC 9470: why a bearer caller is refused, and what a human would have to reach. */
    static final String CHALLENGE = "Bearer error=\"insufficient_user_authentication\", acr_values=\""
            + AssurancePolicy.ACR_STRONG + "\", max_age=0";

    private AssuranceResponses() {}

    /** 403 {@code HUMAN_ONLY} with the RFC 9470 header: a script token or the director's bearer cannot step up in band. */
    public static WebApplicationException humanOnly() {
        return new WebApplicationException(Response.status(403)
                .type(MediaType.APPLICATION_JSON)
                .header("WWW-Authenticate", CHALLENGE)
                .entity(new ErrorMappers.Problem(
                        "HUMAN_ONLY",
                        "This act needs a person at the desk, signed in again with a second factor: a token"
                                + " cannot do it"))
                .build());
    }

    /** 202 {@code {redirect}}, the path of the step-up, as a plan approval answers (the screen follows it). */
    public static WebApplicationException followStepUp(String path) {
        return new WebApplicationException(Response.accepted(Map.of("redirect", path))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }

    /** 403: only a browser signed in through Keycloak, not by the console link, can sign an act. */
    public static RefusedException needsSignIn() {
        return new RefusedException(
                403, "STEP_UP_NEEDS_SIGN_IN", "This act needs the director signed in through Keycloak");
    }

    /** An act's own refusal, as the step-up reports it. */
    public static AssuredFailure failure(RefusedException e) {
        return new AssuredFailure(e.status(), e.code(), e.getMessage());
    }
}
