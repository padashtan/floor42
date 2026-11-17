package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;

import dev.floor42.server.security.SessionToken;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Duration;

/** What the API tests share: a request signed in as the director, with a JSON body. */
public abstract class ApiTestBase {

    @Inject
    public SessionToken token;

    protected RequestSpecification api() {
        return given().header("Authorization", "Bearer " + token.value()).contentType("application/json");
    }

    /** Dismisses the agent once its work has ended (its session process included): a test never leaves a hire behind. */
    protected void dismissWhenFree(String agent) throws InterruptedException {
        await(
                agent + " is free to leave",
                Duration.ofSeconds(30),
                () -> api().body("{\"reason\":\"test\"}")
                                .post("/api/agents/" + agent + "/dismiss")
                                .statusCode()
                        == 200);
    }
}
