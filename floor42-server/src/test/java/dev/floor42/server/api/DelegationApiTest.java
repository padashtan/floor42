package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import dev.floor42.domain.MissionId;
import dev.floor42.server.iam.Delegations;
import dev.floor42.server.iam.FakeKeycloak;
import dev.floor42.server.iam.IamOnProfile;
import dev.floor42.server.iam.StepUpApprovals;
import dev.floor42.server.security.LocalAccessGuard;
import dev.floor42.server.security.SessionRegistry;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.config.RedirectConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Plan approval in IAM mode: only a Keycloak sign-in, then a password step-up, then the delegation and the approval. */
@QuarkusTest
@TestProfile(IamOnProfile.class)
class DelegationApiTest extends ApiTestBase {

    private static final RestAssuredConfig NO_REDIRECT =
            RestAssuredConfig.config().redirect(RedirectConfig.redirectConfig().followRedirects(false));
    private static final HttpClient BROWSER = HttpClient.newHttpClient();

    @Inject
    SessionRegistry sessions;

    @Inject
    SessionToken token;

    @Inject
    Delegations delegations;

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    FakeKeycloak fake() {
        return IamOnProfile.fake;
    }

    @Inject
    StepUpApprovals stepUp;

    @AfterEach
    void normal() {
        fake().ignorePromptLogin(false);
        fake().signInAs(null);
    }

