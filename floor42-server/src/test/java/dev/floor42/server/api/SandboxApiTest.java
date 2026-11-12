package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Setting;
import dev.floor42.domain.event.PreferenceEvent.SandboxToolsProbed;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.runtime.SandboxProbe;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Sandbox readiness (ADR-0033): reading never measures, measuring is one run in the sandbox, nothing installs, and a
 * tool is MISSING only when a measure said so. No test starts wsl.exe: the probe's process seam is replaced.
 */
@QuarkusTest
class SandboxApiTest extends ApiTestBase {

    static final String DEBIAN = """
            os|PRETTY_NAME="Debian GNU/Linux 13 (trixie)"
            os|VERSION_ID="13"
            os|ID=debian
            git|/usr/bin/git|git version 2.47.3
            java|/usr/bin/java|openjdk version "17.0.15" 2025-04-15
            mvn||
            node||
            floor42-probe-end
            """;

    @Inject
    SandboxProbe probe;

    @Inject
    dev.floor42.server.runtime.HostChecks host;

    @Inject
    OfficeService office;

    @Inject
    Journal journal;

    /** A sandbox whose settings say it must run, counting how often it is measured again. */
    static class CountingSandbox extends dev.floor42.server.runtime.Sandbox {
        int rechecks;

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public boolean recheck() {
            rechecks++;
            return true;
        }
    }

    final List<List<String>> runs = new ArrayList<>();

    @AfterEach
    void realProbeBack() {
        probe.useForTests(null, null);
    }

    static Path mavenProject() throws Exception {
        Path dir = Files.createTempDirectory("office-sandbox-project");
        Files.writeString(
                dir.resolve("pom.xml"),
                "<project><properties><maven.compiler.release>21</maven.compiler.release></properties></project>");
        Files.writeString(dir.resolve("package.json"), "{\"engines\":{\"node\":\">=22\"}}");
        return dir;
    }

    @Test
    void offWindowsNoProcessRunsAndEveryToolIsUnknown() throws Exception {
        probe.useForTests(
                (argv, limit) -> {
                    runs.add(argv);
                    return new SandboxProbe.RunResult(DEBIAN, 0, false);
                },
                () -> "Mac OS X");
        Path project = mavenProject();

        JsonPath r = api().queryParam("project", project.toString())
                .post("/api/sandbox/readiness/probe")
                .then()
                .statusCode(200)
                .body("measured", equalTo(false))
                .body("tools.state", everyItem(equalTo("UNKNOWN")))
                .body("commands", org.hamcrest.Matchers.empty())
                .extract()
                .jsonPath();

        assertThat(runs).isEmpty();
        assertThat(r.getString("reason")).contains("Windows");
        assertThat(r.getList("tools.tool", String.class)).contains("git", "java", "mvn", "node", "npm");
    }

    @Test
    void aProbeMeasuresTheSandboxAgainSoARepairedMachineIsSeenWithoutARestart() throws Exception {
        CountingSandbox sandbox = new CountingSandbox();
        io.quarkus.test.junit.QuarkusMock.installMockForType(sandbox, dev.floor42.server.runtime.Sandbox.class);
        probe.useForTests((argv, limit) -> new SandboxProbe.RunResult(DEBIAN, 0, false), () -> "Windows 11");

        api().post("/api/sandbox/readiness/probe").then().statusCode(200);

        assertThat(sandbox.rechecks).isEqualTo(1);
    }

    @Test
    void withoutAMeasureNothingIsMissingAndReadingRunsNothing() throws Exception {
        probe.useForTests(
                (argv, limit) -> {
                    runs.add(argv);
                    return new SandboxProbe.RunResult(DEBIAN, 0, false);
                },
                () -> "Windows 11");
        try {
            // Turning the sandbox on forgets the last measure (without measuring: no wsl.exe in tests).
            office.execute(c -> c.preferences()
                    .changeSetting(office.context(Actor.director(), Origin.REAL), Setting.SHELL_SANDBOX, "wsl"));

            api().queryParam("project", mavenProject().toString())
                    .queryParam("check", "mvn -q verify")
                    .get("/api/sandbox/readiness")
                    .then()
                    .statusCode(200)
                    .body("measuredAt", equalTo(null))
                    .body("measured", equalTo(false))
                    .body("enabled", equalTo(true))
                    .body("tools.state", everyItem(not(equalTo("MISSING"))))
                    .body("commands", org.hamcrest.Matchers.empty());
            assertThat(runs).isEmpty();
        } finally {
            office.execute(c -> c.preferences()
                    .changeSetting(office.context(Actor.director(), Origin.REAL), Setting.SHELL_SANDBOX, null));
        }
    }

