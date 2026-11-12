package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.server.app.OfficeService;
import jakarta.inject.Inject;
import java.time.Duration;

/** The API tests that answer the director's questions one at a time. */
abstract class QuestionApiTestBase extends ApiTestBase {

    @Inject
    OfficeService office;

    Question open(MissionId m) {
        return office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN)
                .findFirst()
                .orElse(null));
    }

    void answer(MissionId m, String answer) throws InterruptedException {
        await("a question", Duration.ofSeconds(20), () -> open(m) != null);
        api().body("{\"answer\":\"" + answer + "\"}")
                .post("/api/questions/" + open(m).id().value() + "/answer")
                .then()
                .statusCode(200);
    }
}
