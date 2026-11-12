package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The API console sees every call, refused ones included, and never shows the session token. */
@QuarkusTest
class ApiConsoleTest {

    @Inject
    SessionToken token;

    @Inject
    ApiCalls calls;

    ApiCalls.Call last(String pathPrefix) {
        return calls.recent(ApiCalls.CAPACITY).reversed().stream()
                .filter(c -> c.path().startsWith(pathPrefix))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void callsAreRecordedWithTheirCallerAndStatus() {
        given().header("Authorization", "Bearer " + token.value())
                .get("/api/missions")
                .then()
                .statusCode(200);
        given().get("/api/missions").then().statusCode(401);

        List<ApiCalls.Call> missions = calls.recent(ApiCalls.CAPACITY).stream()
                .filter(c -> c.path().equals("/api/missions"))
                .toList();
        assertThat(missions.getLast().status()).isEqualTo(401);
        assertThat(missions.getLast().caller()).isEqualTo("anonymous");
        assertThat(missions.get(missions.size() - 2).status()).isEqualTo(200);
        assertThat(missions.get(missions.size() - 2).caller()).isEqualTo("script");
        assertThat(missions.getLast().method()).isEqualTo("GET");
    }

    @Test
    void theTokenNeverAppears() {
        given().redirects()
                .follow(false)
                .get("/auth?token=" + token.value())
                .then()
                .statusCode(400);

        ApiCalls.Call login = last("/auth");
        assertThat(login.path()).isEqualTo("/auth?token=***").doesNotContain(token.value());
        assertThat(calls.recent(ApiCalls.CAPACITY)).noneMatch(c -> c.path().contains(token.value()));
    }

    @Test
    void theHistoryIsServedToTheDirectorOnly() {
        given().header("Authorization", "Bearer " + token.value())
                .get("/api/calls?limit=5")
                .then()
                .statusCode(200);
        given().get("/api/calls").then().statusCode(401);
    }

    @Test
    void aCallKeepsItsBodiesForTheConsole() {
        given().header("Authorization", "Bearer " + token.value())
                .contentType("application/json")
                .body("{\"text\":\"payload in the console\"}")
                .post("/api/notes")
                .then()
                .statusCode(200);
        ApiCalls.Call post = calls.recent(ApiCalls.CAPACITY).reversed().stream()
                .filter(c -> c.path().equals("/api/notes") && c.method().equals("POST"))
                .findFirst()
                .orElseThrow();

        ApiCalls.CallDetail detail = given().header("Authorization", "Bearer " + token.value())
                .get("/api/calls/" + post.sequence())
                .then()
                .statusCode(200)
                .extract()
                .as(ApiCalls.CallDetail.class);

        assertThat(detail.requestBody()).isEqualTo("{\"text\":\"payload in the console\"}");
        assertThat(detail.responseBody()).contains("payload in the console");
        assertThat(detail.truncated()).isFalse();
        given().header("Authorization", "Bearer " + token.value())
                .get("/api/calls/999999999")
                .then()
                .statusCode(404);
    }

    @Test
    void staticFilesAreNotApiCalls() {
        assertThat(ApiCalls.worthLogging("/assets/index.js")).isFalse();
        assertThat(ApiCalls.worthLogging("/")).isFalse();
        assertThat(ApiCalls.worthLogging("/hooks/pre-tool-use")).isTrue();
        assertThat(ApiCalls.worthLogging("/ws/events")).isTrue();
    }

    @Test
    void aSecretSentToTheApiNeverReachesTheConsole() {
        String secret = "console-must-not-keep-this-7c1e";
        given().header("Authorization", "Bearer " + token.value())
                .contentType("application/json")
                .body("{\"secret\":\"" + secret + "\"}")
                .put("/api/iam/secrets/api")
                .then()
                .statusCode(200);
        given().header("Authorization", "Bearer " + token.value())
                .contentType("application/json")
                .body("{\"enabled\":false,\"issuer\":\"http://localhost:8180/realms/floor42\",\"maxTokenHours\":8,"
                        + "\"daemonSecret\":\"" + secret + "\",\"apiSecret\":\"" + secret + "\"}")
                .put("/api/iam/settings")
                .then()
                .statusCode(200);
        List<ApiCalls.Call> puts = calls.recent(ApiCalls.CAPACITY).stream()
                .filter(c -> c.method().equals("PUT") && c.path().startsWith("/api/iam/"))
                .toList();
        assertThat(puts).hasSizeGreaterThanOrEqualTo(2);
        for (ApiCalls.Call put : puts) {
            String detail = given().header("Authorization", "Bearer " + token.value())
                    .get("/api/calls/" + put.sequence())
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString();
            assertThat(detail).doesNotContain(secret).doesNotContain("requestBody\":\"{");
        }
    }

    @Test
    void secretValuesAreMaskedWhateverTheirFormAndNothingElseIs() {
        assertThat(ConsoleMask.masked("{\"apiSecret\":\"a\\\"b\",\"text\":\"kept\"}"))
                .isEqualTo("{\"apiSecret\":\"***\",\"text\":\"kept\"}");
        assertThat(ConsoleMask.masked("{\"\u0073ecret\":\"LEAK1\"}")).doesNotContain("LEAK1");
        assertThat(ConsoleMask.masked("{\"secret\":12345}")).doesNotContain("12345");
        assertThat(ConsoleMask.masked("{\"secret\":[\"LEAK3\"],\"a\":[{\"PassWord\":{\"v\":\"LEAK4\"}}]}"))
                .doesNotContain("LEAK3")
                .doesNotContain("LEAK4");
        assertThat(ConsoleMask.masked("{\"password\" : \"cut-by-the-lim")).isEqualTo(ConsoleMask.WITHHELD);
        assertThat(ConsoleMask.masked("{\"secret\":\"LEAK5\\")).isEqualTo(ConsoleMask.WITHHELD);
        assertThat(ConsoleMask.masked("{\"\u0073ecret\":\"LEAK6")).isEqualTo(ConsoleMask.WITHHELD);
        assertThat(ConsoleMask.masked("grant_type=x&client_secret=abc&refresh_token=def"))
                .isEqualTo("grant_type=x&client_secret=***&refresh_token=***");
        String plain = "{\"title\":\"x=y\",\"url\":\"http://a/b?c=d&e=f\",\"cost\":0.10}";
        assertThat(ConsoleMask.masked(plain)).isEqualTo(plain);
        assertThat(ConsoleMask.masked("{\"title\":\"cut")).isEqualTo("{\"title\":\"cut");
    }
}