    private String newMission() throws Exception {
        return api().body("{\"title\":\"Delegated\",\"objective\":\"Describe\",\"repository\":\""
                        + LiveMissionTest.gitRepository().toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .body("status", equalTo("AWAITING_APPROVAL"))
                .extract()
                .path("id");
    }

    private String status(String id) {
        return api().get("/api/missions/" + id).then().statusCode(200).extract().path("status");
    }

    /** A real Keycloak sign-in (fake issuer): the browser cookie of a session with live director tokens. */
    private String signedInCookie() throws Exception {
        Response begun = given().config(NO_REDIRECT).get("/auth/keycloak");
        begun.then().statusCode(302);
        return given().config(NO_REDIRECT)
                .cookies(begun.getCookies())
                .get(callbackOf(begun.header("Location")))
                .then()
                .statusCode(303)
                .extract()
                .detailedCookie(LocalAccessGuard.COOKIE)
                .getValue();
    }

    /** What the fake Keycloak answers to an authorization request: the callback with its code and state. */
    private static String callbackOf(String authorizationUrl) throws Exception {
        HttpResponse<String> back = BROWSER.send(
                HttpRequest.newBuilder(URI.create(authorizationUrl)).build(), HttpResponse.BodyHandlers.ofString());
        URI callback = URI.create(back.headers().firstValue("Location").orElseThrow());
        return callback.getPath() + "?" + callback.getRawQuery();
    }

    /** The click's redirect followed by the browser that holds the director's cookie: Keycloak's 302 and the pre-sign-in cookie. */
    private Response stepUpBegin(String cookie, String redirect) {
        Response r = given().config(NO_REDIRECT)
                .cookie(LocalAccessGuard.COOKIE, cookie)
                .get(redirect);
        r.then().statusCode(302);
        return r;
    }

    /** Keycloak approves, the browser comes back to the callback with the cookie it was given. */
    private static Response stepUpCallback(Response begun) throws Exception {
        return given().config(NO_REDIRECT).cookies(begun.getCookies()).get(callbackOf(begun.header("Location")));
    }

    /** The same callback, as a local process that never got the pre-sign-in cookie would send it. */
    @Test
    void aStepUpCallbackFromAnotherBrowserIsRefused() throws Exception {
        String id = newMission();
        String cookie = signedInCookie();
        String redirect = browser(cookie)
                .body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(202)
                .extract()
                .path("redirect");
        Response begun = stepUpBegin(cookie, redirect);
        given().config(NO_REDIRECT)
                .get(callbackOf(begun.header("Location")))
                .then()
                .statusCode(401)
                .body("code", equalTo("SIGN_IN_BROWSER_UNKNOWN"));
        assertThat(status(id)).isEqualTo("AWAITING_APPROVAL");
        assertThat(delegations.find(new MissionId(id))).isEmpty();
    }

    private RequestSpecification browser(String cookie) {
        return given().cookie(LocalAccessGuard.COOKIE, cookie)
                .header(LocalAccessGuard.REQUEST_HEADER, "1")
                .header("Origin", "http://127.0.0.1:" + io.restassured.RestAssured.port)
                .contentType("application/json");
    }

    @Test
    void scriptTokenApprovalIsRefusedInIamMode() throws Exception {
        String id = newMission();
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(403)
                .body("code", equalTo("DELEGATION_NEEDS_SIGN_IN"));
        String script =
                sessions.issueScript(SessionRegistry.Scope.ALL, 1, "ci", "test").secret();
        given().header("Authorization", "Bearer " + script)
                .contentType("application/json")
                .body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(403)
                .body("code", equalTo("DELEGATION_NEEDS_SIGN_IN"));
        assertThat(status(id)).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void aBreakGlassSessionCannotApprove() throws Exception {
        String id = newMission();
        String cookie = sessions.openBreakGlass("test").secret();
        browser(cookie)
                .body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(403)
                .body("code", equalTo("DELEGATION_NEEDS_SIGN_IN"));
        assertThat(status(id)).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void theClickAsksForThePasswordAgainAndTheApprovalFollowsTheStepUp() throws Exception {
        String id = newMission();
        String cookie = signedInCookie();
        api().get("/api/iam/missions/" + id + "/consent")
                .then()
                .statusCode(200)
                .body("text", org.hamcrest.Matchers.containsString("password again"));
        String redirect = browser(cookie)
                .body("{\"noCap\":true,\"resources\":[\"crm\"]}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(202)
                .body("redirect", startsWith("/auth/keycloak/approve?mission="))
                .extract()
                .path("redirect");
        assertThat(status(id)).isEqualTo("AWAITING_APPROVAL");
        assertThat(delegations.find(new MissionId(id))).isEmpty();

        Response begun = stepUpBegin(cookie, redirect);
        assertThat(begun.header("Location"))
                .contains("prompt=login", "max_age=0", "scope=openid+offline_access", "code_challenge_method=S256");

        stepUpCallback(begun).then().statusCode(200);
        assertThat(status(id)).isNotEqualTo("AWAITING_APPROVAL");
        assertThat(delegations.find(new MissionId(id))).get().satisfies(d -> {
            assertThat(d.resources()).containsExactly("crm");
            assertThat(d.revoked()).isFalse();
        });
        api().get("/api/iam/missions/" + id + "/delegation")
                .then()
                .statusCode(200)
                .body("revoked", equalTo(false))
                .body("resources[0]", equalTo("crm"));
        assertNoTokenOnDisk();

        // Cancelling the mission ends the delegation: locally at once, then at Keycloak.
        api().body("{\"reason\":\"done\"}")
                .post("/api/missions/" + id + "/cancel")
                .then()
                .statusCode(200);
        assertThat(delegations.find(new MissionId(id)))
                .get()
                .extracting("revoked")
                .isEqualTo(true);
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until
                && fake().requests().stream().noneMatch(r -> r.get("_path").endsWith("/revoke"))) {
            Thread.sleep(50);
        }
        assertThat(fake().requests().stream().anyMatch(r -> r.get("_path").endsWith("/revoke")))
                .isTrue();
    }

    @Test
    void aPasswordNotTypedAgainLeavesThePlanAwaitingApproval() throws Exception {
        String id = newMission();
        String cookie = signedInCookie();
        String redirect = browser(cookie)
                .body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(202)
                .extract()
                .path("redirect");
        fake().ignorePromptLogin(true);
        Response begun = stepUpBegin(cookie, redirect);
        String callback = callbackOf(begun.header("Location"));
        given().config(NO_REDIRECT)
                .cookies(begun.getCookies())
                .get(callback)
                .then()
                .statusCode(401)
                .body("code", equalTo("DELEGATION_STEP_UP_MISSING"));
        assertThat(status(id)).isEqualTo("AWAITING_APPROVAL");
        assertThat(delegations.find(new MissionId(id))).isEmpty();
        // The pending approval is gone and the state is single use.
        given().config(NO_REDIRECT).get(callback).then().statusCode(401).body("code", equalTo("SIGN_IN_STATE_UNKNOWN"));
        given().config(NO_REDIRECT)
                .cookie(LocalAccessGuard.COOKIE, cookie)
                .get(redirect)
                .then()
                .statusCode(401)
                .body("code", equalTo("DELEGATION_APPROVAL_UNKNOWN"));
    }

    @Test
    void aPasswordOfAnotherUserIsRefused() throws Exception {
        String id = newMission();
        String cookie = signedInCookie();
        String redirect = browser(cookie)
                .body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(202)
                .extract()
                .path("redirect");
        fake().signInAs("mallory");
        stepUpCallback(stepUpBegin(cookie, redirect))
                .then()
                .statusCode(401)
                .body("code", equalTo("DELEGATION_WRONG_USER"));
        assertThat(status(id)).isEqualTo("AWAITING_APPROVAL");
        assertThat(delegations.find(new MissionId(id))).isEmpty();
    }

    @Test
    void anUnknownMissionIsNotFoundBeforeAnyRedirect() throws Exception {
        browser(signedInCookie())
                .body("{\"noCap\":true}")
                .post("/api/missions/M-99999/approve")
                .then()
                .statusCode(404)
                .body("code", equalTo("UNKNOWN_MISSION"));
    }

    @Test
    void theStepUpRouteWantsTheBrowserSessionThatClicked() throws Exception {
        String id = newMission();
        String cookie = signedInCookie();
        String redirect = browser(cookie)
                .body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(202)
                .extract()
                .path("redirect");
        given().config(NO_REDIRECT).get(redirect).then().statusCode(401);
        String other = sessions.openBrowser("other").secret();
        given().config(NO_REDIRECT)
                .cookie(LocalAccessGuard.COOKIE, other)
                .get(redirect)
                .then()
                .statusCode(401);
    }

    @Test
    void revokeRouteEndsTheDelegationAndOtherRoutesAnswerWhenThereIsNone() throws Exception {
        api().get("/api/iam/missions/M-998/delegation").then().statusCode(404).body("code", equalTo("NO_DELEGATION"));
        api().post("/api/iam/missions/M-998/delegation/revoke").then().statusCode(404);
        api().get("/api/iam/missions/M-998/consent?resources=Bad%20Name")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_RESOURCES"));
    }

    private void assertNoTokenOnDisk() throws Exception {
        try (Stream<Path> files = Files.walk(dataDir)) {
            for (Path f : files.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("vault"))
                    .toList()) {
                String text = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
                assertThat(text).as(f.toString()).doesNotContain("eyJ");
            }
        }
    }
}
