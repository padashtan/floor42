package dev.floor42.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.event.DeliveryEvent.PullRequestCheckState;
import dev.floor42.domain.event.DeliveryEvent.PullRequestOpened;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.MissionEvent;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.server.app.GhCli;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.PullRequests;
import dev.floor42.server.app.PullRequests.PullRequestRequest;
import dev.floor42.server.app.Workshop;
import dev.floor42.server.app.Workspaces;
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

        // The checks are read on the director's click, once, and recorded.
        Files.writeString(
                s.state().resolve("checks.json"),
                "[{\"name\":\"build\",\"bucket\":\"pass\",\"state\":\"SUCCESS\"},"
                        + "{\"name\":\"lint\",\"bucket\":\"pending\",\"state\":\"IN_PROGRESS\"}]");
        api().post("/api/missions/" + s.mission() + "/pull-requests/7/checks")
                .then()
                .statusCode(200)
                .body("checks.size()", equalTo(2))
                .body("checks[1].bucket", equalTo("pending"));
        assertThat(events(s.mission(), PullRequestCheckState.class)).hasSize(1);
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
}