    @Test
    void aMeasureIsOneRunJournaledWithoutTheProjectAndOnlyShowsCommands() throws Exception {
        probe.useForTests(
                (argv, limit) -> {
                    runs.add(argv);
                    return new SandboxProbe.RunResult(DEBIAN, 0, false);
                },
                () -> "Windows 11");
        Path project = mavenProject();

        JsonPath r = api().queryParam("project", project.toString())
                .post("/api/sandbox/readiness/probe")
                .then()
                .statusCode(200)
                .body("measured", equalTo(true))
                .body("os", equalTo("Debian GNU/Linux 13 (trixie)"))
                .body("recipes", equalTo(true))
                .body("tools.find { it.tool == 'git' }.state", equalTo("PRESENT"))
                .body("tools.find { it.tool == 'java' }.state", equalTo("OLDER"))
                .body("tools.find { it.tool == 'mvn' }.state", equalTo("MISSING"))
                .body("tools.find { it.tool == 'node' }.state", equalTo("MISSING"))
                // npm was asked about, but its line is not in the recorded output: unknown, not missing.
                .body("tools.find { it.tool == 'npm' }.state", equalTo("UNKNOWN"))
                .extract()
                .jsonPath();

        assertThat(runs).hasSize(1);
        String script = new String(Base64.getDecoder().decode(runs.getFirst().get(7)), StandardCharsets.UTF_8);
        assertThat(script).doesNotContain("apt", "sudo", "tar ", "curl -");
        assertThat(String.join(" ", runs.getFirst()))
                .doesNotContain(project.getFileName().toString());
        List<String> commands = r.getList("commands", String.class);
        assertThat(commands.getFirst()).startsWith("sudo apt-get update").contains("openjdk-21-jdk-headless", "maven");
        assertThat(String.join("\n", commands))
                .contains("nodejs.org/dist/latest-v22.x")
                .doesNotContain("nvm");
        assertThat(r.getString("measuredAt")).isNotNull();

        var envelope = journal.readAll().stream()
                .filter(e -> e.event() instanceof SandboxToolsProbed)
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(envelope.origin()).isEqualTo(Origin.REAL);
        assertThat(envelope.actor().kind()).isEqualTo(Actor.Kind.SYSTEM);
        assertThat(envelope.mission()).isNull();
        assertThat(envelope.event().toString())
                .doesNotContain(project.getFileName().toString());

        // Agents' briefs carry what was measured.
        String note = office.query(dev.floor42.server.app.LiveSeams::measuredTools);
        assertThat(note)
                .contains("present git (git version 2.47.3)", "java (openjdk version")
                .contains("not installed: mvn, node");

        // Reading again runs nothing and shows the same measure.
        api().queryParam("project", project.toString())
                .get("/api/sandbox/readiness")
                .then()
                .statusCode(200)
                .body("tools.find { it.tool == 'mvn' }.state", equalTo("MISSING"));
        assertThat(runs).hasSize(1);
    }

    @Test
    void aTimeoutMeasuresNothingAndAFolderThatIsNotOneIsRefused() throws Exception {
        probe.useForTests(
                (argv, limit) -> new SandboxProbe.RunResult("git|/usr/bin/git|", null, true), () -> "Windows");

        api().post("/api/sandbox/readiness/probe")
                .then()
                .statusCode(200)
                .body("measured", equalTo(false))
                .body("tools.state", everyItem(equalTo("UNKNOWN")));
        api().queryParam(
                        "project",
                        Path.of("no-such-folder-here").toAbsolutePath().toString())
                .get("/api/sandbox/readiness")
                .then()
                .statusCode(404);
        given().get("/api/sandbox/readiness").then().statusCode(401);
        given().post("/api/sandbox/readiness/probe").then().statusCode(401);
        given().get("/api/sandbox/checklist").then().statusCode(401);
    }

    /** The machine checklist: host checks read now, the sandbox's part from its last measure; nothing installs. */
    @Test
    void theChecklistReadsTheHostNowAndTheSandboxFromItsLastMeasure() throws Exception {
        List<List<String>> hostRuns = new ArrayList<>();
        host.useForTests(
                (argv, limit) -> {
                    hostRuns.add(argv);
                    // wsl.exe writes UTF-16: the bytes as SandboxProbe.start reads them.
                    String out = argv.getFirst().equals("git")
                            ? "git version 2.51.0.windows.1\n"
                            : new String(
                                    "  NAME      STATE           VERSION\r\n* Debian    Running         2\r\n"
                                            .getBytes(StandardCharsets.UTF_16LE),
                                    StandardCharsets.UTF_8);
                    return new SandboxProbe.RunResult(out, 0, false);
                },
                () -> "Windows 11");
        probe.useForTests(
                (argv, limit) -> {
                    runs.add(argv);
                    return new SandboxProbe.RunResult(DEBIAN, 0, false);
                },
                () -> "Windows 11");
        try {
            api().post("/api/sandbox/readiness/probe").then().statusCode(200);
            int measures = runs.size();

            JsonPath r = api().get("/api/sandbox/checklist")
                    .then()
                    .statusCode(200)
                    .body("windows", equalTo(true))
                    .body("rows.find { it.id == 'git' && !it.sandbox }.state", equalTo("PRESENT"))
                    .body("rows.find { it.id == 'wsl' }.state", equalTo("PRESENT"))
                    .body("rows.find { it.id == 'distro' }.state", equalTo("PRESENT"))
                    .body("rows.find { it.id == 'isolation' }.state", equalTo("PRESENT"))
                    .body("rows.find { it.id == 'git' && it.sandbox }.state", equalTo("PRESENT"))
                    // node was measured missing: optional in the sandbox, with the command to copy.
                    .body("rows.find { it.id == 'node' }.state", equalTo("OPTIONAL"))
                    .body("rows.find { it.id == 'chromium' }.state", equalTo("UNKNOWN"))
                    .extract()
                    .jsonPath();

            assertThat(r.getString("rows.find { it.id == 'node' }.command"))
                    .isEqualTo(dev.floor42.server.runtime.WslSandbox.installHint("nodejs npm"));
            assertThat(runs)
                    .as("reading the checklist never measures the sandbox")
                    .hasSize(measures);
            assertThat(hostRuns).containsExactly(List.of("git", "--version"), List.of("wsl.exe", "-l", "-v"));
        } finally {
            host.useForTests(null, null);
        }
    }
}
