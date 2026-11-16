package dev.floor42.server.api.journal;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import dev.floor42.server.api.ApiTestBase;
import dev.floor42.server.security.LocalAccessGuard;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.RedirectConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

/** The chain's status is the director's, and says only sequences, hashes and counts (ADR-0079). */
@QuarkusTest
class JournalIntegrityApiTest extends ApiTestBase {

    @Test
    void theDirectorReadsVerifiesAndAcknowledgesAHealthyChain() {
        api().get("/api/journal/integrity")
                .then()
                .statusCode(200)
                .body("status", equalTo("OK"))
                .body("writesRefused", equalTo(false))
                .body("origin", equalTo("REAL"))
                .body("keyId", notNullValue());
        api().post("/api/journal/integrity/verify").then().statusCode(200).body("status", equalTo("OK"));
        // Nothing to acknowledge: the status is unchanged. Only the browser's session may ask.
        browser()
                .post("/api/journal/integrity/acknowledge")
                .then()
                .statusCode(200)
                .body("status", equalTo("OK"));
    }

    /** The director's browser session, as the desk holds it. */
    RequestSpecification browser() {
        String cookie = given().config(RestAssuredConfig.config()
                        .redirect(RedirectConfig.redirectConfig().followRedirects(false)))
                .header("Authorization", "Bearer " + token.value())
                .post("/auth")
                .getDetailedCookie(LocalAccessGuard.COOKIE)
                .getValue();
        return given().contentType("application/json")
                .cookie(LocalAccessGuard.COOKIE, cookie)
                .header("Origin", "http://127.0.0.1:" + RestAssured.port)
                .header(LocalAccessGuard.REQUEST_HEADER, "1");
    }

    @Test
    void aScriptTokenOfScopeAllCannotAcknowledge() {
        String script = api().body("{\"scope\":\"all\",\"days\":1,\"label\":\"ack test\"}")
                .post("/api/auth/tokens")
                .then()
                .statusCode(201)
                .extract()
                .path("token");
        given().contentType("application/json")
                .header("Authorization", "Bearer " + script)
                .post("/api/journal/integrity/acknowledge")
                .then()
                .statusCode(403)
                .body("code", equalTo("COOKIE_ONLY"));
    }

    @Test
    void theDirectorsBearerTokenCannotAcknowledgeEither() {
        api().post("/api/journal/integrity/acknowledge").then().statusCode(403).body("code", equalTo("COOKIE_ONLY"));
    }

    @Test
    void anAnonymousCallerIsRefused() {
        given().get("/api/journal/integrity").then().statusCode(401);
    }
}
