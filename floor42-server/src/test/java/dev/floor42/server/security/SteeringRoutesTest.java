package dev.floor42.server.security;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.http.HttpMethod;
import jakarta.inject.Inject;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The routes a script token bound to Keycloak may steer in IAM mode (ADR-0083) are a list of patterns. A route renamed
 * or added to the API must not slip through it, or out of it, unseen: the served POST routes the list lets through are
 * exactly the six of the sheet.
 */
@QuarkusTest
class SteeringRoutesTest {

    @Inject
    SessionToken token;

    @Test
    void everyRouteThatStartsWorkIsListedAndNothingElseIs() throws Exception {
        JsonNode paths = new ObjectMapper()
                .readTree(given().header("Authorization", "Bearer " + token.value())
                        .get("/q/openapi?format=json")
                        .asString())
                .path("paths");
        Set<String> steered = new TreeSet<>();
        paths.properties().forEach(p -> {
            if (!p.getValue().has("post")) return;
            String sample = p.getKey().replaceAll("\\{[^}]+}", "x");
            // The routes any script token may POST in IAM mode are not the steering list's.
            if (!ScriptTokensInIam.mayCall(HttpMethod.POST, sample)
                    && ScriptTokensInIam.boundMayCall(HttpMethod.POST, sample)) steered.add(p.getKey());
        });
        assertThat(steered)
                .containsExactlyInAnyOrder(
                        "/api/missions/{id}/tickets/approve",
                        "/api/missions/{id}/nudge",
                        "/api/missions/{id}/workshop/directive",
                        "/api/missions/{id}/resume",
                        "/api/questions/{id}/answer",
                        "/api/agents/{id}/resume");
    }
}
