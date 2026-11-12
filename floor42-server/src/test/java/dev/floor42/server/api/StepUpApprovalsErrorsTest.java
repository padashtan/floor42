package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.MissionId;
import dev.floor42.server.iam.Delegations;
import dev.floor42.server.iam.IamOnProfile;
import dev.floor42.server.iam.StepUpApprovals;
import dev.floor42.server.iam.StepUpClock;
import dev.floor42.server.security.LocalAccessGuard;
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
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The error branches of the password step-up: expired, replaced, replayed, other mission, wrong nonce. */
@QuarkusTest
@TestProfile(IamOnProfile.class)
class StepUpApprovalsErrorsTest extends ApiTestBase {

    private static final RestAssuredConfig NO_REDIRECT =
            RestAssuredConfig.config().redirect(RedirectConfig.redirectConfig().followRedirects(false));
    private static final HttpClient BROWSER = HttpClient.newHttpClient();

    @Inject
    Delegations delegations;

    @Inject
    StepUpApprovals stepUp;

    @AfterEach
    void normal() {
        StepUpClock.reset(stepUp);
        IamOnProfile.fake.idTokenNonce(null);
    }

    private String newMission() throws Exception {
        return api().body("{\"title\":\"Delegated\",\"objective\":\"Describe\",\"repository\":\""
                        + LiveMissionTest.gitRepository().toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private String status(String id) {
        return api().get("/api/missions/" + id).then().statusCode(200).extract().path("status");
    }

    private static String callbackOf(String authorizationUrl) throws Exception {
        HttpResponse<String> back = BROWSER.send(
                HttpRequest.newBuilder(URI.create(authorizationUrl)).build(), HttpResponse.BodyHandlers.ofString());
        URI callback = URI.create(back.headers().firstValue("Location").orElseThrow());
        return callback.getPath() + "?" + callback.getRawQuery();
    }

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

    private RequestSpecification browser(String cookie) {
        return given().cookie(LocalAccessGuard.COOKIE, cookie)
                .header(LocalAccessGuard.REQUEST_HEADER, "1")
                .header("Origin", "http://127.0.0.1:" + io.restassured.RestAssured.port)
                .contentType("application/json");
    }

    /** The click: the path to follow for this mission. */
    private String click(String cookie, String id) {
        return browser(cookie)
                .body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(202)
                .extract()
                .path("redirect");
    }

    /** The redirect to Keycloak; the answer is the callback path the fake Keycloak would send the browser to. */
    private String callbackFor(String cookie, String redirect) throws Exception {
        Response begun = given().config(NO_REDIRECT)
                .cookie(LocalAccessGuard.COOKIE, cookie)
                .get(redirect);
        begun.then().statusCode(302);
        String path = callbackOf(begun.header("Location"));
        preCookies.put(path, begun.getCookies());
        return path;
    }

    /** The browser comes back to the callback with the pre-sign-in cookie its start gave it. */
    private Response hit(String callback) {
        return given().config(NO_REDIRECT)
                .cookies(preCookies.getOrDefault(callback, Map.of()))
                .get(callback);
    }

    private final Map<String, Map<String, String>> preCookies = new HashMap<>();

    private void assertNotApproved(String id) {
        assertThat(status(id)).isEqualTo("AWAITING_APPROVAL");
        assertThat(delegations.find(new MissionId(id))).isEmpty();
    }

    @Test
    void anExpiredStepUpIsRefused() throws Exception {
        String id = newMission();
        String cookie = signedInCookie();
        String redirect = click(cookie, id);
        String callback = callbackFor(cookie, redirect);
        StepUpClock.advance(stepUp, Duration.ofMinutes(6));
        hit(callback).then().statusCode(401).body("code", equalTo("SIGN_IN_STATE_UNKNOWN"));
        assertNotApproved(id);
        // The pending approval itself has expired too.
        given().config(NO_REDIRECT)
                .cookie(LocalAccessGuard.COOKIE, cookie)
                .get(redirect)
                .then()
                .statusCode(401)
                .body("code", equalTo("DELEGATION_APPROVAL_UNKNOWN"));
    }

    @Test
    void aSecondClickReplacesTheFirstAndTheOldFlowIsRefused() throws Exception {
        String id = newMission();
        String cookie = signedInCookie();
        String first = callbackFor(cookie, click(cookie, id));
        String second = callbackFor(cookie, click(cookie, id));
        hit(first).then().statusCode(401).body("code", equalTo("DELEGATION_APPROVAL_UNKNOWN"));
        assertNotApproved(id);
        // The refusal of the old flow did not drop the newer approval.
        hit(second).then().statusCode(200);
        assertThat(status(id)).isNotEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void anApprovalOfOneMissionNeverApprovesAnother() throws Exception {
        String a = newMission();
        String b = newMission();
        String cookie = signedInCookie();
        String redirectA = click(cookie, a);
        String redirectB = redirectA.replace(a, b);
        given().config(NO_REDIRECT)
                .cookie(LocalAccessGuard.COOKIE, cookie)
                .get(redirectB)
                .then()
                .statusCode(401)
                .body("code", equalTo("DELEGATION_APPROVAL_UNKNOWN"));
        hit(callbackFor(cookie, redirectA)).then().statusCode(200);
        assertThat(status(a)).isNotEqualTo("AWAITING_APPROVAL");
        assertNotApproved(b);
    }

    @Test
    void aReplayOfASuccessfulCallbackIsRefused() throws Exception {
        String id = newMission();
        String cookie = signedInCookie();
        String callback = callbackFor(cookie, click(cookie, id));
        hit(callback).then().statusCode(200);
        var granted = delegations.find(new MissionId(id)).orElseThrow();
        hit(callback).then().statusCode(401).body("code", equalTo("SIGN_IN_STATE_UNKNOWN"));
        assertThat(delegations.find(new MissionId(id))).contains(granted);
    }

    @Test
    void anIdTokenWithAnotherNonceLeavesThePlanAwaitingApproval() throws Exception {
        String id = newMission();
        String cookie = signedInCookie();
        String redirect = click(cookie, id);
        IamOnProfile.fake.idTokenNonce("not-the-nonce");
        hit(callbackFor(cookie, redirect)).then().statusCode(401).body("code", equalTo("SIGN_IN_FAILED"));
        assertNotApproved(id);
    }
}
