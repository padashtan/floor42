package dev.floor42.server.api;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** The director defines a committee seat of their own and seats it on an idea committee. */
@QuarkusTest
class CustomSeatApiTest extends ApiTestBase {

    @Test
    void aSeatOfTheDirectorsOwnIsListedAndSeated() {
        api().body("""
                        {"name": "Rosé", "title": "GDPR expert", "lens": "Personal data, consent and retention.",
                         "panels": ["idea"]}""")
                .post("/api/committee/roles")
                .then()
                .statusCode(200)
                .body("find { it.key == 'rose' }.custom", equalTo(true))
                .body("find { it.key == 'rose' }.model", equalTo("claude-sonnet-5-5"))
                .body("find { it.key == 'lawyer' }.custom", equalTo(false));

        api().body("""
                        {"title": "Idea with a GDPR eye", "objective": "Should we keep the visitors' emails?",
                         "repository": "", "committee": ["rose", "lawyer"], "panel": "idea"}""").post("/api/missions").then().statusCode(201);
        api().get("/api/agents").then().body("id", hasItem("rose"));
    }

    @Test
    void anOfficeSeatsNameIsRefused() {
        api().body("{\"name\": \"Nora\", \"title\": \"t\", \"lens\": \"l\", \"panels\": [\"idea\"]}")
                .post("/api/committee/roles")
                .then()
                .statusCode(400)
                .body("code", equalTo("SEAT_TAKEN"));
    }
}
