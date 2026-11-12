package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Skills and agents (W3): the director's reading only, from an empty user level in tests (never the machine's own
 * {@code ~/.claude}); nothing journaled; MCP servers marked as never reaching the sessions.
 */
@QuarkusTest
class CatalogueApiTest {

    @Inject
    SessionToken token;

    @Test
    void withoutTheDirectorsTokenNothingIsRead() {
        given().get("/api/catalogue").then().statusCode(401);
        given().header("Authorization", "Bearer not-the-token")
                .get("/api/catalogue")
                .then()
                .statusCode(401);
    }

    @Test
    void aReadingShowsTheLaunchAndTheCuratedCatalogue() {

        JsonPath view = given().header("Authorization", "Bearer " + token.value())
                .get("/api/catalogue")
                .then()
                .statusCode(200)
                .body("launch.flags", hasItems("--restricted", "--strict-mcp-config"))
                .body("mission", equalTo(null))
                .body("checked", equalTo("2026-10-31"))
                .extract()
                .jsonPath();

        assertThat(view.getString("readAt")).isNotBlank();
        // The test user level is an empty folder: nothing installed, never the machine's own.
        assertThat(view.getList("installed")).isEmpty();
        List<Map<String, Object>> suggestions = view.getList("suggestions");
        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions)
                .filteredOn(s -> "mcp".equals(s.get("kind")))
                .isNotEmpty()
                .allSatisfy(s -> {
                    assertThat(s.get("state")).isEqualTo("NOT_AVAILABLE");
                    assertThat(s.get("reason")).isEqualTo("strictMcp");
                });
        // Never AVAILABLE for something not installed.
        assertThat(suggestions).extracting(s -> s.get("state")).doesNotContain("AVAILABLE");
        assertThat(suggestions).allSatisfy(s -> {
            assertThat((String) s.get("source")).startsWith("https://github.com/");
            assertThat((List<?>) s.get("install")).isNotEmpty();
        });
    }

    @Test
    void anUnknownMissionIsNotFound() {
        given().header("Authorization", "Bearer " + token.value())
                .get("/api/catalogue?mission=M-404404")
                .then()
                .statusCode(404);
    }

    @Test
    void theInstallCommandNamesThePlugin() {
        assertThat(CatalogueResource.pluginOf(List.of("/plugin marketplace add x/y", "/plugin install tidy@market")))
                .isEqualTo("tidy");
        assertThat(CatalogueResource.pluginOf(List.of("npm i something"))).isNull();
    }
}
