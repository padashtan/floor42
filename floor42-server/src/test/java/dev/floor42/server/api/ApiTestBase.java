package dev.floor42.server.api;

import static io.restassured.RestAssured.given;

import dev.floor42.server.security.SessionToken;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;

/** What the API tests share: a request signed in as the director, with a JSON body. */
public abstract class ApiTestBase {

    @Inject
    public SessionToken token;

    protected RequestSpecification api() {
        return given().header("Authorization", "Bearer " + token.value()).contentType("application/json");
    }
}
