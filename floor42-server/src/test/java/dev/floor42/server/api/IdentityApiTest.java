package dev.floor42.server.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.server.iam.SecretVault;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/** IAM mode's routes with the mode off (the default): settings are saved for the next start, secrets never come back. */
@QuarkusTest
class IdentityApiTest extends ApiTestBase {

    private static final String DAEMON_SECRET = "d4emon-secret-do-not-echo-1f2e3d";
    private static final String API_SECRET = "ap1-secret-do-not-echo-9a8b7c";

    @Inject
    SecretVault vault;

    private static String body(String issuer, int hours, String daemon, String apiSecret) {
        return "{\"enabled\":false,\"issuer\":\"" + issuer + "\",\"maxTokenHours\":" + hours + ",\"daemonSecret\":\""
                + daemon + "\",\"apiSecret\":\"" + apiSecret + "\"}";
    }

    @Test
    void settingsAnswerRestartNeededAndNeverEchoTheSecret() {
        String answer = api().body(body("http://localhost:8180/realms/floor42", 5, DAEMON_SECRET, API_SECRET))
                .put("/api/iam/settings")
                .then()
                .statusCode(200)
                .body("restartNeeded", equalTo(true))
                .extract()
                .asString();
        assertThat(answer).doesNotContain(DAEMON_SECRET).doesNotContain(API_SECRET);
        String status =
                api().get("/api/iam/status").then().statusCode(200).extract().asString();
        assertThat(status).doesNotContain(DAEMON_SECRET).doesNotContain(API_SECRET);
        assertThat(vault.get("floor42-daemon")).hasValue(DAEMON_SECRET.getBytes(UTF_8));
        assertThat(vault.get("floor42-api")).hasValue(API_SECRET.getBytes(UTF_8));
    }

    @Test
    void aBlankSecretLeavesTheStoredOneAndHoursAreClamped() {
        api().body(body("http://localhost:8180/realms/floor42", 3, DAEMON_SECRET, ""))
                .put("/api/iam/settings");
        api().body(body("http://localhost:8180/realms/floor42", 12, "", "")).put("/api/iam/settings");
        assertThat(vault.get("floor42-daemon")).hasValue(DAEMON_SECRET.getBytes(UTF_8));
        api().get("/api/iam/status")
                .then()
                .statusCode(200)
                .body("maxTokenHours", equalTo(8))
                .body("enabled", equalTo(false))
                .body("health", equalTo("OFF"));
    }

    @Test
    void anIssuerThatIsNotAnHttpAddressIsRefused() {
        api().body(body("file:///etc/passwd", 8, "", ""))
                .put("/api/iam/settings")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_ISSUER"));
    }

    @Test
    void viewRoutesAnswerIamOffAndTheAuditExportIsEmptyNdjson() {
        api().get("/api/iam/tokens").then().statusCode(409).body("code", equalTo("IAM_OFF"));
        api().get("/api/iam/refusals").then().statusCode(409).body("code", equalTo("IAM_OFF"));
        api().get("/api/sessions/L1/trust-chain").then().statusCode(409).body("code", equalTo("IAM_OFF"));
        api().get("/api/missions/M-1/identity-audit")
                .then()
                .statusCode(200)
                .contentType("application/x-ndjson")
                .body(equalTo(""));
    }

    @Test
    void agentRoutesAnswerIamOffWhileTheModeIsOff() {
        api().get("/api/iam/agents").then().statusCode(409).body("code", equalTo("IAM_OFF"));
        api().post("/api/iam/agents/ada/rotate").then().statusCode(409).body("code", equalTo("IAM_OFF"));
        api().post("/api/iam/agents/ada/revoke").then().statusCode(409).body("code", equalTo("IAM_OFF"));
        api().post("/api/iam/agents/ada/restore").then().statusCode(409).body("code", equalTo("IAM_OFF"));
    }

    @Test
    void theSecretRouteWritesOnlyTheVaultAndTheStatusShowsTheSavedSwitch() {
        String saved =
                api().get("/api/iam/status").then().statusCode(200).extract().asString();
        String answer = api().body("{\"secret\":\"" + API_SECRET + "\"}")
                .put("/api/iam/secrets/api")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertThat(answer).doesNotContain(API_SECRET);
        assertThat(vault.get("floor42-api")).hasValue(API_SECRET.getBytes(UTF_8));
        assertThat(api().get("/api/iam/status").then().extract().asString()).isEqualTo(saved);
        api().body("{\"secret\":\" \"}").put("/api/iam/secrets/daemon").then().statusCode(400);
        api().body("{\"secret\":\"x\"}").put("/api/iam/secrets/other").then().statusCode(400);
        io.restassured.RestAssured.given()
                .contentType("application/json")
                .body("{\"secret\":\"x\"}")
                .put("/api/iam/secrets/api")
                .then()
                .statusCode(org.hamcrest.Matchers.anyOf(equalTo(401), equalTo(403)));

        api().body("{\"enabled\":true,\"issuer\":\"http://localhost:8180/realms/floor42\",\"maxTokenHours\":8}")
                .put("/api/iam/settings")
                .then()
                .statusCode(200)
                .body("restartNeeded", equalTo(true));
        api().get("/api/iam/status").then().body("enabled", equalTo(false)).body("savedEnabled", equalTo(true));
        api().body("{\"enabled\":false,\"issuer\":\"http://localhost:8180/realms/floor42\",\"maxTokenHours\":8}")
                .put("/api/iam/settings");
    }
}
