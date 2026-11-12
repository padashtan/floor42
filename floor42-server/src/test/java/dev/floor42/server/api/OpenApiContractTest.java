package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The web UI's types are checked against a snapshot of the API contract (floor42-web/src/api/openapi.json). When the
 * server's API changes, this test fails until the snapshot is refreshed: run the build with -Dfloor42.openapi.write=true,
 * then `npm run api:types` in floor42-web. A renamed field then breaks the UI's type check instead of the screen.
 */
@QuarkusTest
class OpenApiContractTest {

    /** The documentation needs the director, like the API it describes. */
    @jakarta.inject.Inject
    dev.floor42.server.security.SessionToken token;

    static final Path SNAPSHOT = Path.of("..", "floor42-web", "src", "api", "openapi.json");

    @Test
    void theUiSnapshotMatchesTheServedContract() throws Exception {
        String served = given().header("Authorization", "Bearer " + token.value())
                .get("/q/openapi?format=json")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        ObjectMapper json = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        JsonNode contract = json.readTree(served);
        if (Boolean.getBoolean("floor42.openapi.write") || Boolean.getBoolean("office.openapi.write")) {
            Files.writeString(SNAPSHOT, json.writeValueAsString(json.treeToValue(contract, Object.class)) + "\n");
        }

        assertThat(json.readTree(Files.readString(SNAPSHOT)))
                .as("The API changed: refresh %s (see this test's documentation)", SNAPSHOT)
                .isEqualTo(contract);
    }

    @Test
    void routesNeedTheSessionTokenExceptTheStatus() throws Exception {
        JsonNode paths = new ObjectMapper()
                .readTree(given().header("Authorization", "Bearer " + token.value())
                        .get("/q/openapi?format=json")
                        .asString())
                .path("paths");

        assertThat(paths.path("/api/missions").path("get").path("security").toString())
                .contains("cookieAuth", "bearerToken")
                .doesNotContain("agentBearer");
        assertThat(paths.path("/api/status").path("get").has("security")).isFalse();
        assertThat(paths.path("/hooks/pre-tool-use")
                        .path("post")
                        .path("security")
                        .toString())
                .contains("agentBearer")
                .doesNotContain("cookieAuth");
        assertThat(paths.path("/mcp").path("post").path("security").toString()).contains("agentBearer");
    }

    /** Only dev mode opens /q/ without the director: the packaged jar and the Docker image never do. */
    @Test
    void quarkusPagesAreOpenOnlyInDevMode() throws Exception {
        for (Path file :
                new Path[] {Path.of("src/main/resources/application.properties"), Path.of("..", "Dockerfile")}) {
            assertThat(Files.readAllLines(file))
                    .as(file.toString())
                    .noneMatch(l -> l.contains("open-quarkus-pages")
                            && !l.startsWith("%dev.")
                            && !l.startsWith("#")
                            && !l.contains("false"));
        }
    }

    @Test
    void sharedErrorsVersionAndStabilityAreInTheContract() throws Exception {
        JsonNode doc = new ObjectMapper()
                .readTree(given().header("Authorization", "Bearer " + token.value())
                        .get("/q/openapi?format=json")
                        .asString());
        JsonNode paths = doc.path("paths");
        assertThat(doc.path("info").path("version").asText())
                .isNotEqualTo("0.1.0")
                .isEqualTo(org.eclipse.microprofile.config.ConfigProvider.getConfig()
                        .getValue("quarkus.application.version", String.class));
        assertThat(doc.path("info").path("description").asText())
                .contains("Idempotency-Key", "x-stability", "MISSION_WRITES", "200");
        assertThat(doc.path("components").path("schemas").has("Problem")).isTrue();
        JsonNode scoped = paths.path("/api/missions/{id}/approve").path("post").path("responses");
        for (String code : new String[] {"401", "403", "404", "409", "503"}) {
            assertThat(scoped.has(code)).as(code).isTrue();
        }
        assertThat(paths.path("/api/missions").path("post").path("responses").has("413"))
                .isTrue();
        assertThat(paths.path("/api/iam/status").path("get").path("x-stability").asText())
                .isEqualTo("unstable");
        assertThat(paths.path("/api/missions").path("get").has("x-stability")).isFalse();
        assertThat(paths.path("/api/journal").path("get").has("x-stability")).isFalse();
    }
}
