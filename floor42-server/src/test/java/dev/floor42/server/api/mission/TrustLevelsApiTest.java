package dev.floor42.server.api.mission;

import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.TrustLevel;
import dev.floor42.server.api.ApiTestBase;
import dev.floor42.server.app.mission.TrustRaiseStepUp;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import org.junit.jupiter.api.Test;

/**
 * Trust levels on the director's staff API (ADR-0082): a hire starts on probation, the director narrows by a plain
 * click, and widens only with the step-up proof, here a fake standing for the assured approvals' mechanism.
 */
@QuarkusTest
class TrustLevelsApiTest extends ApiTestBase {

    /** The proof the test decides; the default stays true so no other test sees a refusal. */
    @Alternative
    @Priority(1)
    @ApplicationScoped
    public static class FakeStepUp implements TrustRaiseStepUp {
        static volatile boolean proof = true;

        @Override
        public boolean proven(AgentId agent, TrustLevel to) {
            return proof;
        }
    }

    String hire(String name) {
        return api().body("{\"name\":\"" + name + "\",\"workRole\":\"RESEARCHER\",\"model\":\"claude-haiku-5-5\"}")
                .post("/api/agents")
                .then()
                .statusCode(200)
                .body("trust", equalTo("PROBATION"))
                .body("trustCause", equalTo("HIRE"))
                .extract()
                .path("id");
    }

    @Test
    void aNewHireIsOnProbationAndTheDirectorNarrowsWithAPlainClick() {
        String id = hire("Tess");

        api().body("{\"level\":\"SHADOW\"}")
                .post("/api/agents/" + id + "/trust/lower")
                .then()
                .statusCode(200)
                .body("trust", equalTo("SHADOW"))
                .body("trustCause", equalTo("DIRECTOR"));
        // A "lower" that widens is refused, whatever the proof.
        api().body("{\"level\":\"STANDARD\"}")
                .post("/api/agents/" + id + "/trust/lower")
                .then()
                .statusCode(409)
                .body("code", equalTo("INVALID_TRANSITION"));
        api().body("{\"level\":\"NOPE\"}")
                .post("/api/agents/" + id + "/trust/lower")
                .then()
                .statusCode(400);
        api().body("{}").post("/api/agents/" + id + "/trust/lower").then().statusCode(400);
    }

    @Test
    void aRaiseNeedsTheStepUpProof() {
        String id = hire("Rhea");
        try {
            FakeStepUp.proof = false;
            api().body("{\"level\":\"STANDARD\"}")
                    .post("/api/agents/" + id + "/trust/raise")
                    .then()
                    .statusCode(409)
                    .body("code", equalTo("TRUST_RAISE_NEEDS_STEP_UP"));
            api().get("/api/agents").then().body("find { it.id == '" + id + "' }.trust", equalTo("PROBATION"));

            FakeStepUp.proof = true;
            api().body("{\"level\":\"STANDARD\"}")
                    .post("/api/agents/" + id + "/trust/raise")
                    .then()
                    .statusCode(200)
                    .body("trust", equalTo("STANDARD"))
                    .body("raiseProposed", equalTo(false))
                    .body("acceptedPatches", equalTo(0));
        } finally {
            FakeStepUp.proof = true;
        }
    }
}
