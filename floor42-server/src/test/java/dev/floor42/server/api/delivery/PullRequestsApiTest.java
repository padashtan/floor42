package dev.floor42.server.api.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.event.DeliveryEvent.DeliveryEvidenceRecorded;
import dev.floor42.domain.event.DeliveryEvent.PullRequestMerged;
import dev.floor42.domain.event.DeliveryEvent.PullRequestOpened;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.MissionEvent;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.server.api.ApiTestBase;
import dev.floor42.server.api.mission.LiveMissionTest;
import dev.floor42.server.app.core.OfficeService;
import dev.floor42.server.app.delivery.GhCli;
import dev.floor42.server.app.delivery.PullRequests;
import dev.floor42.server.app.delivery.PullRequests.PullRequestRequest;
import dev.floor42.server.app.mission.Workshop;
import dev.floor42.server.app.workspace.Workspaces;
import dev.floor42.server.journal.Journal;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Pull requests (ADR-0036) end to end: a real repository whose remote is a local bare repository (git rewrites the
 * GitHub URL to it), and a fake gh. No test reaches GitHub or any network.
 */
@QuarkusTest
class PullRequestsApiTest extends ApiTestBase {

    static final String GITHUB = "https://github.com/acme/app.git";
    /** An agent of this test only, with no work role: the dispatcher never gives it work. */
    static final AgentId PRIA = new AgentId("pria");

    @Inject
    OfficeService office;

    @Inject
    Workspaces workspaces;

    @Inject
    Workshop workshop;

    @Inject
    PullRequests pullRequests;

    @Inject
    Journal journal;

    @AfterEach
    void realGhBack() {
        pullRequests.useForTests(null, null);
    }

    record Setup(MissionId mission, Path repo, Path bare, Path state, String branch, String head) {}

    /** The fake gh, compiled once per run (FakeTools), playing the scenario in {@code state}. */
    static List<String> fakeGh(String state) {
        List<String> cmd = new java.util.ArrayList<>(dev.floor42.server.FakeTools.ghCommand());
        cmd.addAll(List.of("--fake-state", state));
        return cmd;
    }

