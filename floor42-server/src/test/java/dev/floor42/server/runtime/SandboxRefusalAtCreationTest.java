package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Setting;
import dev.floor42.server.api.ApiTestBase;
import dev.floor42.server.api.mission.LiveMissionTest;
import dev.floor42.server.app.core.OfficeService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** A sandbox that is required and does not work refuses the mission at its creation, not at the first session (ADR-0070). */
@QuarkusTest
class SandboxRefusalAtCreationTest extends ApiTestBase {

    @Inject
    Sandbox sandbox;

    @Inject
    OfficeService office;

    private void shellSandbox(String value) {
        office.execute(c -> c.preferences()
                .changeSetting(office.context(Actor.director(), Origin.REAL), Setting.SHELL_SANDBOX, value));
    }

    @Test
    void aBrokenRequiredSandboxRefusesTheCreationAndRecordsNoMission() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        int before = office.query(c -> c.missions().all().size());
        shellSandbox("wsl");
        sandbox.setAvailable(false);
        try {
            api().body("{\"title\":\"Blocked\",\"objective\":\"Do a thing\",\"repository\":\""
                            + repo.toString().replace("\\", "\\\\") + "\"}")
                    .post("/api/missions")
                    .then()
                    .statusCode(409)
                    .body("code", equalTo("SANDBOX_REQUIRED"));
            int after = office.query(c -> c.missions().all().size());
            assertThat(after).isEqualTo(before);
        } finally {
            sandbox.setAvailable(null);
            shellSandbox(null);
        }
    }
}
