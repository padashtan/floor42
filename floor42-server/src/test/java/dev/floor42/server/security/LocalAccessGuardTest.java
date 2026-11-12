package dev.floor42.server.security;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.RedirectConfig;
import io.restassured.config.RestAssuredConfig;
import jakarta.inject.Inject;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

@QuarkusTest
class LocalAccessGuardTest {

    @Inject
    SessionToken token;

    @Inject
    dev.floor42.server.journal.Journal journal;

    @Inject
    DirectorSignIn signIn;

    static final RestAssuredConfig NO_REDIRECT =
            RestAssuredConfig.config().redirect(RedirectConfig.redirectConfig().followRedirects(false));

    @Test
    void theApiRequiresTheSessionToken() {
        given().get("/api/missions").then().statusCode(401);
        given().header("Authorization", "Bearer wrong")
                .get("/api/missions")
                .then()
                .statusCode(401);
        given().header("Authorization", "Bearer " + token.value())
                .get("/api/missions")
                .then()
                .statusCode(200);
    }

    @Test
    void aConsoleLinkSignInWithIamOffJournalsNoBreakGlassEvent() {
        given().config(NO_REDIRECT)
                .header("Authorization", "Bearer " + token.value())
                .post("/auth")
                .then()
                .statusCode(303);
        assertThat(journal.readAll())
                .noneMatch(e -> e.event() instanceof dev.floor42.domain.event.PreferenceEvent.BreakGlassSignIn);
    }

    @Test
    void theModeBannerIsPublic() {
        given().get("/api/status").then().statusCode(200).body("runtime.available", equalTo(true));
    }

    @Test
    void foreignHostsAreRejectedAgainstDnsRebinding() {
        given().header("Host", "evil.example:7420")
                .header("Authorization", "Bearer " + token.value())
                .get("/api/missions")
                .then()
                .statusCode(403);
    }

    @Test
    void onlyTheOneFixedLocalhostNameIsAccepted() {
        String auth = "Bearer " + token.value();
        given().header("Host", "floor42.localhost:7420")
                .header("Authorization", auth)
                .get("/api/missions")
                .then()
                .statusCode(200);
        given().header("Host", "floor42.localhost:7420")
                .header("Origin", "http://floor42.localhost:7420")
                .header("Authorization", auth)
                .get("/api/missions")
                .then()
                .statusCode(200);
        given().header("Host", "floor42.localhost:7420")
                .header("Origin", "http://127.0.0.1:7420")
                .header("Authorization", auth)
                .get("/api/missions")
                .then()
                .statusCode(403);
        for (String host : new String[] {"evil.localhost:7420", "sub.floor42.localhost:7420"}) {
            given().header("Host", host)
                    .header("Authorization", auth)
                    .get("/api/missions")
                    .then()
                    .statusCode(403);
        }
        assertThat(LocalAccessGuard.signInBase("floor42.localhost:7421", 7420))
                .isEqualTo("http://floor42.localhost:7420");
        assertThat(LocalAccessGuard.signInBase("evil.localhost", 7420)).isEqualTo("http://127.0.0.1:7420");
    }

    @Test
    void otherWebPagesCannotDriveTheDaemon() {
        given().header("Origin", "https://evil.example")
                .header("Authorization", "Bearer " + token.value())
                .post("/api/demo")
                .then()
                .statusCode(403);
        // The exact origin, port included (ADR-0044): the Vite server's is allowed in dev mode only.
        given().header("Origin", "http://127.0.0.1:5173")
                .header("Authorization", "Bearer " + token.value())
                .get("/api/missions")
                .then()
                .statusCode(403);
    }

    @Test
    void theBrowserLogsInWithAnHttpOnlyStrictCookie() {
        var noRedirect = NO_REDIRECT;
        given().config(noRedirect)
                .header("Authorization", "Bearer wrong")
                .post("/auth")
                .then()
                .statusCode(401);
        // A token in a URL is refused, right or wrong: it would sit in the browser history.
        given().config(noRedirect)
                .get("/auth?token=" + token.value())
                .then()
                .statusCode(400)
                .body("code", org.hamcrest.Matchers.equalTo("TOKEN_IN_URL"));

        var response = given().config(noRedirect)
                .header("Authorization", "Bearer " + token.value())
                .post("/auth");
        response.then().statusCode(303).header("Location", "/");
        var cookie = response.getDetailedCookie(LocalAccessGuard.COOKIE);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualToIgnoringCase("Strict");

        given().cookie(LocalAccessGuard.COOKIE, cookie.getValue())
                .get("/api/agents")
                .then()
                .statusCode(200);
    }

