package dev.floor42.server.runtime;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Action;
import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.ActionStage;
import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.Decision;
import dev.floor42.domain.DenialReason;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.server.app.MissionGovernor;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The live runtime against a protocol-faithful fake of Claude Code (no tokens spent): hook calls really go through
 * HTTP to the mission policy, results are read from the stream, the session is recorded.
 */
@QuarkusTest
@TestProfile(ClaudeCodeRuntimeTest.RuntimeOnly.class)
class ClaudeCodeRuntimeTest {

    /** This test drives sessions by hand: the mission orchestrator must stay out of the way. */
    public static class RuntimeOnly implements QuarkusTestProfile {
        @Override
        public java.util.Map<String, String> getConfigOverrides() {
            // A short silence limit, so a session stuck without a word is stopped within the test.
            // A short wait for the director, so an unanswered question is withdrawn within the test.
            // A short hook timeout: a shell call nobody answers is refused after 20 - 15 s, not 30 - 15 s (most
            // sessions here play the default script, whose Bash call asks the director and is never answered).
            return java.util.Map.of(
                    "floor42.live-missions.enabled",
                    "false",
                    "floor42.claude.session-timeout-seconds",
                    "6",
                    "floor42.claude.question-timeout-seconds",
                    "4",
                    "floor42.claude.hook-timeout-seconds",
                    "20",
                    "floor42.sandbox.assume-available",
                    "true");
        }
    }

    static final AgentId CHRIS = new AgentId("chris");

    @Inject
    OfficeService office;

    @Inject
    ClaudeCodeRuntime runtime;

    @Inject
    SessionToken token;

    @Inject
    Journal journal;

    @Inject
    ClaudeCodeProbe probe;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    static List<String> sleeper() {
        return System.getProperty("os.name").startsWith("Windows")
                ? List.of("ping", "-n", "300", "127.0.0.1")
                : List.of("sleep", "300");
    }

    record Setup(MissionId mission, TicketId ticket, String workdir) {}

