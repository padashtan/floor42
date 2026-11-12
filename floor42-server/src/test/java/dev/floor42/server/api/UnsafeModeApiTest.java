package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** Unsafe mode over HTTP (ADR-0063): off by default, the director switches it with a reason, nobody else can. */
@QuarkusTest
class UnsafeModeApiTest extends ApiTestBase {

    @Test
    void offByDefaultThenOnWithAReasonThenOff() {
        api().get("/api/unsafe-mode").then().statusCode(200).body("on", equalTo(false));
        api().body("{\"on\":true,\"reason\":\" \"}")
                .put("/api/unsafe-mode")
                .then()
                .statusCode(400);
        api().get("/api/unsafe-mode").then().body("on", equalTo(false));
        api().body("{\"on\":true,\"reason\":\"WSL is not installed on this laptop\"}")
                .put("/api/unsafe-mode")
                .then()
                .statusCode(200)
                .body("on", equalTo(true));
        api().body("{\"on\":false}")
                .put("/api/unsafe-mode")
                .then()
                .statusCode(200)
                .body("on", equalTo(false));
    }

    @Test
    void noCredentialNoChange() {
        given().contentType("application/json")
                .body("{\"on\":true,\"reason\":\"x\"}")
                .put("/api/unsafe-mode")
                .then()
                .statusCode(org.hamcrest.Matchers.anyOf(equalTo(401), equalTo(403)));
    }
}
