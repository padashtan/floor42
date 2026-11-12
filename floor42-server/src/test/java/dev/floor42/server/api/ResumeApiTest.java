package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.runtime.AgentRuntime;
import dev.floor42.server.runtime.HookRegistry;
import dev.floor42.server.runtime.RuntimeEvent;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Resuming work (ADR-0038), with the fake Claude Code: a developer's session that failed is listed with its "Retry";
 * continuing it with --resume is offered only once Claude Code's own transcript is there, and only on the director's
 * click; the journal says which session the next one continued. Director only.
 */
@QuarkusTest
class ResumeApiTest {

    @Inject
    SessionToken token;

    @Inject
    OfficeService office;

    @Inject
    HookRegistry hooks;

    io.restassured.specification.RequestSpecification api() {
        return given().header("Authorization", "Bearer " + token.value());
    }

    List<AgentSession> sessions(MissionId m) {
        return office.query(c -> c.sessions().of(m));
    }

    @Test
    void aFailedSessionIsContinuedWithResumeOnlyWhenSafeAndOnlyOnTheDirectorsClick() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().contentType("application/json")
                .body("{\"title\":\"Resume\",\"objective\":\"Describe this repository [fake:crash-unless-resumed]\","
                        + "\"repository\":\"" + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        MissionId m = new MissionId(id);
        api().contentType("application/json")
                .body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(200);

        await(
                "the failed session and its Retry",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.desk().of(m).stream()
                        .anyMatch(q ->
                                q.status() == QuestionStatus.OPEN && q.options().contains("Retry"))));
        AgentSession failed = sessions(m).getFirst();
        assertThat(failed.failed()).isTrue();

        // Listed, but not continuable: Claude Code's own transcript is not there.
        api().get("/api/resume")
                .then()
                .statusCode(200)
                .body("missions.find { it.id == '" + id + "' }.action", equalTo("RETRY"))
                .body("sessions.find { it.session == '" + failed.id() + "' }.continuable", equalTo(false))
                .body("sessions.find { it.session == '" + failed.id() + "' }.continuity", equalTo("noTranscript"));
        api().contentType("application/json")
                .body("{\"session\":\"" + failed.id() + "\",\"mode\":\"CONTINUE\"}")
                .post("/api/resume/session")
                .then()
                .statusCode(409)
                .body("code", equalTo("CONTINUE_UNSAFE"));
        assertThat(sessions(m)).as("nothing started on a refusal").hasSize(1);

        // The transcript Claude Code keeps for the worktree: now it can be continued.
        String worktree = office.query(c ->
                c.workshop().workspace(m, new AgentId("chris")).orElseThrow().path());
        String claudeId = failed.id().substring(failed.id().indexOf(':') + 1);
        Path projects = Path.of("target/test-claude-home/projects").resolve(worktree.replaceAll("[^A-Za-z0-9]", "-"));
        Files.createDirectories(projects);
        Files.writeString(
                projects.resolve(claudeId + ".jsonl"),
                "{\"type\":\"user\",\"cwd\":\"" + worktree.replace("\\", "\\\\") + "\",\"sessionId\":\"" + claudeId
                        + "\"}\n");
        api().get("/api/resume")
                .then()
                .body("sessions.find { it.session == '" + failed.id() + "' }.continuable", equalTo(true));

        // An agent's credential is refused on every route of this panel.
        String credential = hooks.register(new AgentRuntime.Governor() {
            @Override
            public AgentRuntime.Verdict decide(RuntimeEvent.ToolRequested call) {
                return AgentRuntime.Verdict.DENY;
            }

            @Override
            public String violationNow(dev.floor42.server.runtime.RuntimeEvent.ToolRequested call) {
                return null;
            }

            @Override
            public void completed(RuntimeEvent.ToolCompleted result) {}
        });
        try {
            given().header("Authorization", "Bearer " + credential)
                    .get("/api/resume")
                    .then()
                    .statusCode(403);
            given().header("Authorization", "Bearer " + credential)
                    .contentType("application/json")
                    .body("{\"session\":\"" + failed.id() + "\",\"mode\":\"CONTINUE\"}")
                    .post("/api/resume/session")
                    .then()
                    .statusCode(403);
            given().header("Authorization", "Bearer " + credential)
                    .contentType("application/json")
                    .body("{\"path\":\"" + repo.toString().replace("\\", "\\\\") + "\",\"pinned\":true}")
                    .post("/api/projects/pin")
                    .then()
                    .statusCode(403);
            given().header("Authorization", "Bearer " + credential)
                    .contentType("application/json")
                    .body("{\"path\":\"" + repo.toString().replace("\\", "\\\\") + "\"}")
                    .post("/api/projects/forget")
                    .then()
                    .statusCode(403);
        } finally {
            hooks.unregister(credential);
        }
        assertThat(sessions(m)).as("listing starts nothing").hasSize(1);

