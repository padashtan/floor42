package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** The director's improvement checklist over the API: add, tick, remove; the director only. */
@QuarkusTest
class NotesApiTest extends ApiTestBase {

    @Test
    void theChecklistLivesInTheJournal() {
        String id = api().body("{\"text\":\"Sound when a question arrives\"}")
                .post("/api/notes")
                .then()
                .statusCode(200)
                .body("text", hasItem("Sound when a question arrives"))
                .extract()
                .path("find { it.text == 'Sound when a question arrives' }.id");

        api().body("{\"done\":true}")
                .put("/api/notes/" + id)
                .then()
                .statusCode(200)
                .body("find { it.id == '" + id + "' }.done", equalTo(true));
        api().get("/api/journal?limit=1").then().statusCode(200).body("[0].type", equalTo("NoteChecked"));
        api().delete("/api/notes/" + id).then().statusCode(200);
        api().delete("/api/notes/" + id).then().statusCode(404).body("code", equalTo("UNKNOWN_NOTE"));
        api().body("{\"text\":\"\"}").post("/api/notes").then().statusCode(400);
        given().get("/api/notes").then().statusCode(401);
    }
}