    /** A real mission whose worktree holds one commit the director accepted, and one check Floor42 measured. */
    Setup setup(String scenario) throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        Path bare = Files.createTempDirectory("floor42-remote");
        LiveMissionTest.run(bare, "git", "init", "-q", "--bare");
        LiveMissionTest.run(repo, "git", "remote", "add", "origin", GITHUB);
        // The configured URL stays GitHub's; git itself pushes to the local bare repository.
        // In the global settings of the tests (GIT_CONFIG_GLOBAL): Floor42 refuses a repository that sets insteadOf.
        Path global = Path.of(System.getenv("GIT_CONFIG_GLOBAL"));
        // The temp file sits in target/, not in test-classes/: another fork's test discovery walks the classpath
        // folder.
        Path next = Files.writeString(
                Files.createTempFile(global.getParent().getParent(), "gitconfig", ".tmp"),
                "[include]\n\tpath = ~/.gitconfig\n"
                        + "[url \"" + bare.toAbsolutePath().toString().replace('\\', '/') + "\"]\n\tinsteadOf = "
                        + GITHUB + "\n");
        // The global config is shared by the test forks: on Windows a git of another fork holding it open makes the
        // replace fail with a sharing violation for a moment, so it is tried again.
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(next, global, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                break;
            } catch (java.nio.file.FileSystemException e) {
                // Handled: a sharing violation lasts while the other git runs; after 5 s it is a real failure
                if (attempt == 50) throw e;
                Thread.sleep(100);
            }
        }
        Path state = Files.createTempDirectory("floor42-fake-gh");
        Files.writeString(state.resolve("scenario"), scenario, StandardCharsets.UTF_8);
        pullRequests.useForTests(new GhCli.Process(fakeGh(state.toString())), 20L);

        if (office.query(c -> c.staff().find(PRIA).isEmpty())) {
            office.execute(c -> c.staff()
                    .hire(
                            office.context(Actor.director(), Origin.REAL),
                            PRIA,
                            "Pria",
                            "Developer of the pull request tests",
                            "anthropic",
                            "claude-sonnet-5-5",
                            "low",
                            List.of()));
        }
        MissionId m = (MissionId) ((MissionEvent.MissionCreated) office.execute(c -> c.missions()
                                .create(
                                        office.context(Actor.director(), Origin.REAL),
                                        "Notes",
                                        "Write the project's notes",
                                        repo.toString(),
                                        Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        Path worktree = workspaces.worktree(repo.toString(), m, PRIA);
        String branch = workspaces.branch(worktree);
        String base = workspaces.head(worktree);
        office.execute(c -> c.workshop()
                .prepareWorkspace(
                        office.context(Actor.system(), Origin.REAL), m, PRIA, worktree.toString(), branch, base));
        Files.writeString(worktree.resolve("notes.txt"), "the notes\n");
        String head = workshop.accept(m, PRIA, "Add the notes");
        office.execute(c -> c.verification()
                .recordChecks(
                        office.context(Actor.system(), Origin.REAL),
                        m,
                        PRIA,
                        "mvn -q verify",
                        0,
                        false,
                        12_300,
                        "BUILD SUCCESS",
                        head,
                        "director"));
        return new Setup(m, repo, bare, state, branch, head);
    }

    JsonPath preview(Setup s, String workspace) {
        var spec = api();
        if (workspace != null) spec = spec.queryParam("workspace", workspace);
        return spec.get("/api/missions/" + s.mission() + "/pull-requests/preview")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    Map<String, String> confirm(JsonPath p) {
        Map<String, String> body = new java.util.HashMap<>();
        body.put("workspace", p.getString("workspace"));
        body.put("remote", p.getString("remote"));
        body.put("branch", p.getString("branch"));
        body.put("base", p.getString("base"));
        body.put("title", p.getString("title"));
        body.put("body", p.getString("body"));
        body.put("remoteUrl", p.getString("remoteUrl"));
        return body;
    }

    String onRemote(Setup s, String branch) throws Exception {
        Process p = new ProcessBuilder("git", "-C", s.bare().toString(), "rev-parse", "--verify", "--quiet", branch)
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        return p.waitFor() == 0 ? out : null;
    }

    List<DomainEvent> events(MissionId m, Class<? extends DomainEvent> type) {
        return journal.readAll().stream()
                .filter(e -> m.equals(e.mission()) && type.isInstance(e.event()))
                .map(e -> e.event())
                .toList();
    }

    String calls(Setup s) throws Exception {
        Path log = s.state().resolve("calls.log");
        return Files.exists(log) ? Files.readString(log) : "";
    }

    @Test
    void theDirectorConfirmsOfficePushesAndGhOpensThePullRequest() throws Exception {
        Setup s = setup("auth=yes\ndefault=main\ncreate=ok\n");

        JsonPath p = preview(s, "pria");
        assertThat(p.getString("refused")).as(p.getString("refusal")).isNull();
        assertThat(p.getString("remote")).isEqualTo("origin");
        assertThat(p.getString("remoteUrl")).isEqualTo(GITHUB);
        assertThat(p.getString("repository")).isEqualTo("github.com/acme/app");
        assertThat(p.getString("branch")).isEqualTo(s.branch());
        assertThat(p.getString("base")).isEqualTo("main");
        assertThat(p.getInt("commits")).isEqualTo(1);
        assertThat(p.getString("title")).isEqualTo("Notes (" + s.mission() + ")");
        assertThat(p.getString("body"))
                .contains("Prepared with Floor42")
                .contains("Write the project's notes")
                .contains("`mvn -q verify`")
                .contains("**passed**")
                .contains("Written by AI agents")
                .contains("Add the notes");
        // The preview pushed nothing and asked GitHub nothing but questions.
        assertThat(onRemote(s, s.branch())).isNull();
        assertThat(calls(s)).doesNotContain("pr create");

        Map<String, String> body = confirm(p);
        body.put("title", "Write the notes");
        api().body(body)
                .post("/api/missions/" + s.mission() + "/pull-requests")
                .then()
                .statusCode(200)
                .body("outcome", equalTo("OPENED"))
                .body("pushed", equalTo(true))
                .body("pullRequest.number", equalTo(7))
                .body("pullRequest.url", equalTo("https://github.com/acme/app/pull/7"));
        assertThat(onRemote(s, s.branch())).isEqualTo(s.head());
        assertThat(Files.readString(s.state().resolve("title.txt"))).isEqualTo("Write the notes");
        assertThat(Files.readString(s.state().resolve("body.md"))).contains("Prepared with Floor42");
        assertThat(events(s.mission(), PullRequestOpened.class))
                .singleElement()
                .satisfies(e -> assertThat(((PullRequestOpened) e).found()).isFalse());
        assertThat(journal.readAll().stream()
                        .filter(e -> e.event() instanceof PullRequestOpened)
                        .map(e -> e.origin())
                        .distinct())
                .containsExactly(Origin.REAL);

        // The checks are read on the director's click, once, for the exact head commit, and recorded.
        Files.writeString(s.state().resolve("head.txt"), s.head());
        runs(s, s.head(), run(1, "build", "completed", "success"), run(2, "lint", "in_progress", null));
        api().post("/api/missions/" + s.mission() + "/pull-requests/7/checks")
                .then()
                .statusCode(200)
                .body("checks.size()", equalTo(2))
                .body("checks.find { it.name == 'lint' }.bucket", equalTo("pending"))
                .body("commit", equalTo(s.head()))
                .body("conclusion", equalTo("PENDING"));
        assertThat(events(s.mission(), DeliveryEvidenceRecorded.class)).hasSize(1);
        api().get("/api/missions/" + s.mission() + "/pull-requests")
                .then()
                .statusCode(200)
                .body("[0].number", equalTo(7))
                .body("[0].checks.size()", equalTo(2));

        // Confirming again never opens a second pull request: Floor42 finds the open one first.
        api().body(body)
                .post("/api/missions/" + s.mission() + "/pull-requests")
                .then()
                .statusCode(200)
                .body("outcome", equalTo("FOUND"))
                .body("pullRequest.number", equalTo(7));
        assertThat(calls(s).lines().filter(l -> l.startsWith("pr create"))).hasSize(1);
        assertThat(events(s.mission(), PullRequestOpened.class)).hasSize(1);
    }

    @Test
    void aWorktreeChangingClaudeConfigurationIsNeitherAcceptedNorProposed() throws Exception {
        Setup s = setup("auth=yes\n");
        Path worktree = workspaces.worktree(s.repo().toString(), s.mission(), PRIA);
        Files.createDirectories(worktree.resolve(".claude/skills/x"));
        Files.writeString(worktree.resolve(".claude/skills/x/SKILL.md"), "!`id`\n");
        assertThatThrownBy(() -> workshop.accept(s.mission(), PRIA, "Add a skill"))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo("PROTECTED_PATH");

        // The agent committed it itself, from its shell.
        LiveMissionTest.run(worktree, "git", "add", "-f", ".claude");
        LiveMissionTest.run(
                worktree, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "skill");
        JsonPath p = preview(s, "pria");
        assertThat(p.getString("refused")).isEqualTo("PROTECTED_PATH");
        assertThat(p.getString("refusal")).contains(".claude/skills/x/SKILL.md");
    }

    @Test
    void withoutGhOfficeSaysSoAndPushesNothing() throws Exception {
        Setup s = setup("auth=yes\n");
        pullRequests.useForTests(new GhCli.Process(List.of("floor42-no-such-gh-command")), 20L);

        JsonPath p = preview(s, "pria");
        assertThat(p.getString("refused")).isEqualTo("GH_MISSING");
        assertThat(p.getBoolean("gh.available")).isFalse();
        assertThat(p.getString("gh.fix")).isEqualTo("gh auth login");
        assertThat(p.getString("refusal")).contains("https://cli.github.com");

        api().body(confirm(p))
                .post("/api/missions/" + s.mission() + "/pull-requests")
                .then()
                .statusCode(409)
                .body("code", equalTo("GH_MISSING"));
        assertThat(onRemote(s, s.branch())).isNull();
    }

    @Test
    void notSignedInOfficeShowsTheCommandAndNeverSignsIn() throws Exception {
        Setup s = setup("auth=no\n");

        JsonPath p = preview(s, "pria");
        assertThat(p.getString("refused")).isEqualTo("GH_NOT_AUTHENTICATED");
        assertThat(p.getBoolean("gh.available")).isTrue();
        assertThat(p.getBoolean("gh.authenticated")).isFalse();
        assertThat(p.getString("gh.fix")).isEqualTo("gh auth login --hostname github.com");

        api().body(confirm(p))
                .post("/api/missions/" + s.mission() + "/pull-requests")
                .then()
                .statusCode(409)
                .body("code", equalTo("GH_NOT_AUTHENTICATED"));
        assertThat(onRemote(s, s.branch())).isNull();
        assertThat(calls(s)).doesNotContain("auth login").doesNotContain("pr create");
    }

    @Test
    void afterATimeoutOfficeLooksOnGitHubAndFindsThePullRequest() throws Exception {
        Setup s = setup("auth=yes\ncreate=timeout\n");
        JsonPath p = preview(s, "pria");
        // Each gh call may take this long: the fake, precompiled, answers in a fraction of it; its pr create never
        // does.
        pullRequests.useForTests(new GhCli.Process(fakeGh(s.state().toString())), 4L);

        api().body(confirm(p))
                .post("/api/missions/" + s.mission() + "/pull-requests")
                .then()
                .statusCode(200)
                .body("outcome", equalTo("FOUND"))
                .body("pushed", equalTo(true))
                .body("retry", equalTo(false))
                .body("detail", containsString("timed out"))
                .body("pullRequest.number", equalTo(7));
        assertThat(events(s.mission(), PullRequestOpened.class))
                .singleElement()
                .satisfies(e -> assertThat(((PullRequestOpened) e).found()).isTrue());
    }

    @Test
    void theDefaultBranchIsNeverTheHead() throws Exception {
        Setup s = setup("auth=yes\ndefault=main\n");

        // The director's own branch of the project is main.
        JsonPath own = preview(s, null);
        assertThat(own.getString("branch")).isEqualTo("main");
        assertThat(own.getString("refused")).isEqualTo("HEAD_IS_DEFAULT");
        Map<String, String> body = confirm(own);
        body.put("base", "main");
        api().body(body)
                .post("/api/missions/" + s.mission() + "/pull-requests")
                .then()
                .statusCode(409)
                .body("code", equalTo("HEAD_IS_DEFAULT"));

        // A repository whose default branch has the worktree's name: refused too.
        Files.writeString(s.state().resolve("scenario"), "auth=yes\ndefault=" + s.branch() + "\n");
        JsonPath p = preview(s, "pria");
        assertThat(p.getString("refused")).isEqualTo("HEAD_IS_DEFAULT");
        api().body(confirm(p))
                .post("/api/missions/" + s.mission() + "/pull-requests")
                .then()
                .statusCode(409)
                .body("code", equalTo("HEAD_IS_DEFAULT"));
        assertThat(onRemote(s, "main")).isNull();
        assertThat(onRemote(s, s.branch())).isNull();
    }

    @Test
    void aRemoteUrlRewrittenAfterThePreviewIsRefusedAndNothingIsPushed() throws Exception {
        Setup s = setup("auth=yes\n");
        JsonPath p = preview(s, "pria");
        LiveMissionTest.run(
                s.repo(), "git", "config", "--local", "remote.origin.url", "https://github.com/acme/other.git");

        api().body(confirm(p))
                .post("/api/missions/" + s.mission() + "/pull-requests")
                .then()
                .statusCode(409)
                .body("code", equalTo("PREVIEW_STALE"));
        assertThat(onRemote(s, s.branch())).isNull();
        assertThat(calls(s)).doesNotContain("pr create");
    }

    @Test
    void aPlantedPrePushHookDoesNotRunWhenTheDirectorHasNoGlobalHooksPath() throws Exception {
        Setup s = setup("auth=yes\n");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                !globalHooksPath(), "the machine's own settings name a hooks folder: git reads no .git/hooks then");
        JsonPath p = preview(s, "pria");
        Path marker = s.state().resolve("hook-ran");
        Path hook = s.repo().resolve(".git/hooks/pre-push");
        Files.createDirectories(hook.getParent());
        Files.writeString(hook, "#!/bin/sh\ntouch \"" + marker.toString().replace('\\', '/') + "\"\n");
        hook.toFile().setExecutable(true);

        api().body(confirm(p))
                .post("/api/missions/" + s.mission() + "/pull-requests")
                .then()
                .statusCode(200)
                .body("outcome", equalTo("OPENED"));
        assertThat(onRemote(s, s.branch())).isEqualTo(s.head());
        assertThat(marker).doesNotExist();
    }

    private static boolean globalHooksPath() throws Exception {
        for (String scope : List.of("--global", "--system")) {
            Process r = new ProcessBuilder("git", "config", scope, "--get", "core.hooksPath")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (r.waitFor() == 0 && !out.isBlank()) return true;
        }
        return false;
    }

    @Test
    void anAgentCanNeverOpenOne() throws Exception {
        Setup s = setup("auth=yes\n");
        JsonPath p = preview(s, "pria");
        Map<String, String> b = confirm(p);
        PullRequestRequest request = new PullRequestRequest(
                b.get("workspace"), b.get("remote"), b.get("branch"), b.get("base"), "t", "b", b.get("remoteUrl"));
        String before = calls(s);

        assertThatThrownBy(() -> pullRequests.open(Actor.agent(PRIA), s.mission(), request))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("FORBIDDEN");
        assertThatThrownBy(() -> pullRequests.preview(Actor.agent(PRIA), s.mission(), "pria"))
                .extracting("code")
                .isEqualTo("FORBIDDEN");
        assertThatThrownBy(() -> pullRequests.open(Actor.system(), s.mission(), request))
                .extracting("code")
                .isEqualTo("FORBIDDEN");
        assertThat(calls(s)).isEqualTo(before);
        assertThat(onRemote(s, s.branch())).isNull();
        assertThat(events(s.mission(), PullRequestOpened.class)).isEmpty();
    }

    static String run(int id, String name, String status, String conclusion) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"status\":\"" + status + "\",\"conclusion\":"
                + (conclusion == null ? "null" : "\"" + conclusion + "\"") + "}";
    }

    /** A run of a given check suite (one workflow of the repository). */
    static String run(int suite, int id, String name, String status, String conclusion) {
        return run(id, name, status, conclusion).replaceFirst("\\{", "{\"check_suite\":{\"id\":" + suite + "},");
    }

    /** The legacy statuses of a commit: {@code commits/<sha>/status}. */
    static void statuses(Setup s, String sha, String combined, String... pairs) throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) rows.append(',');
            rows.append("{\"context\":\"")
                    .append(pairs[i])
                    .append("\",\"state\":\"")
                    .append(pairs[i + 1])
                    .append("\"}");
        }
        Files.writeString(
                s.state().resolve("status-" + sha + ".json"),
                "{\"state\":\"" + combined + "\",\"total_count\":" + pairs.length / 2 + ",\"statuses\":[" + rows
                        + "]}");
    }

    /** The director's browser: the session cookie of a sign-in, which is what a merge is allowed to come from. */
    io.restassured.specification.RequestSpecification browser() {
        String cookie = io.restassured.RestAssured.given()
                .config(io.restassured.config.RestAssuredConfig.config()
                        .redirect(io.restassured.config.RedirectConfig.redirectConfig()
                                .followRedirects(false)))
                .header("Authorization", "Bearer " + token.value())
                .post("/auth")
                .getDetailedCookie(dev.floor42.server.security.LocalAccessGuard.COOKIE)
                .getValue();
        return io.restassured.RestAssured.given()
                .contentType("application/json")
                .cookie(dev.floor42.server.security.LocalAccessGuard.COOKIE, cookie)
                .header("Origin", "http://127.0.0.1:" + io.restassured.RestAssured.port)
                .header("X-Floor42-Request", "1");
    }

    /** What GitHub answers for {@code commits/<sha>/check-runs}: only the runs of that commit. */
    static void runs(Setup s, String sha, String... runs) throws Exception {
        Files.writeString(
                s.state().resolve("check-runs-" + sha + ".json"),
                "{\"total_count\":" + runs.length + ",\"check_runs\":[" + String.join(",", runs) + "]}");
    }

    /** A mission whose pull request #7 is open, with its head commit known to the fake gh. */
    Setup opened() throws Exception {
        Setup s = setup("auth=yes\ndefault=main\ncreate=ok\n");
        api().body(confirm(preview(s, "pria")))
                .post("/api/missions/" + s.mission() + "/pull-requests")
                .then()
                .statusCode(200)
                .body("outcome", equalTo("OPENED"));
        Files.writeString(s.state().resolve("head.txt"), s.head());
        return s;
    }

    String path(Setup s, String tail) {
        return "/api/missions/" + s.mission() + "/pull-requests/7" + tail;
    }

    @Test
    void theChecksAreThoseOfTheExactHeadAndTheLatestRunOfANameWins() throws Exception {
        Setup s = opened();
        String other = "1".repeat(40);
        runs(s, other, run(9, "build", "completed", "failure"));
        // A cancelled run of an older attempt sits beside the run that replaced it, on the same commit.
        runs(
                s,
                s.head(),
                run(10, "e2e", "completed", "cancelled"),
                run(12, "e2e", "completed", "success"),
                run(11, "build", "completed", "success"),
                run(13, "docs", "completed", "skipped"));
        api().post(path(s, "/checks"))
                .then()
                .statusCode(200)
                .body("commit", equalTo(s.head()))
                .body("conclusion", equalTo("GREEN"))
                .body("stale", equalTo(false))
                .body("checks.size()", equalTo(3))
                .body("checks.find { it.name == 'e2e' }.bucket", equalTo("pass"))
                .body("checks.find { it.name == 'docs' }.bucket", equalTo("skipping"));
        assertThat(calls(s)).contains("commits/" + s.head() + "/check-runs").doesNotContain(other);
        assertThat(events(s.mission(), DeliveryEvidenceRecorded.class))
                .singleElement()
                .satisfies(e -> {
                    var d = (DeliveryEvidenceRecorded) e;
                    assertThat(d.commit()).isEqualTo(s.head());
                    assertThat(d.conclusion()).isEqualTo("GREEN");
                });
        // A run still cancelled with nothing after it is not a pass.
        runs(s, s.head(), run(10, "e2e", "completed", "cancelled"));
        api().post(path(s, "/checks")).then().statusCode(200).body("conclusion", equalTo("FAILED"));
    }

    @Test
    void aPushToTheBranchMarksTheEvidenceStale() throws Exception {
        Setup s = opened();
        runs(s, s.head(), run(1, "build", "completed", "success"));
        api().post(path(s, "/checks")).then().statusCode(200).body("stale", equalTo(false));
        api().get(path(s, "/head")).then().statusCode(200).body("stale", equalTo(false));

        Files.writeString(s.state().resolve("head.txt"), "2".repeat(40));
        api().get(path(s, "/head"))
                .then()
                .statusCode(200)
                .body("stale", equalTo(true))
                .body("commit", equalTo(s.head()))
                .body("headNow", equalTo("2".repeat(40)));
        // Reading the head records nothing.
        assertThat(events(s.mission(), DeliveryEvidenceRecorded.class)).hasSize(1);
    }

    @Test
    void aBranchThatMovesWhileTheChecksAreReadRecordsNothing() throws Exception {
        Setup s = opened();
        runs(s, s.head(), run(1, "build", "completed", "success"));
        Files.writeString(s.state().resolve("head-after-checks.txt"), "2".repeat(40));
        api().post(path(s, "/checks")).then().statusCode(409).body("code", equalTo("HEAD_MOVED"));
        assertThat(events(s.mission(), DeliveryEvidenceRecorded.class)).isEmpty();
    }

    @Test
    void aMergeIsRefusedUnlessTheChecksOfTheCurrentHeadAreGreen() throws Exception {
        Setup s = opened();
        String body = "{\"commit\":\"" + s.head() + "\"}";
        // No check at all.
        browser().body(body).post(path(s, "/merge")).then().statusCode(409).body("code", equalTo("CHECKS_NOT_GREEN"));
        // One failed.
        runs(s, s.head(), run(1, "build", "completed", "success"), run(2, "lint", "completed", "failure"));
        browser().body(body).post(path(s, "/merge")).then().statusCode(409).body("code", equalTo("CHECKS_NOT_GREEN"));
        // The director saw another commit than the head now.
        runs(s, s.head(), run(1, "build", "completed", "success"));
        browser()
                .body("{\"commit\":\"" + "3".repeat(40) + "\"}")
                .post(path(s, "/merge"))
                .then()
                .statusCode(409)
                .body("code", equalTo("EVIDENCE_STALE"));
        // The branch moved to a commit with no green run.
        Files.writeString(s.state().resolve("head.txt"), "2".repeat(40));
        browser().body(body).post(path(s, "/merge")).then().statusCode(409).body("code", equalTo("EVIDENCE_STALE"));
        assertThat(calls(s)).doesNotContain("pr merge");
        assertThat(events(s.mission(), PullRequestMerged.class)).isEmpty();
    }

    @Test
    void aGreenHeadIsMergedOnceAtThatCommitAndBothEventsAreRecorded() throws Exception {
        Setup s = opened();
        runs(s, s.head(), run(1, "build", "completed", "success"));
        String body = "{\"commit\":\"" + s.head() + "\"}";
        browser()
                .body(body)
                .post(path(s, "/merge"))
                .then()
                .statusCode(200)
                .body("mergedCommit", equalTo(s.head()))
                .body("conclusion", equalTo("GREEN"));
        // GitHub is told which commit to merge: a push in between makes gh refuse instead of merging unseen work.
        assertThat(calls(s)).contains("pr merge 7").contains("--match-head-commit " + s.head());
        assertThat(events(s.mission(), DeliveryEvidenceRecorded.class)).hasSize(1);
        assertThat(events(s.mission(), PullRequestMerged.class))
                .singleElement()
                .satisfies(e -> assertThat(((PullRequestMerged) e).commit()).isEqualTo(s.head()));
        browser()
                .body(body)
                .post(path(s, "/merge"))
                .then()
                .statusCode(409)
                .body("code", equalTo("PULL_REQUEST_MERGED"));
    }

    @Test
    void aMergeGhRefusesIsNotRecorded() throws Exception {
        Setup s = opened();
        Files.writeString(s.state().resolve("scenario"), "auth=yes\nmerge=fail\n");
        runs(s, s.head(), run(1, "build", "completed", "success"));
        browser()
                .body("{\"commit\":\"" + s.head() + "\"}")
                .post(path(s, "/merge"))
                .then()
                .statusCode(409)
                .body("code", equalTo("GH_FAILED"));
        assertThat(events(s.mission(), PullRequestMerged.class)).isEmpty();
    }

    @Test
    void aMergeComesFromTheDirectorsBrowserOnlyWhateverTheTokenAndTheMode() throws Exception {
        Setup s = opened();
        runs(s, s.head(), run(1, "build", "completed", "success"));
        String body = "{\"commit\":\"" + s.head() + "\"}";
        // The director's own bearer is a token like any other: IAM mode off does not make it a click.
        api().body(body).post(path(s, "/merge")).then().statusCode(403).body("code", equalTo("HUMAN_ONLY"));
        assertThat(calls(s)).doesNotContain("pr merge");
        browser().body(body).post(path(s, "/merge")).then().statusCode(200);
        assertThat(events(s.mission(), PullRequestMerged.class))
                .singleElement()
                .satisfies(e -> assertThat(((PullRequestMerged) e).approval()).isEqualTo("CLICK"));
    }

    @Test
    void aPullRequestThatIsNotOpenIsNeverMerged() throws Exception {
        Setup s = opened();
        runs(s, s.head(), run(1, "build", "completed", "success"));
        String body = "{\"commit\":\"" + s.head() + "\"}";
        Files.writeString(s.state().resolve("state.txt"), "CLOSED");
        browser().body(body).post(path(s, "/merge")).then().statusCode(409).body("code", equalTo("NOT_OPEN"));
        Files.writeString(s.state().resolve("state.txt"), "MERGED");
        browser()
                .body(body)
                .post(path(s, "/merge"))
                .then()
                .statusCode(409)
                .body("code", equalTo("PULL_REQUEST_MERGED"));
        assertThat(calls(s)).doesNotContain("pr merge");
    }

    @Test
    void aMergeIsRecordedOnlyWhenGhSucceededAndGitHubSaysMerged() throws Exception {
        String body;
        Setup s = opened();
        runs(s, s.head(), run(1, "build", "completed", "success"));
        body = "{\"commit\":\"" + s.head() + "\"}";
        // gh failed, yet GitHub shows the pull request merged: said so, not recorded as Floor42's merge.
        Files.writeString(s.state().resolve("scenario"), "auth=yes\nmerge=fail-but-merged\n");
        browser().body(body).post(path(s, "/merge")).then().statusCode(409).body("code", equalTo("GH_FAILED"));
        assertThat(events(s.mission(), PullRequestMerged.class)).isEmpty();

        Setup t = opened();
        runs(t, t.head(), run(1, "build", "completed", "success"));
        Files.writeString(t.state().resolve("scenario"), "auth=yes\nmerge=ok-but-open\n");
        browser()
                .body("{\"commit\":\"" + t.head() + "\"}")
                .post(path(t, "/merge"))
                .then()
                .statusCode(409)
                .body("code", equalTo("GH_FAILED"));
        assertThat(events(t.mission(), PullRequestMerged.class)).isEmpty();
    }

    @Test
    void theSameCheckNameInTwoWorkflowsKeepsBothAndAFailureIsNotHidden() throws Exception {
        Setup s = opened();
        runs(
                s,
                s.head(),
                run(100, 5, "build", "completed", "failure"),
                run(200, 6, "build", "completed", "success"),
                run(200, 4, "lint", "completed", "failure"),
                run(200, 7, "lint", "completed", "success"));
        api().post(path(s, "/checks"))
                .then()
                .statusCode(200)
                .body("conclusion", equalTo("FAILED"))
                .body("checks.size()", equalTo(3))
                .body("checks.findAll { it.name.startsWith('build') }.size()", equalTo(2))
                .body("checks.find { it.name == 'lint' }.bucket", equalTo("pass"));
    }

    @Test
    void aLegacyStatusCountsAndAnEmptyCombinedPendingDoesNot() throws Exception {
        Setup s = opened();
        runs(s, s.head(), run(1, "build", "completed", "success"));
        // The default answer of this repository's statuses: combined pending, none reported. Not a failure.
        api().post(path(s, "/checks")).then().statusCode(200).body("conclusion", equalTo("GREEN"));
        statuses(s, s.head(), "failure", "ci/legacy", "failure");
        api().post(path(s, "/checks")).then().statusCode(200).body("conclusion", equalTo("FAILED"));
        statuses(s, s.head(), "pending", "ci/legacy", "pending");
        api().post(path(s, "/checks")).then().statusCode(200).body("conclusion", equalTo("PENDING"));
        statuses(s, s.head(), "success", "ci/legacy", "success");
        api().post(path(s, "/checks"))
                .then()
                .statusCode(200)
                .body("conclusion", equalTo("GREEN"))
                .body("checks.find { it.name == 'ci/legacy' }.bucket", equalTo("pass"));
    }

    @Test
    void aCheckSeenBeforeAndNotYetCreatedNowIsPendingNotAbsent() throws Exception {
        Setup s = opened();
        runs(s, s.head(), run(1, "build", "completed", "success"), run(2, "e2e", "completed", "success"));
        api().post(path(s, "/checks")).then().statusCode(200).body("conclusion", equalTo("GREEN"));
        runs(s, s.head(), run(1, "build", "completed", "success"));
        api().post(path(s, "/checks"))
                .then()
                .statusCode(200)
                .body("conclusion", equalTo("PENDING"))
                .body("checks.find { it.name == 'e2e' }.bucket", equalTo("pending"));
        browser()
                .body("{\"commit\":\"" + s.head() + "\"}")
                .post(path(s, "/merge"))
                .then()
                .statusCode(409)
                .body("code", equalTo("CHECKS_NOT_GREEN"));
    }

    @Test
    void onlySkippedChecksProveNothing() throws Exception {
        Setup s = opened();
        runs(s, s.head(), run(1, "docs", "completed", "skipped"));
        api().post(path(s, "/checks")).then().statusCode(200).body("conclusion", equalTo("NONE"));
    }

    @Test
    void aMergeRunsAloneAndNothingReadsTheChecksOfThatMissionWhileItDoes() throws Exception {
        Setup s = opened();
        runs(s, s.head(), run(1, "build", "completed", "success"));
        Files.writeString(s.state().resolve("scenario"), "auth=yes\nmerge=slow\n");
        String body = "{\"commit\":\"" + s.head() + "\"}";
        Thread merging = Thread.ofVirtual().start(() -> browser().body(body).post(path(s, "/merge")));
        for (int i = 0; i < 100 && !calls(s).contains("pr merge"); i++) Thread.sleep(50);
        api().post(path(s, "/checks")).then().statusCode(409).body("code", equalTo("PULL_REQUEST_RUNNING"));
        merging.join();
        assertThat(events(s.mission(), PullRequestMerged.class)).hasSize(1);
    }

    @Test
    void aStateReadThatFailsAfterTheMergeIsReadAgainAndNeverReportedAsNotMerged() throws Exception {
        Setup s = opened();
        runs(s, s.head(), run(1, "build", "completed", "success"));
        String body = "{\"commit\":\"" + s.head() + "\"}";
        // GitHub answers late twice: the third read says MERGED.
        Files.writeString(s.state().resolve("view-fails"), "2");
        browser().body(body).post(path(s, "/merge")).then().statusCode(200);
        assertThat(events(s.mission(), PullRequestMerged.class)).hasSize(1);

        Setup t = opened();
        runs(t, t.head(), run(1, "build", "completed", "success"));
        Files.writeString(t.state().resolve("view-fails"), "-1");
        browser()
                .body("{\"commit\":\"" + t.head() + "\"}")
                .post(path(t, "/merge"))
                .then()
                .statusCode(409)
                .body("code", equalTo("MERGE_UNKNOWN"))
                .body("message", org.hamcrest.Matchers.containsString("look at it on GitHub"));
        assertThat(events(t.mission(), PullRequestMerged.class)).isEmpty();
    }

    @Test
    void aMergeVerifiesAgainWhenItRunsAfterTheSignIn() throws Exception {
        Setup s = opened();
        runs(s, s.head(), run(1, "build", "completed", "success"));
        // What the step-up carries out later is the service's merge: it reads the head and the checks itself.
        Files.writeString(s.state().resolve("head.txt"), "2".repeat(40));
        assertThatThrownBy(() -> pullRequests.merge(s.mission(), 7, s.head(), "MERGE:x"))
                .isInstanceOfSatisfying(
                        DomainException.class, e -> assertThat(e.code()).isEqualTo("EVIDENCE_STALE"));
        assertThat(calls(s)).doesNotContain("pr merge");
    }

    @Test
    void aRemoteRewrittenAfterTheOpeningIsRefusedForTheChecksAndTheMerge() throws Exception {
        Setup s = opened();
        runs(s, s.head(), run(1, "build", "completed", "success"));
        api().post(path(s, "/checks")).then().statusCode(200).body("conclusion", equalTo("GREEN"));
        LiveMissionTest.run(
                s.repo(), "git", "config", "--local", "remote.origin.url", "https://github.com/acme/other.git");
        String before = calls(s);
        api().post(path(s, "/checks")).then().statusCode(409).body("code", equalTo("REMOTE_CHANGED"));
        browser()
                .body("{\"commit\":\"" + s.head() + "\"}")
                .post(path(s, "/merge"))
                .then()
                .statusCode(409)
                .body("code", equalTo("REMOTE_CHANGED"));
        assertThat(calls(s)).doesNotContain("pr merge").isEqualTo(before);
    }

    @Test
    void aStatusBeyondTheFirstPageIsNeverReadAsGreen() throws Exception {
        Setup s = opened();
        runs(s, s.head(), run(1, "build", "completed", "success"));
        String[] pairs = new String[62];
        for (int i = 0; i < 31; i++) {
            pairs[2 * i] = "ci/" + i;
            pairs[2 * i + 1] = "success";
        }
        // GitHub lists 31 of 32 statuses: the failing one is on the next page.
        statuses(s, s.head(), "failure", pairs);
        Files.writeString(
                s.state().resolve("status-" + s.head() + ".json"),
                Files.readString(s.state().resolve("status-" + s.head() + ".json"))
                        .replace("\"total_count\":31", "\"total_count\":32"));
        var r = api().post(path(s, "/checks"));
        assertThat(r.statusCode()).isNotEqualTo(200);
        assertThat(calls(s)).contains("per_page=100");
    }

    @Test
    void aScriptTokenCannotMergeEither() throws Exception {
        Setup s = opened();
        runs(s, s.head(), run(1, "build", "completed", "success"));
        String script = api().body("{\"scope\":\"all\",\"days\":1,\"label\":\"merge test\"}")
                .post("/api/auth/tokens")
                .then()
                .statusCode(201)
                .extract()
                .path("token");
        io.restassured.RestAssured.given()
                .contentType("application/json")
                .header("Authorization", "Bearer " + script)
                .body("{\"commit\":\"" + s.head() + "\"}")
                .post(path(s, "/merge"))
                .then()
                .statusCode(403)
                .body("code", equalTo("HUMAN_ONLY"));
        assertThat(calls(s)).doesNotContain("pr merge");
    }
}
