package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.app.Deliverables;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.config.DataFolders;
import dev.floor42.server.runtime.AgentRuntime;
import dev.floor42.server.runtime.HookRegistry;
import dev.floor42.server.runtime.RuntimeEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The deliverables of a mission (ADR-0037), end to end with the fake Claude Code and a real git worktree: what is
 * listed, what is served and how, what is refused, the ZIP and its manifest.
 */
@QuarkusTest
class DeliverablesApiTest extends ApiTestBase {

    @Inject
    OfficeService office;

    @Inject
    HookRegistry hooks;

    @Inject
    Deliverables deliverables;

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    Question open(MissionId m) {
        return office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN)
                .findFirst()
                .orElse(null));
    }

    void answerNext(MissionId m, String answer) throws InterruptedException {
        await("a question for the director", Duration.ofSeconds(20), () -> open(m) != null);
        api().body("{\"answer\":\"" + answer + "\"}")
                .post("/api/questions/" + open(m).id().value() + "/answer")
                .then()
                .statusCode(200);
    }

    /** A mission the fake developer worked on and the fake reviewer validated: its worktree is journaled. */
    String deliveredMission(Path repo) throws Exception {
        String id = api().body("{\"title\":\"Deliver\",\"objective\":\"Write notes\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
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
        answerNext(m, "ALLOW");
        answerNext(m, "ALLOW");
        await(
                "the review",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.tickets().of(m).getFirst().status()) == TicketStatus.DONE);
        return id;
    }

    static Path repositoryWithIgnoredOutputs() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        Files.writeString(repo.resolve(".gitignore"), "out/\n");
        LiveMissionTest.run(repo, "git", "add", ".");
        LiveMissionTest.run(
                repo, "git", "-c", "user.name=test", "-c", "user.email=test@localhost", "commit", "-q", "-m", "ignore");
        return repo;
    }

    static void commit(Path wt, String message) throws Exception {
        LiveMissionTest.run(wt, "git", "add", ".");
        LiveMissionTest.run(
                wt,
                "git",
                "-c",
                "user.name=tester",
                "-c",
                "user.email=tester@localhost",
                "commit",
                "-q",
                "-m",
                message);
    }

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 0};

    @Test
    void listsServesAndZipsWhatTheMissionDeliveredAndRefusesTheRest() throws Exception {
        Path repo = repositoryWithIgnoredOutputs();
        String id = deliveredMission(repo);
        String base = "/api/missions/" + id + "/deliverables";
        JsonPath first = api().get(base).then().statusCode(200).extract().jsonPath();
        Path wt = Path.of(first.getString("branches[0].worktree"));
        // Whoever the mission staffed: the test names no agent of its own.
        String agent = first.getString("branches[0].agent");
        assertThat(agent).isNotBlank();

        // The agent's work: some committed, a draft left untracked, a build output git ignores.
        Files.writeString(wt.resolve("report.md"), "# Report\n");
        Files.writeString(wt.resolve("page.html"), "<html><script>alert(document.cookie)</script></html>\n");
        Files.write(wt.resolve("pic.png"), PNG);
        Files.writeString(
                wt.resolve("diagram.svg"),
                "<?xml version=\"1.0\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>\n");
        Files.writeString(wt.resolve("fake.svg"), "<html><script>alert(1)</script></html>\n");
        Files.write(wt.resolve("tool.bin"), new byte[] {0, 1, 2});
        commit(wt, "Deliver the report");
        Files.writeString(wt.resolve("draft.txt"), "not finished\n");
        Files.writeString(wt.resolve("big.txt"), "x".repeat((int) Deliverables.MAX_VIEW_TEXT + 1));
        Files.createDirectories(wt.resolve("out"));
        Files.writeString(wt.resolve("out/report.pdf"), "%PDF-1.4\n% fake\n");
        Path outside = Files.createTempDirectory("floor42-outside");
        Files.writeString(outside.resolve("secret.txt"), "secret\n");
        boolean linked = link(wt.resolve("escape"), outside);
        System.out.println("DeliverablesApiTest: link out of the worktree created: " + linked);

        JsonPath v = api().get(base).then().statusCode(200).extract().jsonPath();
        assertThat(v.getString("status")).isNotBlank();
        assertThat(v.getString("tickets[0].deliverable")).isNotBlank();
        assertThat(v.getString("tickets[0].verdict")).isEqualTo("VALIDATED");
        assertThat(v.getList("tickets[0].reviews")).isNotEmpty();
        assertThat(v.getList("branches[0].tickets", String.class)).contains(v.getString("tickets[0].id"));
        assertThat(v.getBoolean("branches[0].worktreePresent")).isTrue();
        assertThat(v.getString("branches[0].merged")).isEqualTo("NOT_MERGED");
        assertThat(v.getList("branches[0].commits.subject", String.class)).contains("Deliver the report");
        Map<String, String> committed = statuses(v, "branches[0].committed");
        assertThat(committed).containsEntry("report.md", "A").containsEntry("page.html", "A");
        assertThat(v.getLong("branches[0].committed.find { it.path == 'report.md' }.size"))
                .isEqualTo(9L);
        Map<String, String> loose = statuses(v, "branches[0].uncommitted");
        assertThat(loose).containsEntry("draft.txt", "?").doesNotContainKey("out/report.pdf");
        assertThat(v.getInt("branches[0].ignoredHidden")).isGreaterThanOrEqualTo(1);

        JsonPath withIgnored = api().get(base + "?ignored=true")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(statuses(withIgnored, "branches[0].uncommitted")).containsEntry("out/report.pdf", "!");
        assertThat(withIgnored.getBoolean("branches[0].uncommitted.find { it.path == 'out/report.pdf' }.ignored"))
                .isTrue();
        assertThat(withIgnored.getString("branches[0].uncommitted.find { it.path == 'out/report.pdf' }.kind"))
                .isEqualTo("PDF");

        // Markdown keeps its own type, shown or downloaded; the sandbox and nosniff stay.
        api().get(base + "/file?agent=" + agent + "&source=COMMITTED&path=report.md")
                .then()
                .statusCode(200)
                .contentType("text/markdown;charset=UTF-8")
                .header("Content-Security-Policy", startsWith("sandbox; default-src 'none'"))
                .header("X-Content-Type-Options", "nosniff");
        api().get(base + "/file?agent=" + agent + "&source=COMMITTED&path=report.md&download=true")
                .then()
                .statusCode(200)
                .contentType("text/markdown;charset=UTF-8")
                .header("Content-Disposition", startsWith("attachment;"));
        // HTML is served as text, in a sandbox, never sniffed.
        api().get(base + "/file?agent=" + agent + "&source=COMMITTED&path=page.html")
                .then()
                .statusCode(200)
                .contentType(startsWith("text/plain"))
                .header("Content-Security-Policy", startsWith("sandbox; default-src 'none'"))
                .header("X-Content-Type-Options", "nosniff")
                .body(containsString("<script>"));
        api().get(base + "/file?agent=" + agent + "&source=COMMITTED&path=pic.png")
                .then()
                .statusCode(200)
                .contentType("image/png")
                .header("Content-Security-Policy", startsWith("sandbox;"));
        // A HEAD answers the headers and leaves nothing open: neither the committed blob's temporary copy nor a file.
        await("no file left open", Duration.ofSeconds(5), () -> dev.floor42.server.app.BlobBytes.openNow() == 0);
        api().head(base + "/file?agent=" + agent + "&source=COMMITTED&path=pic.png")
                .then()
                .statusCode(200)
                .contentType("image/png");
        api().head(base + "/file?agent=" + agent + "&source=WORKTREE&path=draft.txt&download=true")
                .then()
                .statusCode(200);
        assertThat(dev.floor42.server.app.BlobBytes.openNow())
                .as("files left open by HEAD")
                .isZero();
        api().get(base + "/file?agent=" + agent + "&source=WORKTREE&path=draft.txt")
                .then()
                .statusCode(200)
                .body(equalTo("not finished\n"));
        // An SVG is an image only (ADR-0051): opened on its own it downloads, under a sandbox, never sniffed.
        assertThat(v.getString("branches[0].committed.find { it.path == 'diagram.svg' }.kind"))
                .isEqualTo("IMAGE");
        api().get(base + "/file?agent=" + agent + "&source=COMMITTED&path=diagram.svg")
                .then()
                .statusCode(200)
                .contentType("image/svg+xml")
                .header("Content-Disposition", startsWith("attachment;"))
                .header("Content-Security-Policy", startsWith("sandbox; default-src 'none'"))
                .header("X-Content-Type-Options", "nosniff");
        // Not an SVG inside, not a kind the viewer shows, or above the cap: refused, Download stays.
        refused(base + "/file?agent=" + agent + "&source=COMMITTED&path=fake.svg", 415, "NOT_VIEWABLE");
        refused(base + "/file?agent=" + agent + "&source=COMMITTED&path=tool.bin", 415, "NOT_VIEWABLE");
        refused(base + "/file?agent=" + agent + "&source=WORKTREE&path=big.txt", 413, "TOO_LARGE_TO_VIEW");
        api().get(base + "/file?agent=" + agent + "&source=COMMITTED&path=tool.bin&download=true")
                .then()
                .statusCode(200)
                .contentType("application/octet-stream");
        api().get(base + "/file?agent=" + agent + "&source=WORKTREE&path=out/report.pdf")
                .then()
                .statusCode(200)
                .contentType("application/pdf")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", containsString("default-src 'none'"));
        api().get(base + "/file?agent=" + agent + "&source=COMMITTED&path=page.html&download=true")
                .then()
                .statusCode(200)
                .contentType("application/octet-stream")
                .header("Content-Disposition", startsWith("attachment; filename=\"page.html\""))
                .header("Content-Security-Policy", startsWith("sandbox"));

        // Refused: climbing out, absolute paths, git's own files, files the listing does not name, links.
        refused(base + "/file?agent=" + agent + "&source=WORKTREE&path=../../secret.txt", 400, "OUTSIDE_WORKSPACE");
        refused(
                base + "/file?agent=" + agent + "&source=WORKTREE&path="
                        + outside.resolve("secret.txt").toString().replace('\\', '/'),
                400,
                "OUTSIDE_WORKSPACE");
        refused(base + "/file?agent=" + agent + "&source=WORKTREE&path=.git/config", 400, "OUTSIDE_WORKSPACE");
        refused(base + "/file?agent=" + agent + "&source=WORKTREE&path=README.md", 400, "NOT_A_DELIVERABLE");
        refused(base + "/file?agent=" + agent + "&source=COMMITTED&path=../README.md", 400, "NOT_A_DELIVERABLE");
        refused(base + "/file?agent=" + agent + "&source=COMMITTED&path=README.md", 400, "NOT_A_DELIVERABLE");
        refused(base + "/file?agent=" + agent + "&source=ELSEWHERE&path=report.md", 400, "INVALID_ARGUMENT");
        if (linked) {
            int status = api().get(base + "/file?agent=" + agent + "&source=WORKTREE&path=escape/secret.txt")
                    .then()
                    .extract()
                    .statusCode();
            assertThat(status).isEqualTo(400);
        }
        api().get(base + "/file?agent=nobody&source=WORKTREE&path=draft.txt")
                .then()
                .statusCode(404);
        api().get("/api/missions/M-999/deliverables").then().statusCode(404);
        api().get("/api/missions/M-999/deliverables/zip").then().statusCode(404);

        // The ZIP: tickets as Markdown, files by branch, a manifest saying where each comes from.
        byte[] zip = api().get(base + "/zip")
                .then()
                .statusCode(200)
                .contentType("application/zip")
                .header("Content-Disposition", startsWith("attachment;"))
                .extract()
                .asByteArray();
        Map<String, String> entries = unzip(zip);
        String t = v.getString("tickets[0].id");
        assertThat(entries)
                .containsKeys(
                        "MANIFEST.txt",
                        "tickets/" + t + ".md",
                        "branches/" + agent + "/committed/report.md",
                        "branches/" + agent + "/not-committed/draft.txt");
        assertThat(entries).doesNotContainKey("branches/" + agent + "/not-committed/out/report.pdf");
        assertThat(entries.keySet()).noneMatch(k -> k.contains("secret"));
        assertThat(entries.get("tickets/" + t + ".md")).contains("VALIDATED");
        assertThat(entries.get("MANIFEST.txt"))
                .contains("branches/" + agent + "/committed/report.md\tcommitted at ")
                .contains("branches/" + agent + "/not-committed/draft.txt\tnot committed: exists only in the worktree");
        if (linked) assertThat(entries.get("MANIFEST.txt")).contains("escape");
        assertThat(unzip(api().get(base + "/zip?ignored=true").asByteArray()))
                .containsEntry("branches/" + agent + "/not-committed/out/report.pdf", "%PDF-1.4\n% fake\n");

        // The worktree gone, the committed files still come from the repository.
        if (linked) Files.delete(wt.resolve("escape"));
        LiveMissionTest.run(repo, "git", "worktree", "remove", "--force", wt.toString());
        JsonPath gone = api().get(base).then().statusCode(200).extract().jsonPath();
        assertThat(gone.getBoolean("branches[0].worktreePresent")).isFalse();
        assertThat(statuses(gone, "branches[0].committed")).containsKey("report.md");
        api().get(base + "/file?agent=" + agent + "&source=COMMITTED&path=report.md")
                .then()
                .statusCode(200)
                .body(equalTo("# Report\n"));
        refused(base + "/file?agent=" + agent + "&source=WORKTREE&path=draft.txt", 409, "WORKTREE_GONE");
    }

    @Test
    void anAgentsCredentialIsRefusedOnEveryDeliverablesRoute() throws Exception {
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
            for (String path : List.of(
                    "/api/missions/M-1/deliverables",
                    "/api/missions/M-1/deliverables/file?agent=dev-1&source=WORKTREE&path=a.txt",
                    "/api/missions/M-1/deliverables/zip")) {
                given().header("Authorization", "Bearer " + credential)
                        .get(path)
                        .then()
                        .statusCode(403);
            }
            given().header("Authorization", "Bearer " + credential)
                    .contentType("application/json")
                    .body("{\"agent\":\"dev-1\"}")
                    .post("/api/missions/M-1/deliverables/open-folder")
                    .then()
                    .statusCode(403);
            given().get("/api/missions/M-1/deliverables").then().statusCode(401);
        } finally {
            hooks.unregister(credential);
        }
    }

    @Test
    void opensOnlyAWorktreeFloor42MadeForTheMission() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = deliveredMission(repo);
        String agent = api().get("/api/missions/" + id + "/deliverables")
                .then()
                .extract()
                .path("branches[0].agent");
        List<Path> opened = new ArrayList<>();
        deliverables.useForTests(opened::add);
        try {
            api().body("{\"agent\":\"" + agent + "\"}")
                    .post("/api/missions/" + id + "/deliverables/open-folder")
                    .then()
                    .statusCode(204);
            assertThat(opened)
                    .singleElement()
                    .satisfies(p -> assertThat(p.toString())
                            .contains(id + "-" + agent)
                            .startsWith(dataDir.resolve("workspaces")
                                    .toAbsolutePath()
                                    .normalize()
                                    .toString()));
            api().body("{\"agent\":\"nobody\"}")
                    .post("/api/missions/" + id + "/deliverables/open-folder")
                    .then()
                    .statusCode(404);
            api().body("{}")
                    .post("/api/missions/" + id + "/deliverables/open-folder")
                    .then()
                    .statusCode(400);
            assertThat(opened).hasSize(1);
        } finally {
            deliverables.useForTests(null);
        }
    }

    @Test
    void findsTheWorktreesOfAnOfficeEraMissionInTheFormerDataFolder() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Old\",\"objective\":\"Old notes\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        Path legacy = Files.createTempDirectory("office-legacy");
        Path wt = legacy.resolve("workspaces").resolve(id + "-reviewer");
        Files.createDirectories(wt.getParent());
        LiveMissionTest.run(repo, "git", "worktree", "add", "-q", "-b", "office/old-reviewer", wt.toString(), "HEAD");
        Files.writeString(wt.resolve("report.md"), "# Report\n");
        commit(wt, "Report");
        Files.writeString(wt.resolve("letter.txt"), "Dear\n");
        Path record = dataDir.resolve(DataFolders.MIGRATION_RECORD);
        DataFolders.recordMigration(dataDir, legacy, DataFolders.LEGACY_JOURNAL, 0);
        try {
            JsonPath v = api().get("/api/missions/" + id + "/deliverables")
                    .then()
                    .statusCode(200)
                    .extract()
                    .jsonPath();
            assertThat(v.getString("branches[0].agent")).isEqualTo("reviewer");
            assertThat(v.getBoolean("branches[0].legacy")).isTrue();
            assertThat(v.getString("branches[0].branch")).isEqualTo("office/old-reviewer");
            assertThat(statuses(v, "branches[0].committed")).containsEntry("report.md", "A");
            assertThat(statuses(v, "branches[0].uncommitted")).containsEntry("letter.txt", "?");
            api().get("/api/missions/" + id + "/deliverables/file?agent=reviewer&source=WORKTREE&path=letter.txt")
                    .then()
                    .statusCode(200)
                    .body(equalTo("Dear\n"));
        } finally {
            Files.deleteIfExists(record);
        }
    }

    private void refused(String url, int status, String code) {
        api().get(url).then().statusCode(status).body("code", equalTo(code));
    }

    private static Map<String, String> statuses(JsonPath v, String list) {
        Map<String, String> out = new LinkedHashMap<>();
        List<Map<String, Object>> files = v.getList(list);
        for (Map<String, Object> f : files) out.put((String) f.get("path"), (String) f.get("status"));
        return out;
    }

    private static Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                entries.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    /** A link to a folder outside the worktree: a symbolic link, else a Windows junction; false when neither works. */
    static boolean link(Path at, Path target) {
        try {
            Files.createSymbolicLink(at, target);
            return true;
        } catch (Exception e) {
            // no privilege for symbolic links on Windows: a junction needs none
        }
        try {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", at.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0 && Files.exists(at.resolve("secret.txt"));
        } catch (Exception e) {
            return false;
        }
    }
}