    @Test
    void theDirectorTokenIsKeptInMemoryNotInAFile() throws Exception {
        // ADR-0034: agents run as the same OS user, so no director credential is left at rest for them to read.
        assertThat(token.writtenToFile()).isFalse();
        assertThat(token.file()).doesNotExist();
        try (var files = Files.walk(token.file().getParent())) {
            for (var f : files.filter(Files::isRegularFile).toList()) {
                assertThat(new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.ISO_8859_1))
                        .as(f.toString())
                        .doesNotContain(token.value());
            }
        }
    }

    @Test
    void theBrowserSignsInWithASingleUseLinkTheDaemonOpensItself() throws Exception {
        // The link is delivered to the browser (here: kept for the test, which plays the browser), never to the caller.
        var asked = askForALink();
        asked.then().statusCode(202);
        assertThat(asked.body().asString()).isEmpty();
        String link = signIn.lastLink();
        assertThat(link).contains("/auth?code=");
        String path = link.substring(link.indexOf("/auth"));

        var response = given().config(NO_REDIRECT).get(path);
        response.then().statusCode(303).header("Location", "/");
        var cookie = response.getDetailedCookie(LocalAccessGuard.COOKIE);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualToIgnoringCase("Strict");
        assertThat(cookie.getValue()).isNotEqualTo(token.value());
        given().cookie(LocalAccessGuard.COOKIE, cookie.getValue())
                .get("/api/missions")
                .then()
                .statusCode(200);

        // Used a second time, the link is refused and the session opened with it dies: someone else got there first.
        given().config(NO_REDIRECT).get(path).then().statusCode(401);
        given().cookie(LocalAccessGuard.COOKIE, cookie.getValue())
                .get("/api/agents")
                .then()
                .statusCode(401);
    }

    @Test
    void aFullTablePushesTheOldestLinkOut() {
        signIn.clear();
        try {
            signIn.noPause();
            assertThat(signIn.open("http://127.0.0.1:1")).isTrue();
            String first = signIn.lastLink();
            for (int i = 0; i < 40; i++)
                assertThat(signIn.open("http://127.0.0.1:1")).isTrue();
            // The table is bounded: the first link is the one pushed out.
            assertThat(signIn.redeem(first.substring(first.indexOf("code=") + 5), "test"))
                    .isEmpty();
        } finally {
            signIn.clear();
        }
    }

    @Test
    void anotherWebPageCannotAskForASignInLink() {
        given().header("Origin", "https://evil.example")
                .post("/auth/open")
                .then()
                .statusCode(403);
    }

    /** Asks for a link, waiting out the short interval between two links if another test just asked. */
    io.restassured.response.Response askForALink() throws InterruptedException {
        for (int i = 0; ; i++) {
            var r = given().post("/auth/open");
            if (r.statusCode() != 429 || i == 5) return r;
            Thread.sleep(700);
        }
    }

    @Test
    void hostParsing() {
        assertThat(LocalAccessGuard.hostName("127.0.0.1:7420")).isEqualTo("127.0.0.1");
        assertThat(LocalAccessGuard.hostName("[::1]:7420")).isEqualTo("[::1]");
    }

    @Test
    void theSignInLinkUsesTheRealPortNeverTheCallersHostHeader() {
        assertThat(LocalAccessGuard.signInBase("127.0.0.1:6666", 7420)).isEqualTo("http://127.0.0.1:7420");
        assertThat(LocalAccessGuard.signInBase("localhost:1@evil.example", 7420))
                .isEqualTo("http://localhost:7420");
        assertThat(LocalAccessGuard.signInBase("[::1]:9", 7420)).isEqualTo("http://[::1]:7420");
        assertThat(LocalAccessGuard.signInBase("evil.example", 7420)).isEqualTo("http://127.0.0.1:7420");
        assertThat(LocalAccessGuard.signInBase(null, 7420)).isEqualTo("http://127.0.0.1:7420");
    }
}