        api().contentType("application/json")
                .body("{\"session\":\"" + failed.id() + "\",\"mode\":\"CONTINUE\"}")
                .post("/api/resume/session")
                .then()
                .statusCode(200);

        await("the continued session", Duration.ofSeconds(30), () -> sessions(m).size() == 2);
        AgentSession next = sessions(m).get(1);
        await(
                "its record",
                Duration.ofSeconds(10),
                () -> office.query(c -> c.sessions()
                                .find(next.id())
                                .map(AgentSession::continuedFrom)
                                .orElse(null)
                        != null));
        String from =
                office.query(c -> c.sessions().find(next.id()).orElseThrow().continuedFrom());
        assertThat(from).isEqualTo(failed.id());
        assertThat(launchedWith("--resume", claudeId))
                .as("claude --resume <id> --fork-session")
                .isTrue();

        // The continued session plays the usual script: its shell and write requests are refused, then it reports.
        await("the continued session's end", Duration.ofSeconds(60), () -> {
            office.query(c -> c.desk().of(m).stream()
                            .filter(q -> q.status() == QuestionStatus.OPEN
                                    && q.options().contains("DENY"))
                            .map(q -> q.id().value())
                            .toList())
                    .forEach(q -> api().contentType("application/json")
                            .body("{\"answer\":\"DENY\"}")
                            .post("/api/questions/" + q + "/answer"));
            return office.query(c -> c.sessions().of(m).stream().noneMatch(AgentSession::isOpen))
                    && sessions(m).size() >= 2;
        });
        api().contentType("application/json")
                .body("{\"session\":\"" + failed.id() + "\",\"mode\":\"FRESH\"}")
                .post("/api/resume/session")
                .then()
                .statusCode(409)
                .body("code", equalTo("NOTHING_TO_RESUME"));

        api().contentType("application/json")
                .body("{\"reason\":\"Resume checked\"}")
                .post("/api/missions/" + id + "/cancel")
                .then()
                .statusCode(200);
        await(
                "every session closed",
                Duration.ofSeconds(60),
                () -> office.query(c -> c.sessions().of(m).stream().noneMatch(AgentSession::isOpen)));
        api().get("/api/projects").then().body("path", hasItem(repo.toString()));
    }

    /** Whether one of the fake's recorded launches had these two arguments in a row. */
    static boolean launchedWith(String flag, String value) throws Exception {
        // SQLite creates and deletes floor42.db-journal in the same folder: a file that vanishes mid-walk is retried.
        for (int attempt = 1; ; attempt++) {
            try {
                return walkLaunches(flag, value);
            } catch (java.io.UncheckedIOException e) {
                if (!(e.getCause() instanceof java.nio.file.NoSuchFileException) || attempt == 5) throw e;
            }
        }
    }

    private static boolean walkLaunches(String flag, String value) throws Exception {
        Path sessions = Path.of("target/test-data");
        try (Stream<Path> files = Files.walk(sessions, 4)) {
            return files.filter(p -> p.getFileName().toString().equals("fake-args.txt"))
                    .anyMatch(p -> {
                        try {
                            List<String> args = Files.readAllLines(p);
                            int i = args.indexOf(flag);
                            return i >= 0
                                    && i + 1 < args.size()
                                    && args.get(i + 1).equals(value)
                                    && args.contains("--fork-session");
                        } catch (java.io.IOException e) {
                            return false;
                        }
                    });
        }
    }
}
