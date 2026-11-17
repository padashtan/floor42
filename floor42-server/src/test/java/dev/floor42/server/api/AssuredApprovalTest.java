package dev.floor42.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.app.core.RefusedException;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a route that guards an assured act answers (ADR-0081): a bearer is refused with the RFC 9470 header, a browser
 * is sent to the step-up, and neither answer is turned into another one by the error mapper.
 */
class AssuredApprovalTest {

    private static Response mapped(RuntimeException e) {
        return e instanceof jakarta.ws.rs.WebApplicationException w
                ? new ErrorMappers(null).web(w, null)
                : new ErrorMappers(null).refused((RefusedException) e);
    }

    @Test
    void aBearerGetsHumanOnlyWithTheRfc9470Header() {
        Response r = mapped(AssuranceResponses.humanOnly());
        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(((ErrorMappers.Problem) r.getEntity()).code()).isEqualTo("HUMAN_ONLY");
        assertThat(r.getHeaderString("WWW-Authenticate"))
                .isEqualTo(
                        "Bearer error=\"insufficient_user_authentication\", acr_values=\"floor42-strong\", max_age=0");
    }

    @Test
    void aBrowserIsSentToTheStepUpWith202() {
        Response r = mapped(AssuranceResponses.followStepUp("/auth/keycloak/assure?act=abc"));
        assertThat(r.getStatus()).isEqualTo(202);
        assertThat(r.getEntity()).isEqualTo(Map.of("redirect", "/auth/keycloak/assure?act=abc"));
    }

    @Test
    void aSignInByTheConsoleLinkCannotSignAnAct() {
        Response r = mapped(AssuranceResponses.needsSignIn());
        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(((ErrorMappers.Problem) r.getEntity()).code()).isEqualTo("STEP_UP_NEEDS_SIGN_IN");
    }

    @Test
    void aRefusalOfTheActItselfKeepsItsStatusAndCode() {
        dev.floor42.server.iam.AssuredFailure f =
                AssuranceResponses.failure(RefusedException.conflict("QUESTION_CLOSED", "closed"));
        assertThat(f).hasMessage("closed");
    }
}
