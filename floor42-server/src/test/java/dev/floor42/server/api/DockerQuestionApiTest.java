package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.EnvironmentBlocker;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.OptionCode;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Question;
import dev.floor42.domain.Setting;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.runtime.SandboxProbe;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A plan whose project needs Docker, with the sandbox on, gets ONE question saying the sandbox cannot reach the Docker
 * daemon, with the honest options only: the director runs the Docker steps, or the mission goes on without Docker. No
 * option installs anything or opens the sandbox. Answered "I do the step myself", the tickets carry the director's step.
 */
@QuarkusTest
class DockerQuestionApiTest {

    @Inject
    SessionToken token;

    @Inject
    OfficeService office;

    io.restassured.specification.RequestSpecification api() {
        return given().header("Authorization", "Bearer " + token.value()).contentType("application/json");
    }

    @Inject
    dev.floor42.server.runtime.Sandbox sandbox;

    @Inject
    SandboxProbe probe;

    /** What the probe's process seam answers: the client present, the daemon silent (as measured on Debian 13). */
    final AtomicReference<String> output = new AtomicReference<>("""
            os|ID=debian
            git|/usr/bin/git|git version 2.47.3
            docker|/usr/bin/docker|Docker version 27.5.1, build 9f9e405
            docker-daemon||
            floor42-probe-end
            """);

    @BeforeEach
    void sandboxOn() {
        probe.useForTests(
                (argv, limit) -> {
                    String out = output.get();
                    if (out == null) throw new java.io.IOException("Cannot run program \"wsl.exe\"");
                    return new SandboxProbe.RunResult(out, 0, false);
                },
                () -> "Windows 11");
        setting("wsl");
        dev.floor42.server.runtime.SandboxSeams.assume(sandbox, true);
    }

    @AfterEach
    void sandboxOff() {
        setting(null);
        dev.floor42.server.runtime.SandboxSeams.assume(sandbox, null);
        probe.useForTests(null, null);
    }

    void setting(String value) {
        office.execute(c -> c.preferences()
                .changeSetting(office.context(Actor.director(), Origin.REAL), Setting.SHELL_SANDBOX, value));
    }

    MissionId planned(boolean dockerfile) throws Exception {
        return planned(dockerfile, "Build the image");
    }

    MissionId planned(boolean dockerfile, String objective) throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        if (dockerfile) {
            Files.writeString(repo.resolve("Dockerfile"), "FROM debian:13\n");
            LiveMissionTest.run(repo, "git", "add", "Dockerfile");
            LiveMissionTest.run(
                    repo, "git", "-c", "user.name=test", "-c", "user.email=test@localhost", "commit", "-q", "-m", "d");
        }
        MissionId m = new MissionId(api().body("{\"title\":\"Image\",\"objective\":\"" + objective
                        + "\",\"repository\":\"" + repo.toString().replace("\\", "\\\\") + "\",\"plan\":true}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id"));
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + m.value() + "/approve")
                .then()
                .statusCode(200);
        await(
                "the plan",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.tickets().of(m).stream().anyMatch(t -> t.status() == TicketStatus.PROPOSED)));
        return m;
    }

    Optional<Question> dockerQuestion(MissionId m) {
        return office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.phrase() != null && q.phrase().code().startsWith("docker"))
                .findFirst());
    }

    @Test
    void aDockerfileMeasuresTheDaemonAndAsksWhatTheMeasureSays() throws Exception {
        MissionId m = planned(true);

        Question q = dockerQuestion(m).orElseThrow(() -> new AssertionError("no Docker question"));
        assertThat(q.phrase().code()).isEqualTo("dockerUnreachable");
        assertThat(q.options()).containsExactly(OptionCode.DO_IT_MYSELF.label(), OptionCode.WITHOUT_DOCKER.label());
        assertThat(q.offers(OptionCode.INSTALL_MISSING)).isFalse();
        assertThat(q.offers(OptionCode.GO_ON_WITHOUT)).isFalse();
        assertThat(q.phrase().params())
                .containsEntry("tools", "docker")
                .containsEntry("because", "Dockerfile")
                .containsEntry("measured", probe.docker().at().toString());
        assertThat(q.context()).contains("Measured at", "cannot reach the Docker daemon", "will not change it");
        // No ticket of the plan NEEDS docker: the director's step is the mission's, never every ticket's.
        assertThat(q.phrase().params()).containsEntry("tickets", "");
        assertThat(q.ticket()).isNull();
        List<TicketId> proposed = office.query(c -> c.tickets().of(m).stream()
                .filter(t -> t.status() == TicketStatus.PROPOSED)
                .map(t -> t.id())
                .toList());

        api().body("{\"answer\":\"" + OptionCode.DO_IT_MYSELF.name() + "\"}")
                .post("/api/questions/" + q.id().value() + "/answer")
                .then()
                .statusCode(200);

        Optional<EnvironmentBlocker> step =
                office.query(c -> EnvironmentBlocker.directorStep(c.desk().of(m), proposed.getFirst()));
        Optional<EnvironmentBlocker> mission =
                office.query(c -> EnvironmentBlocker.missionStep(c.desk().of(m)));
        assertThat(step).isEmpty();
        assertThat(mission).map(EnvironmentBlocker::missing).contains("docker");
    }

    @Test
    void aProbeThatCannotRunAsksWithoutClaimingTheDaemonIsOutOfReach() throws Exception {
        output.set(null);
        MissionId m = planned(true);

        Question q = dockerQuestion(m).orElseThrow(() -> new AssertionError("no Docker question"));
        assertThat(q.phrase().code()).isEqualTo("dockerUnmeasured");
        assertThat(q.phrase().params().get("reason")).contains("wsl.exe");
        assertThat(q.context()).contains("could not measure").doesNotContain("cannot reach");
    }

    @Test
    void withoutDockerNoQuestionIsAsked() throws Exception {
        MissionId m = planned(false);

        assertThat(dockerQuestion(m)).isEmpty();
    }

    @Test
    void aDockerfileTheMissionNeverMentionsIsRecordedQuietlyNotAsked() throws Exception {
        MissionId m = planned(true, "Add a login page");

        assertThat(dockerQuestion(m)).isEmpty();
    }

    @Test
    void withTheSandboxOffNoQuestionIsAsked() throws Exception {
        setting(null);
        MissionId m = planned(true);

        assertThat(dockerQuestion(m)).isEmpty();
    }
}
