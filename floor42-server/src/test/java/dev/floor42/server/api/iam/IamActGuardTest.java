package dev.floor42.server.api.iam;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.server.iam.BrokerTestBase;
import dev.floor42.server.iam.IamSessionProfile;
import dev.floor42.server.iam.ResourceCatalogue.Resource;
import dev.floor42.server.security.LocalAccessGuard;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * In IAM mode a grant, a resource's new address and a change of the identity provider are assured acts (ADR-0081): a
 * token, the director's own included, and a break-glass cookie are refused, and nothing is written. What widens nothing
 * keeps the click.
 */
@QuarkusTest
@TestProfile(IamSessionProfile.class)
class IamActGuardTest extends BrokerTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    dev.floor42.server.security.SessionToken directorToken;

    private RequestSpecification bearer() {
        return given().header("Authorization", "Bearer " + directorToken.value())
                .contentType("application/json");
    }

    private RequestSpecification breakGlass() {
        String cookie = given().config(io.restassured.config.RestAssuredConfig.config()
                        .redirect(io.restassured.config.RedirectConfig.redirectConfig()
                                .followRedirects(false)))
                .header("Authorization", "Bearer " + directorToken.value())
                .post("/auth")
                .getDetailedCookie(LocalAccessGuard.COOKIE)
                .getValue();
        return given().contentType("application/json")
                .cookie(LocalAccessGuard.COOKIE, cookie)
                .header("Origin", "http://127.0.0.1:" + io.restassured.RestAssured.port)
                .header("X-Floor42-Request", "1");
    }

    @Test
    void aGrantThatWidensIsRefusedToATokenAndToABreakGlassSessionAndWritesNothing() throws Exception {
        brokerSetup(List.of(RESOURCE), List.of());
        String body = "{\"names\":[\"" + RESOURCE + "\"]}";
        bearer().body(body)
                .put("/api/iam/agents/chris/grants")
                .then()
                .statusCode(403)
                .body("code", equalTo("HUMAN_ONLY"));
        breakGlass()
                .body(body)
                .put("/api/iam/agents/chris/grants")
                .then()
                .statusCode(403)
                .body("code", equalTo("STEP_UP_NEEDS_SIGN_IN"));
        assertThat(catalogue.grantsOf()).doesNotContainKey("chris");
    }

    @Test
    void takingAGrantBackKeepsTheClick() throws Exception {
        brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        bearer().body("{\"names\":[]}")
                .put("/api/iam/agents/chris/grants")
                .then()
                .statusCode(200);
        assertThat(catalogue.grantsOf().getOrDefault("chris", List.of())).isEmpty();
    }

    @Test
    void anotherAddressForAResourceIsAssuredAndTheSameAddressIsNot() throws Exception {
        brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        Resource now = catalogue.find(RESOURCE).orElseThrow();
        Resource moved = new Resource(
                now.name(),
                URI.create("http://127.0.0.1:1"),
                now.audience(),
                now.scopes(),
                now.methods(),
                now.pathPrefixes(),
                now.maxResponseBytes(),
                now.local());
        bearer().body(JSON.writeValueAsString(moved))
                .put("/api/iam/resources")
                .then()
                .statusCode(403)
                .body("code", equalTo("HUMAN_ONLY"));
        breakGlass()
                .body(JSON.writeValueAsString(moved))
                .put("/api/iam/resources")
                .then()
                .statusCode(403)
                .body("code", equalTo("STEP_UP_NEEDS_SIGN_IN"));
        assertThat(catalogue.find(RESOURCE).orElseThrow().baseUrl()).isEqualTo(now.baseUrl());
        bearer().body(JSON.writeValueAsString(now))
                .put("/api/iam/resources")
                .then()
                .statusCode(200);
    }

    @Test
    void changingTheIssuerOrTurningIamOffIsAssuredButATokenLifetimeIsNot() {
        var status = bearer().get("/api/iam/status");
        String issuer = status.path("issuer");
        boolean saved = status.path("savedEnabled");
        String same = "{\"enabled\":" + saved + ",\"issuer\":\"" + issuer + "\",\"maxTokenHours\":7}";
        String other = "{\"enabled\":" + saved + ",\"issuer\":\"http://localhost:1/realms/other\",\"maxTokenHours\":7}";
        String flipped = "{\"enabled\":" + !saved + ",\"issuer\":\"" + issuer + "\",\"maxTokenHours\":7}";
        bearer().body(same).put("/api/iam/settings").then().statusCode(200);
        for (String body : List.of(other, flipped)) {
            bearer().body(body).put("/api/iam/settings").then().statusCode(403).body("code", equalTo("HUMAN_ONLY"));
            breakGlass()
                    .body(body)
                    .put("/api/iam/settings")
                    .then()
                    .statusCode(403)
                    .body("code", equalTo("STEP_UP_NEEDS_SIGN_IN"));
        }
        bearer().get("/api/iam/status")
                .then()
                .body("savedEnabled", equalTo(saved))
                .body("issuer", equalTo(issuer));
    }
}