    Setup realMissionInProgress() throws Exception {
        Path workdir = Files.createTempDirectory("office-session");
        if (office.query(c -> c.staff().find(CHRIS).isEmpty())) {
            office.execute(c -> c.staff()
                    .hire(
                            director(),
                            CHRIS,
                            "Chris",
                            "Java developer",
                            "anthropic",
                            "claude-sonnet-5-5",
                            "medium",
                            List.of()));
        }
        MissionId m = ((MissionCreated) office.execute(c -> c.missions()
                                .create(
                                        director(),
                                        "Inspect",
                                        "Inspect the repository",
                                        workdir.toString(),
                                        Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        TicketId t = ((TicketCreated) office.execute(c -> c.tickets()
                                .create(director(), m, "Inspect", "", Priority.NORMAL, List.of(), List.of("report")))
                        .getFirst()
                        .event())
                .ticket();
        office.execute(c -> c.tickets().assign(director(), t, CHRIS));
        office.execute(c -> c.missions().setToolPermission(director(), m, "Read", ActionCategory.READ, Decision.ALLOW));
        office.execute(
                c -> c.missions().setToolPermission(director(), m, "Bash", ActionCategory.EXECUTE, Decision.ASK));
        office.execute(
                c -> c.missions().setToolPermission(director(), m, "Write", ActionCategory.WRITE, Decision.DENY));
        office.execute(c -> c.missions().submitPlan(director(), m));
        office.execute(c -> c.verification().setBudget(director(), m, null));
        office.execute(c -> c.missions().approvePlan(director(), m));
        office.execute(c -> c.tickets().start(office.context(Actor.agent(CHRIS), Origin.REAL), t));
        return new Setup(m, t, workdir.toString());
    }

    dev.floor42.domain.CommandContext director() {
        return office.context(Actor.director(), Origin.REAL);
    }

    SessionSpec spec(Setup s, String brief) {
        return new SessionSpec(
                s.mission(),
                s.ticket(),
                CHRIS,
                Origin.REAL,
                s.workdir(),
                brief,
                "claude-sonnet-5-5",
                "medium",
                List.of("Read", "Bash", "Write"));
    }

    @Test
    void theProbeFindsTheRuntimeAndItsVersion() {
        assertThat(probe.availability().available()).isTrue();
        assertThat(probe.availability().version()).isEqualTo("2.1.304");
    }

    @Test
    void theSessionSettingsNeverLetASkillRunAShellCommand() throws Exception {
        Path dir = Files.createTempDirectory("settings");
        var settings = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(Files.readString(runtime.writeSettings(dir)));
        assertThat(settings.path("disableSkillShellExecution").asBoolean(false)).isTrue();
        assertThat(settings.path("hooks").path("PreToolUse")).isNotEmpty();
    }

    @Test
    void aGovernorThatThrowsWhileRecordingStillEndsTheSessionAsFailed() throws Exception {
        Setup s = realMissionInProgress();
        MissionGovernor real = new MissionGovernor(office, spec(s, "Inspect the repository"));
        // Every record but the session's end fails: the end must still be reported, not lost with the exception.
        AgentRuntime.Governor flaky = (AgentRuntime.Governor) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {AgentRuntime.Governor.class}, (proxy, method, args) -> {
                    if (method.getName().equals("observed") && !(args[0] instanceof RuntimeEvent.SessionEnded)) {
                        throw new IllegalStateException("the journal is down");
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });

        SessionReport report = runtime.run(spec(s, "Inspect the repository"), flaky);

        assertThat(report.failed()).isTrue();
        assertThat(String.join(" ", report.notes()))
                .contains("Floor42 could not record the session")
                .contains("the journal is down");
    }

    @Test
    void aLiveSessionIsGovernedThroughTheHookAndRecorded() throws Exception {
        Setup s = realMissionInProgress();
        MissionGovernor governor = new MissionGovernor(office, spec(s, "Inspect the repository"));

        CompletableFuture<SessionReport> run =
                CompletableFuture.supplyAsync(() -> runtime.run(spec(s, "Inspect the repository"), governor));

        // Bash is set to "ask once": the hook waits for the director, exactly like the agent does.
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (office.query(
                c -> c.desk().open().stream().noneMatch(q -> q.mission().equals(s.mission())))) {
            assertThat(Instant.now()).as("approval request").isBefore(deadline);
            Thread.sleep(50);
        }
        var question = office.query(c -> c.desk().open().stream()
                .filter(q -> q.mission().equals(s.mission()))
                .findFirst()
                .orElseThrow()
                .id());
        office.execute(c -> c.desk().answer(director(), question, "ALLOW"));

        SessionReport report = run.get(30, TimeUnit.SECONDS);

        assertThat(report.failed()).isFalse();
        assertThat(report.origin()).isEqualTo(Origin.REAL);
        assertThat(report.allowed()).isEqualTo(2);
        assertThat(report.denied()).isEqualTo(2);
        List<Action> actions = office.query(c -> c.actions().of(s.mission()));
        assertThat(actions).extracting(Action::tool).containsExactly("Bash", "Read", "Write", "Read");
        assertThat(actions.get(0).stage()).isEqualTo(ActionStage.EXECUTED);
        assertThat(actions.get(0).result()).isEqualTo("On branch main");
        assertThat(actions.get(2).stage()).isEqualTo(ActionStage.DENIED);
        assertThat(actions.get(2).denialReason()).isEqualTo(DenialReason.POLICY);
        // Reading is allowed, but not outside the worktree: the code says no before the table is looked at.
        assertThat(actions.get(3).stage()).isEqualTo(ActionStage.DENIED);
        assertThat(actions.get(3).denialReason()).isEqualTo(DenialReason.OUTSIDE_WORKSPACE);
        assertThat(actions.get(3).result()).isNull();
        QuestionStatus answered =
                office.query(c -> c.desk().of(s.mission()).getFirst().status());
        assertThat(answered).isEqualTo(QuestionStatus.ANSWERED);

        AgentSession session = office.query(c -> c.sessions().of(s.mission()).getFirst());
        assertThat(session.isOpen()).isFalse();
        assertThat(session.failed()).isFalse();
        assertThat(session.inputTokensProvided()).isEqualTo(3_100L);
        assertThat(session.costUsdEstimated()).isEqualTo(0.0123);
        assertThat(report.finalText()).startsWith("Report:");
    }

    @Inject
    Sandbox sandbox;

    /** The sandbox bean is built here, in the test thread: the test profile's data folder cannot be expanded in a pool thread. */
    @org.junit.jupiter.api.BeforeEach
    void buildTheSandboxBean() {
        sandbox.enabled();
    }

    /** Runs the scripted session, answering "allow" to the shell question when one is asked. */
    List<Action> runAllowingTheShell(Setup s) throws Exception {
        CompletableFuture<SessionReport> run = CompletableFuture.supplyAsync(
                () -> runtime.run(spec(s, "Inspect the repository"), new MissionGovernor(office, spec(s, "Inspect"))));
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (!run.isDone()) {
            var open = office.query(c -> c.desk().open().stream()
                    .filter(q -> q.mission().equals(s.mission()))
                    .findFirst());
            if (open.isPresent())
                office.execute(c -> c.desk().answer(director(), open.get().id(), "ALLOW"));
            assertThat(Instant.now()).as("session end").isBefore(deadline);
            Thread.sleep(50);
        }
        run.get(30, TimeUnit.SECONDS);
        return office.query(c -> c.actions().of(s.mission()));
    }

    @Test
    void withTheSandboxOnTheShellCommandRunsRewrittenInIt() throws Exception {
        office.execute(c -> c.preferences().changeSetting(director(), dev.floor42.domain.Setting.SHELL_SANDBOX, "wsl"));
        try {
            Setup s = realMissionInProgress();
            List<Action> actions = runAllowingTheShell(s);
            Action bash = actions.getFirst();
            assertThat(bash.tool()).isEqualTo("Bash");
            assertThat(bash.stage()).isEqualTo(ActionStage.EXECUTED);
            // What ran is the agent's command, inside the sandbox at its worktree, network cut.
            String encoded = java.util.Base64.getEncoder().encodeToString("git status".getBytes());
            assertThat(bash.result())
                    .startsWith("ran: " + SandboxHost.shellPrefix(SandboxHost.current(), "Debian") + "sh '/")
                    .contains(Sandbox.linux(Path.of(s.workdir())))
                    .contains("'" + encoded + "' 'off' 'none' 'none' ");

            // Required but not working: the real session does not start (ADR-0063), nothing runs, and it says why.
            sandbox.setAvailable(false);
            Setup broken = realMissionInProgress();
            SessionReport refused = runtime.run(
                    spec(broken, "Inspect the repository"), new MissionGovernor(office, spec(broken, "Inspect")));
            assertThat(refused.failed()).isTrue();
            assertThat(refused.notes()).anyMatch(n -> n.startsWith("SANDBOX_REQUIRED"));
            List<Action> none = office.query(c -> c.actions().of(broken.mission()));
            assertThat(none).isEmpty();
        } finally {
            sandbox.setAvailable(null);
            office.execute(
                    c -> c.preferences().changeSetting(director(), dev.floor42.domain.Setting.SHELL_SANDBOX, null));
        }
    }

    @Test
    void aSessionRunsUnderTheLowerOfTheSessionCapAndWhatIsLeftOfTheBudget() throws Exception {
        Setup s = realMissionInProgress();
        office.execute(
                c -> c.preferences().changeSetting(director(), dev.floor42.domain.Setting.SESSION_BUDGET_USD, "3"));
        office.execute(c -> c.verification().setBudget(director(), s.mission(), 0.4));
        MissionGovernor governor = new MissionGovernor(office, spec(s, "[fake:cap]"));

        SessionReport report = runtime.run(spec(s, "[fake:cap]"), governor);

        assertThat(report.failed()).isTrue();
        assertThat(report.notes()).anyMatch(n -> n.contains("cost cap of 0.40 USD") && n.contains("0.4066"));
        String reason =
                office.query(c -> c.sessions().of(s.mission()).getFirst().outcome());
        assertThat(reason).isEqualTo("stopped at its cost cap");
    }

    @Test
    void anAgentAsksTheDirectorThroughOfficesMcpToolAndGetsTheAnswer() throws Exception {
        Setup s = realMissionInProgress();
        String brief = "[fake:ask=Which colour for the banner?]";
        List<String> said = listenToRemarks();
        MissionGovernor governor = new MissionGovernor(office, spec(s, brief));

        CompletableFuture<SessionReport> run =
                CompletableFuture.supplyAsync(() -> runtime.run(spec(s, brief), governor));
        var asked = waitForOpenQuestion(s.mission());
        assertThat(asked.subject()).isEqualTo("Which colour for the banner?");
        assertThat(asked.askedBy()).isEqualTo(CHRIS);
        assertThat(asked.blocking()).isTrue();
        assertThat(asked.options()).containsExactly("blue", "red");
        dev.floor42.domain.AgentStatus status = office.query(c -> c.queries().statusOf(CHRIS));
        assertThat(status).isEqualTo(dev.floor42.domain.AgentStatus.WAITING_FOR_DIRECTOR);
        office.execute(c -> c.desk().answer(director(), asked.id(), "blue"));
        SessionReport report = run.get(60, TimeUnit.SECONDS);

        assertThat(report.failed()).isFalse();
        assertThat(said).anyMatch(r -> r.contains("The director answered: blue"));
        // The call went through the hook first, as an Floor42 tool.
        assertThat(office.query(c -> c.actions().of(s.mission())).stream()
                        .filter(x -> x.tool().equals("mcp__floor42__ask_director"))
                        .map(Action::summary))
                .containsExactly("Which colour for the banner?");
    }

    @Tag("slow")
    @Test
    void aQuestionLeftUnansweredIsWithdrawnAndTheAgentGoesOn() throws Exception {
        Setup s = realMissionInProgress();
        String brief = "[fake:ask=May I rename the module?]";
        List<String> said = listenToRemarks();
        MissionGovernor governor = new MissionGovernor(office, spec(s, brief));

        SessionReport report = CompletableFuture.supplyAsync(() -> runtime.run(spec(s, brief), governor))
                .get(60, TimeUnit.SECONDS);

        assertThat(report.failed()).isFalse();
        assertThat(office.query(c -> c.desk().of(s.mission())).getFirst().status())
                .isEqualTo(QuestionStatus.WITHDRAWN);
        assertThat(said).anyMatch(r -> r.contains("No answer from the director in time"));
    }

    @Test
    void officesMcpServerRefusesACallerWithoutASessionToken() {
        given().contentType("application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                .post("/mcp")
                .then()
                .statusCode(401);
    }

    dev.floor42.domain.Question waitForOpenQuestion(MissionId m) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            var open = office.query(c -> c.desk().of(m).stream()
                    .filter(q -> q.status() == QuestionStatus.OPEN)
                    .findFirst());
            if (open.isPresent()) return open.get();
            Thread.sleep(50);
        }
        throw new AssertionError("no question asked");
    }

    /** What agents say from now on, as journaled. */
    List<String> listenToRemarks() {
        List<String> said = new java.util.concurrent.CopyOnWriteArrayList<>();
        office.subscribe(events -> events.stream()
                .map(e -> e.event())
                .filter(dev.floor42.domain.event.SessionEvent.AgentRemarked.class::isInstance)
                .forEach(e -> said.add(((dev.floor42.domain.event.SessionEvent.AgentRemarked) e).text())));
        return said;
    }

    @Test
    void aCrashedSessionIsRecordedAsFailedWithItsReason() throws Exception {
        Setup s = realMissionInProgress();
        MissionGovernor governor = new MissionGovernor(office, spec(s, "[fake:crash]"));

        SessionReport report = runtime.run(spec(s, "[fake:crash]"), governor);

        assertThat(report.failed()).isTrue();
        assertThat(report.notes()).anyMatch(n -> n.contains("exited with code 3") && n.contains("fake crash"));
        Boolean failed =
                office.query(c -> c.sessions().of(s.mission()).getFirst().failed());
        assertThat(failed).isTrue();
    }

    /** A session killed before its result reports no cost: the budget counts the cap it ran under, as a bound. */
    @Test
    void aSessionThatEndsWithoutACostStillCountsInTheMissionBudget() throws Exception {
        Setup s = realMissionInProgress();
        office.execute(c -> c.verification().setBudget(director(), s.mission(), 0.50));
        MissionGovernor governor = new MissionGovernor(office, spec(s, "[fake:crash]"));

        runtime.run(spec(s, "[fake:crash]"), governor);

        AgentSession ended = office.query(c -> c.sessions().of(s.mission()).getFirst());
        assertThat(ended.costUsdEstimated())
                .as("no cost was reported: none is invented")
                .isNull();
        assertThat(ended.costUsdBound()).isEqualTo(0.50);
        assertThat(ended.capUsd()).as("journaled with its start").isEqualTo(0.50);
        assertBounded(s.mission(), 0.50);
        boolean reached = office.query(c -> c.verification().budgetReached(s.mission()));
        assertThat(reached).as("the next session of the mission is held").isTrue();
    }

    /** The bound is counted, apart from the estimates: none of it is presented as an estimate. */
    void assertBounded(MissionId m, double bound) {
        double estimated = office.query(c -> c.verification().spentUsdEstimated(m));
        double bounds = office.query(c -> c.verification().spentUsdBound(m));
        double counted = office.query(c -> c.verification().spentUsdCounted(m));
        assertThat(estimated).isZero();
        assertThat(bounds).isEqualTo(bound);
        assertThat(counted).isEqualTo(bound);
    }

    @Inject
    RunningSessions running;

    /** Killed while it runs (as the director's meeting does): no result, no cost reported, the cap counted. */
    @Test
    void aKilledSessionCountsForTheCapItRanUnder() throws Exception {
        Setup s = realMissionInProgress();
        office.execute(c -> c.verification().setBudget(director(), s.mission(), 0.30));
        MissionGovernor governor = new MissionGovernor(office, spec(s, "[fake:hang]"));
        CompletableFuture<SessionReport> run =
                CompletableFuture.supplyAsync(() -> runtime.run(spec(s, "[fake:hang]"), governor));
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (office.query(c -> c.sessions().of(s.mission()).isEmpty())) {
            assertThat(Instant.now()).as("the session starts").isBefore(deadline);
            Thread.sleep(50);
        }

        assertThat(running.stop(CHRIS, "killed by the test")).isTrue();
        SessionReport report = run.get(30, TimeUnit.SECONDS);

        assertThat(report.notes()).contains("killed by the test");
        AgentSession killed = office.query(c -> c.sessions().of(s.mission()).getFirst());
        assertThat(killed.failed()).isTrue();
        assertThat(killed.costUsdEstimated()).isNull();
        assertThat(killed.costUsdBound()).isEqualTo(0.30);
        assertBounded(s.mission(), 0.30);
    }

    /**
     * The process died before Claude Code named its session: no session-started line, yet a process ran and may have
     * spent up to its cap. The launch is recorded as a session of its own, ended with the cap as its bound.
     */
    @Test
    void aProcessThatDiesBeforeItsSessionStartsStillCountsForItsCap() throws Exception {
        Setup s = realMissionInProgress();
        office.execute(c -> c.verification().setBudget(director(), s.mission(), 0.20));
        MissionGovernor governor = new MissionGovernor(office, spec(s, "[fake:crash-before-init]"));

        SessionReport report = runtime.run(spec(s, "[fake:crash-before-init]"), governor);

        assertThat(report.failed()).isTrue();
        assertThat(report.sessionId()).as("Claude Code never named a session").isNull();
        AgentSession launch = office.query(c -> c.sessions().of(s.mission()).getFirst());
        assertThat(launch.id())
                .as("named after its real session folder, the id a restart would give it")
                .isEqualTo(s.mission().value() + ":launch-" + folderOf(s.mission()));
        assertThat(launch.isOpen()).isFalse();
        assertThat(launch.failed()).isTrue();
        assertThat(launch.costUsdBound()).isEqualTo(0.20);
        assertBounded(s.mission(), 0.20);
    }

    /**
     * A result line before any init line, with no cost in it: no session was named, the process ran. The end keeps the
     * cap and the folder's name, so the launch is recorded under the id a restart would give it, for its cap.
     */
    @Test
    void aResultBeforeAnyInitStillCountsTheLaunchForItsCap() throws Exception {
        Setup s = realMissionInProgress();
        office.execute(c -> c.verification().setBudget(director(), s.mission(), 0.15));
        MissionGovernor governor = new MissionGovernor(office, spec(s, "[fake:result-before-init]"));

        SessionReport report = runtime.run(spec(s, "[fake:result-before-init]"), governor);

        assertThat(report.sessionId()).as("Claude Code never named a session").isNull();
        AgentSession launch = office.query(c -> c.sessions().of(s.mission()).getFirst());
        assertThat(launch.id()).isEqualTo(s.mission().value() + ":launch-" + folderOf(s.mission()));
        assertThat(launch.costUsdBound()).isEqualTo(0.15);
        assertBounded(s.mission(), 0.15);
    }

    /** The one session folder whose launch record is for this mission. */
    private String folderOf(MissionId mission) throws java.io.IOException {
        try (var dirs = Files.list(dataDir.resolve("sessions"))) {
            List<String> found = dirs.filter(d -> {
                        try {
                            Path launch = d.resolve(ClaudeCodeRuntime.LAUNCH_FILE);
                            return Files.isRegularFile(launch)
                                    && Files.readString(launch).contains("\"mission\":\"" + mission.value() + "\"");
                        } catch (java.io.IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .map(d -> d.getFileName().toString())
                    .toList();
            assertThat(found).hasSize(1);
            return found.getFirst();
        }
    }

    @Tag("slow")
    @Test
    void aSessionWithNoSignOfLifeIsStoppedWithTheProcessesItStarted() throws Exception {
        Setup s = realMissionInProgress();
        office.execute(c -> c.verification().setBudget(director(), s.mission(), 0.40));
        MissionGovernor governor = new MissionGovernor(office, spec(s, "[fake:hang]"));

        SessionReport report = CompletableFuture.supplyAsync(() -> runtime.run(spec(s, "[fake:hang]"), governor))
                .get(60, TimeUnit.SECONDS);

        assertThat(report.failed()).isTrue();
        assertThat(report.notes()).anyMatch(n -> n.contains("no sign of life for 6 s"));
        Boolean failed =
                office.query(c -> c.sessions().of(s.mission()).getFirst().failed());
        assertThat(failed).isTrue();
        // Stalled: stopped by Floor42 without a result, so the cap it ran under is counted.
        assertBounded(s.mission(), 0.40);
        // The fake started a child before going silent: it is stopped with the session, not left running.
        long child = Long.parseLong(report.finalText().replace("child", "").strip());
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)) {
            assertThat(Instant.now()).as("the child process stops").isBefore(deadline);
            Thread.sleep(100);
        }
    }

    @Test
    void aSessionProcessLeftByAPreviousDaemonIsStopped() throws Exception {
        Process orphan = new ProcessBuilder(sleeper()).start();
        Process stranger = new ProcessBuilder(sleeper()).start();
        try {
            Path left = dataDir.resolve("sessions").resolve("left-" + java.util.UUID.randomUUID());
            Files.createDirectories(left);
            Files.writeString(
                    left.resolve(ClaudeCodeRuntime.PROCESS_FILE),
                    orphan.pid() + " " + orphan.info().startInstant().orElseThrow());
            // A record whose pid now names another process (another start time) must not stop that process.
            Path reused = dataDir.resolve("sessions").resolve("reused-" + java.util.UUID.randomUUID());
            Files.createDirectories(reused);
            Files.writeString(reused.resolve(ClaudeCodeRuntime.PROCESS_FILE), stranger.pid() + " 2000-01-01T00:00:00Z");

            assertThat(runtime.stopLeftovers()).isEqualTo(1);

            assertThat(orphan.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(stranger.isAlive()).isTrue();
            assertThat(left.resolve(ClaudeCodeRuntime.PROCESS_FILE)).doesNotExist();
            assertThat(reused.resolve(ClaudeCodeRuntime.PROCESS_FILE)).doesNotExist();
        } finally {
            orphan.destroyForcibly();
            stranger.destroyForcibly();
        }
    }

    @Inject
    dev.floor42.server.security.SessionToken directorToken;

    @Inject
    HookRegistry hooks;

    /** "steal: candidates=N usable=K own=S open=O openBody=L", as the fake reports it (L: 1 if a link came back). */
    static java.util.Map<String, String> stealReport(SessionReport report) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (String part : report.finalText().replace("steal:", "").strip().split("\\s+")) {
            String[] kv = part.split("=", 2);
            out.put(kv[0], kv[1]);
        }
        return out;
    }

    @Test
    void anAgentsShellFindsNoUsableDirectorCredential() throws Exception {
        // Control: the hunt does find the director's token when it lies where an agent can read it.
        Setup planted = realMissionInProgress();
        Files.writeString(Path.of(planted.workdir(), "planted.txt"), "token " + directorToken.value() + "\n");
        var control = stealReport(
                runtime.run(spec(planted, "[fake:steal]"), new MissionGovernor(office, spec(planted, "[fake:steal]"))));
        assertThat(Integer.parseInt(control.get("usable"))).isPositive();

        // ADR-0044: a live script token's SHA-256 is in the journal, for the hunt to find; it opens nothing.
        var issued = given().header("Authorization", "Bearer " + directorToken.value())
                .contentType("application/json")
                .body("{\"scope\":\"all\",\"days\":1,\"label\":\"steal test\"}")
                .post("/api/auth/tokens");
        issued.then().statusCode(201);
        String scriptHash = dev.floor42.server.security.SessionRegistry.sha256(
                issued.jsonPath().getString("token"));
        StringBuilder atRest = new StringBuilder();
        for (String name : List.of("floor42.db", "floor42.db-wal")) {
            Path f = dataDir.resolve(name);
            if (Files.exists(f))
                atRest.append(new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.ISO_8859_1));
        }
        assertThat(atRest.toString())
                .as("the hash is at rest, where the hunt reads")
                .contains(scriptHash);

        // ADR-0034: with nothing planted, nothing in the data directory, the worktree or the environment opens a
        // director route; the session's own credential is refused there (403); a sign-in link never comes back.
        Setup s = realMissionInProgress();
        var report =
                stealReport(runtime.run(spec(s, "[fake:steal]"), new MissionGovernor(office, spec(s, "[fake:steal]"))));
        assertThat(Integer.parseInt(report.get("candidates"))).isPositive();
        assertThat(report.get("usable")).isEqualTo("0");
        assertThat(report.get("own")).isEqualTo("403");
        assertThat(report.get("openBody")).isEqualTo("0");
    }

    /**
     * ADR-0044, what is proven about the same-user layout (the container's: agents share the daemon's uid, the sandbox
     * is off; and Windows without the WSL sandbox): permission checks only, on this test's own journal and a planted
     * credentials file. The journal stays writable by an agent's shell and Claude Code's credentials readable (Claude
     * Code itself needs them); on Linux the daemon's standard output is closed to it, the daemon being non-dumpable.
     */
    @Test
    void anAgentsShellRunningAsTheDaemonsUserCanWriteTheJournalAndReadCredentials() throws Exception {
        Path home = Files.createTempDirectory("planted-claude-home");
        Path credentials = home.resolve(".credentials.json");
        Files.writeString(credentials, "{\"planted\":\"for the test only\"}");
        Setup s = realMissionInProgress();
        String brief = "[fake:forge=" + credentials.toString().replace('\\', '/') + "]";
        SessionReport report = runtime.run(spec(s, brief), new MissionGovernor(office, spec(s, brief)));
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (String part : report.finalText().replace("forge:", "").strip().split("\\s+")) {
            String[] kv = part.split("=", 2);
            out.put(kv[0], kv[1]);
        }
        assertThat(out.get("journalWritable"))
                .as("same OS user: the journal is the agent's to write")
                .isEqualTo("1");
        assertThat(out.get("credentialsReadable"))
                .as("same OS user: credentials readable")
                .isEqualTo("1");
        if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("linux")) {
            assertThat(dev.floor42.server.security.ProcessHardening.dumpable()).contains(0);
            assertThat(out.get("daemonStdout"))
                    .as("non-dumpable daemon: /proc/<pid>/fd closed")
                    .isEqualTo("0");
        } else {
            assertThat(out.get("daemonStdout")).isEqualTo("na");
        }
    }

    @Test
    void aSessionCredentialStopsWorkingWhenItsSessionEnds() throws Exception {
        Setup s = realMissionInProgress();
        String brief = "[fake:leak] [fake:steal]";
        var report = stealReport(runtime.run(spec(s, brief), new MissionGovernor(office, spec(s, brief))));
        assertThat(report.get("own"))
                .as("refused on a director route while the session lives")
                .isEqualTo("403");

        String leaked =
                Files.readString(Path.of(s.workdir(), "leaked-credential.txt")).strip();
        assertThat(leaked).isNotBlank();
        given().contentType("application/json")
                .header("Authorization", "Bearer " + leaked)
                .body("{\"tool_name\":\"Bash\",\"tool_use_id\":\"x\",\"tool_input\":{\"command\":\"git status\"}}")
                .post("/hooks/pre-tool-use")
                .then()
                .statusCode(401);
        given().contentType("application/json")
                .header("Authorization", "Bearer " + leaked)
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                .post("/mcp")
                .then()
                .statusCode(401);
        given().header("Authorization", "Bearer " + leaked)
                .get("/api/missions")
                .then()
                .statusCode(401);
    }

    @Test
    void aLiveSessionCredentialIsRefusedOnEveryDirectorRoute() throws Exception {
        Setup s = realMissionInProgress();
        String credential = hooks.register(new MissionGovernor(office, spec(s, "")));
        try {
            for (String path : List.of("/api/missions", "/api/agents", "/api/permissions/defaults")) {
                given().header("Authorization", "Bearer " + credential)
                        .get(path)
                        .then()
                        .statusCode(403);
            }
            given().header("Authorization", "Bearer " + credential)
                    .contentType("application/json")
                    .body("{}")
                    .post("/api/missions/" + s.mission().value() + "/resume")
                    .then()
                    .statusCode(403);
            // Nor through the browser's door.
            given().cookie(dev.floor42.server.security.LocalAccessGuard.COOKIE, credential)
                    .get("/api/missions")
                    .then()
                    .statusCode(401);
            given().redirects()
                    .follow(false)
                    .header("Authorization", "Bearer " + credential)
                    .post("/auth")
                    .then()
                    .statusCode(401);
        } finally {
            hooks.unregister(credential);
        }
    }

    @Test
    void aHookCallWithoutALiveSessionTokenIsRefused() {
        given().contentType("application/json")
                .header("Authorization", "Bearer not-a-session")
                .body("{\"tool_name\":\"Bash\",\"tool_use_id\":\"x\",\"tool_input\":{}}")
                .post("/hooks/pre-tool-use")
                .then()
                .statusCode(401);
    }

    io.restassured.response.ValidatableResponse askBack(String question, String text) {
        return given().header("Authorization", "Bearer " + token.value())
                .contentType("application/json")
                .body("{\"text\":\"" + text + "\"}")
                .post("/api/questions/" + question + "/ask-back")
                .then();
    }

    /** ADR-0059: the director asks back, the same session asks again (its cause is the withdrawal), then is answered. */
    @Test
    void theDirectorAsksBackAndTheSameSessionAsksAgainBeforeBeingAnswered() throws Exception {
        Setup s = realMissionInProgress();
        String brief = "[fake:askback=Which colour for the banner?||Blue or red for the banner then?]";
        MissionGovernor governor = new MissionGovernor(office, spec(s, brief));
        int types = office.query(c -> c.desk().of(s.mission()).size());
        CompletableFuture<SessionReport> run =
                CompletableFuture.supplyAsync(() -> runtime.run(spec(s, brief), governor));
        var first = waitForOpenQuestion(s.mission());

        askBack(first.id().value(), "Which brand guide do you follow?")
                .statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo("WITHDRAWN"))
                .body("askBack.text", org.hamcrest.Matchers.equalTo("Which brand guide do you follow?"));
        var second = waitForOpenQuestion(s.mission());

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.subject()).isEqualTo("Blue or red for the banner then?");
        int rounds = office.query(c -> c.desk().askBack(second).rounds());
        assertThat(rounds).isEqualTo(1);
        given().header("Authorization", "Bearer " + token.value())
                .get("/api/questions")
                .then()
                .statusCode(200)
                .body(
                        "find { it.id == '" + second.id().value() + "' }.askBack.rounds",
                        org.hamcrest.Matchers.equalTo(1));
        office.execute(c -> c.desk().answer(director(), second.id(), "blue"));
        SessionReport report = run.get(60, TimeUnit.SECONDS);
        given().header("Authorization", "Bearer " + token.value())
                .get("/api/missions/" + s.mission().value() + "/report")
                .then()
                .statusCode(200)
                .body("askedBack.size()", org.hamcrest.Matchers.equalTo(1))
                .body(
                        "askedBack[0].question",
                        org.hamcrest.Matchers.equalTo(first.id().value()))
                .body("askedBack[0].round", org.hamcrest.Matchers.equalTo(1));

