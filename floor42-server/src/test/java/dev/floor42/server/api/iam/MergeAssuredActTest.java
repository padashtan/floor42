package dev.floor42.server.api.iam;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.server.iam.IamSessionProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Merging a pull request is an assured act (ADR-0084, ADR-0081): in IAM mode a token, the director's own bearer
 * included, is refused before anything is read from GitHub.
 */
@QuarkusTest
@TestProfile(IamSessionProfile.class)
class MergeAssuredActTest {

    @Inject
    dev.floor42.server.security.SessionToken directorToken;

    @Test
    void aBearerIsRefusedHumanOnlyInIamModeWhateverTheMission() {
        given().header("Authorization", "Bearer " + directorToken.value())
                .contentType("application/json")
                .body("{\"commit\":\"" + "a".repeat(40) + "\"}")
                .post("/api/missions/M-1/pull-requests/7/merge")
                .then()
                .statusCode(403)
                .body("code", equalTo("HUMAN_ONLY"));
    }
}
