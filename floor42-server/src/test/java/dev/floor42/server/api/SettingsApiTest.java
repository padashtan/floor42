package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.server.app.Briefs;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.OfficeSettings;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The settings panel's API: values in force, defaults, the director's changes, and the agents' language in briefs. */
@QuarkusTest
class SettingsApiTest extends ApiTestBase {

    @Inject
    OfficeService office;

    @Inject
    OfficeSettings settings;

    @Test
    void theDirectorChangesSettingsThatApplyFromTheNextSession() throws Exception {
        api().get("/api/settings")
                .then()
                .statusCode(200)
                .body("key", hasItems("PARALLEL_SESSIONS", "PLAN_LIMIT_THRESHOLD", "RETRO_MODEL", "AGENT_LANGUAGE"))
                .body("find { it.key == 'AGENT_LANGUAGE' }.value", equalTo("en"))
                .body("find { it.key == 'AGENT_LANGUAGE' }.changed", equalTo(false));
        given().contentType("application/json")
                .body("{\"value\":\"fr\"}")
                .put("/api/settings/AGENT_LANGUAGE")
                .then()
                .statusCode(401);
        api().body("{\"value\":\"9\"}")
                .put("/api/settings/PARALLEL_SESSIONS")
                .then()
                .statusCode(400);
        api().body("{\"value\":\"x\"}")
                .put("/api/settings/NOT_A_SETTING")
                .then()
                .statusCode(400);

        Path repo = LiveMissionTest.gitRepository();
        MissionId m = new MissionId(api().body("{\"title\":\"Lang\",\"objective\":\"Describe\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id"));
        assertThat(brief(m)).doesNotContain("write your prose in French");

        api().body("{\"value\":\"fr\"}")
                .put("/api/settings/AGENT_LANGUAGE")
                .then()
                .statusCode(200)
                .body("find { it.key == 'AGENT_LANGUAGE' }.changed", equalTo(true));
        api().body("{\"value\":\"0.95\"}")
                .put("/api/settings/PLAN_LIMIT_THRESHOLD")
                .then()
                .statusCode(200);
        assertThat(brief(m)).contains("write your prose in French").contains("in English (VERDICT");
        assertThat(settings.planLimitThreshold()).isEqualTo(0.95);

        // Back to the defaults: the journal is shared by every test.
        api().body("{\"value\":null}")
                .put("/api/settings/AGENT_LANGUAGE")
                .then()
                .statusCode(200);
        api().body("{}").put("/api/settings/PLAN_LIMIT_THRESHOLD").then().statusCode(200);
        assertThat(brief(m)).doesNotContain("write your prose in French");
        assertThat(settings.planLimitThreshold()).isEqualTo(0.9);
        api().body("{\"reason\":\"test done\"}").post("/api/missions/" + m.value() + "/cancel");
    }

    private String brief(MissionId m) {
        return office.query(c -> Briefs.inLanguage(
                c,
                Briefs.producer(
                        c,
                        c.missions().find(m).orElseThrow(),
                        c.tickets().of(m).getFirst(),
                        c.staff().find(new AgentId("chris")).orElseThrow())));
    }
}