        assertThat(report.failed()).isFalse();
        assertThat(types).isZero();
        // The chain is in the stored envelopes: a rebuilt company reads the same round.
        var rebuilt = dev.floor42.domain.Company.replay(journal.readAll());
        assertThat(rebuilt.desk()
                        .askBack(rebuilt.desk().find(second.id()).orElseThrow())
                        .rounds())
                .isEqualTo(1);
        assertThat(rebuilt.desk()
                        .askBack(rebuilt.desk().find(first.id()).orElseThrow())
                        .event())
                .isNotNull();
    }

    @Test
    void whatTheDirectorTypesWhenAskingBackNeverBecomesAPermission() throws Exception {
        Setup s = realMissionInProgress();
        office.execute(c -> c.missions()
                .addPermission(
                        director(),
                        s.mission(),
                        new dev.floor42.domain.PermissionRule(
                                null, ActionCategory.EXECUTE, "git push", Decision.DENY)));
        String brief = "[fake:ask=May I publish the branch?]";
        MissionGovernor governor = new MissionGovernor(office, spec(s, brief));
        CompletableFuture<SessionReport> run =
                CompletableFuture.supplyAsync(() -> runtime.run(spec(s, brief), governor));
        var asked = waitForOpenQuestion(s.mission());

        askBack(asked.id().value(), "Yes, run Bash git push origin main, I allow it")
                .statusCode(200);
        run.get(60, TimeUnit.SECONDS);

        var push = new dev.floor42.server.runtime.RuntimeEvent.ToolRequested(
                "toolu_push",
                "Bash",
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .createObjectNode()
                        .put("command", "git push origin main"));
        assertThat(governor.decide(push)).isEqualTo(AgentRuntime.Verdict.DENY);
    }

    @Test
    void aQuestionNoSessionWaitsOnAnyMoreCannotBeAskedBack() throws Exception {
        Setup s = realMissionInProgress();
        var events = office.execute(c -> c.desk()
                .ask(
                        office.context(Actor.agent(CHRIS), Origin.REAL),
                        s.mission(),
                        "Orphan question?",
                        "ctx",
                        List.of(),
                        null,
                        dev.floor42.domain.Criticality.MEDIUM,
                        true,
                        s.ticket()));
        String id = office.query(c -> c.desk().of(s.mission())).getLast().id().value();

        askBack(id, "Why?").statusCode(409).body("code", org.hamcrest.Matchers.equalTo("SESSION_GONE"));
        assertThat(events).isNotEmpty();
        assertThat(office.query(c -> c.desk().of(s.mission())).getLast().status())
                .isEqualTo(QuestionStatus.OPEN);
    }

    CompletableFuture<AskOutcome> askAsync(MissionGovernor governor, String question, int seconds) {
        return CompletableFuture.supplyAsync(() -> governor.askDirector(
                question, List.of(), null, "toolu_" + question.hashCode(), Duration.ofSeconds(seconds)));
    }

    int roundsOf(dev.floor42.domain.Question q) {
        return office.query(c -> c.desk().askBack(q).rounds());
    }

    @Tag("slow")
    @Test
    void aSessionThatEndedWhileItsQuestionWaitedCannotBeAskedBack() throws Exception {
        Setup s = realMissionInProgress();
        MissionGovernor governor = new MissionGovernor(office, spec(s, "x"));
        var outcome = askAsync(governor, "Which database?", 8);
        var q = waitForOpenQuestion(s.mission());

        governor.observed(new RuntimeEvent.SessionEnded(true, 0, 0L, 0L, 0L, 0.0, "killed"));

        askBack(q.id().value(), "Why?").statusCode(409).body("code", org.hamcrest.Matchers.equalTo("SESSION_GONE"));
        QuestionStatus after =
                office.query(c -> c.desk().find(q.id()).orElseThrow().status());
        assertThat(after).isEqualTo(QuestionStatus.OPEN);
        assertThat(outcome.get(30, TimeUnit.SECONDS)).isInstanceOf(AskOutcome.NoAnswer.class);
    }

    @Test
    void anAskBackLinksOnlyTheVeryNextQuestionAndNothingElse() throws Exception {
        Setup s = realMissionInProgress();
        MissionGovernor governor = new MissionGovernor(office, spec(s, "x"));
        // Asked back, then another tool call: the next question starts a thread of its own.
        var first = askAsync(governor, "First?", 20);
        askBack(waitForOpenQuestion(s.mission()).id().value(), "Say more").statusCode(200);
        assertThat(first.get(20, TimeUnit.SECONDS)).isInstanceOf(AskOutcome.AskedBack.class);
        governor.decide(new RuntimeEvent.ToolRequested(
                "toolu_read",
                "Read",
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .createObjectNode()
                        .put("file_path", "x.txt")));
        var second = askAsync(governor, "Second?", 20);
        var q2 = waitForOpenQuestion(s.mission());
        assertThat(roundsOf(q2)).as("a tool call in between cut the link").isZero();
        // Asked back and asked again at once: one round further, and the link is spent after that.
        askBack(q2.id().value(), "And why?").statusCode(200);
        assertThat(second.get(20, TimeUnit.SECONDS)).isInstanceOf(AskOutcome.AskedBack.class);
        var third = askAsync(governor, "Third?", 20);
        var q3 = waitForOpenQuestion(s.mission());
        assertThat(roundsOf(q3)).isEqualTo(1);
        office.execute(c -> c.desk().answer(director(), q3.id(), "ok"));
        assertThat(third.get(20, TimeUnit.SECONDS)).isEqualTo(new AskOutcome.Answer("ok"));
        var fourth = askAsync(governor, "Fourth?", 20);
        var q4 = waitForOpenQuestion(s.mission());
        assertThat(roundsOf(q4)).as("the link was consumed by the third").isZero();
        office.execute(c -> c.desk().answer(director(), q4.id(), "ok"));
        fourth.get(20, TimeUnit.SECONDS);
    }
}
