package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.OptionCode;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.Setting;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.app.DirectorAnswers;
import dev.floor42.server.app.JournalReplay;
import dev.floor42.server.app.LiveMissions;
import dev.floor42.server.app.LiveSeams;
import dev.floor42.server.app.MissingTools;
import dev.floor42.server.app.MissionScheduler;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.SchedulerQuiet;
import dev.floor42.server.app.StallDiagnosis;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.runtime.SandboxProbe;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Floor42 says up front what the sandbox lacks for a plan: the planner states the tools each ticket NEEDS, and when the
 * sandbox's measure finds one absent, the director gets ONE blocking question while the plan awaits approval. Nothing
 * is installed, no permission widens. No test starts wsl.exe: the probe's process seam gives recorded-format outputs.
 */
@QuarkusTest
class MissingToolsApiTest extends ApiTestBase {

    /** The probe's line format (SandboxProbe.script), os lines as recorded on Debian 13 (SandboxProbeTest). */
    static final String HEAD = """
            os|PRETTY_NAME="Debian GNU/Linux 13 (trixie)"
            os|VERSION_ID="13"
            os|ID=debian
            git|/usr/bin/git|git version 2.47.3
            python3|/usr/bin/python3|Python 3.13.5
            """;

    static final String WITHOUT = HEAD + """
            chromium||
            chromium-browser||
            google-chrome||
            google-chrome-stable||
            pdftotext||
            floor42-probe-end
            """;

    static final String WITH = HEAD + """
            chromium|/usr/bin/chromium|Chromium 141.0.7390.65 built on Debian GNU/Linux 13 (trixie)
            chromium-browser||
            google-chrome||
            google-chrome-stable||
            pdftotext|/usr/bin/pdftotext|pdftotext version 25.03.0
            floor42-probe-end
            """;

    @Inject
    OfficeService office;

    @Inject
    dev.floor42.server.runtime.Sandbox sandbox;

    @Inject
    SandboxProbe probe;

    @Inject
    MissingTools missingTools;

    @Inject
    MissionScheduler scheduler;

    @Inject
    LiveMissions live;

    @Inject
    DirectorAnswers answers;

    @Inject
    StallDiagnosis stalls;

    @Inject
    Journal journal;

    final List<List<String>> runs = new CopyOnWriteArrayList<>();
    final AtomicReference<String> output = new AtomicReference<>(WITHOUT);

    @BeforeEach
    void sandboxOn() {
        probe.useForTests(
                (argv, limit) -> {
                    runs.add(argv);
                    // A measure held open by the test: the probe is in progress, outside every lock.
                    CountDownLatch g = gate.get();
                    if (g != null) {
                        measuring.countDown();
                        try {
                            g.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return new SandboxProbe.RunResult(output.get(), 0, false);
                },
                () -> "Windows 11");
        setting("wsl");
        dev.floor42.server.runtime.SandboxSeams.assume(sandbox, true);
    }

    final AtomicReference<CountDownLatch> gate = new AtomicReference<>();
    volatile CountDownLatch measuring = new CountDownLatch(1);

    @AfterEach
    void sandboxOff() {
        CountDownLatch g = gate.getAndSet(null);
        if (g != null) g.countDown();
        LiveSeams.beforeMeasureRecorded(missingTools, null);
        setting(null);
        dev.floor42.server.runtime.SandboxSeams.assume(sandbox, null);
        probe.useForTests(null, null);
    }

    void setting(String value) {
        office.execute(c -> c.preferences()
                .changeSetting(office.context(Actor.director(), Origin.REAL), Setting.SHELL_SANDBOX, value));
    }

    MissionId planned() throws Exception {
        return planned("");
    }

    MissionId planned(String markers) throws Exception {
        MissionId m = start(markers);
        await(
                "the plan",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.tickets().of(m).size() == 3));
        return m;
    }

    MissionId start(String markers) throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        MissionId m = new MissionId(api().body("{\"title\":\"Summary on paper\",\"objective\":\"Describe this"
                        + " repository as a PDF [fake:needs-tools]" + markers + "\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\")
                        + "\",\"plan\":true}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id"));
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + m.value() + "/approve")
                .then()
                .statusCode(200);
        return m;
    }

    /** No session starts on the mission's planned tickets for a while, whoever nudges the dispatcher. */
    void nothingStarts(MissionId m, String why) throws Exception {
        for (int i = 0; i < 30; i++) {
            live.nudge(m);
            Thread.sleep(100);
            List<?> working = office.query(c -> c.sessions().of(m).stream()
                    .filter(s -> !s.ticket().equals(c.tickets().of(m).getFirst().id()))
                    .filter(s -> !c.staff().isStaffingTicket(s.ticket()))
                    .toList());
            assertThat(working).as(why).isEmpty();
        }
    }

    void approveAll(MissionId m) {
        List<String> ids = office.query(c -> c.tickets().of(m).stream()
                .filter(t -> t.status() == TicketStatus.PROPOSED)
                .map(t -> "\"" + t.id().value() + "\"")
                .toList());
        api().body("{\"tickets\":[" + String.join(",", ids) + "]}")
                .post("/api/missions/" + m.value() + "/tickets/approve")
                .then()
                .statusCode(200);
    }

    /** MAJOR 1: the plan is never approvable before its tools are checked; it appears with its question, or not at all. */
    @Test
    void thePlanIsPublishedOnlyWithItsQuestionNeverWhileTheSandboxIsMeasured() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        measuring = new CountDownLatch(1);
        gate.set(release);
        MissionId m = start("");
        assertThat(measuring.await(30, java.util.concurrent.TimeUnit.SECONDS))
                .as("the plan's measure started")
                .isTrue();

        // The measure is in progress: nothing of the plan exists, so the director cannot approve anything yet.
        Thread.sleep(300);
        int tickets = office.query(c -> c.tickets().of(m).size());
        assertThat(tickets)
                .as("only the planning ticket while the sandbox is measured")
                .isEqualTo(1);

        gate.set(null);
        release.countDown();
        // From the first moment the plan exists, its question exists too (one command).
        await("the plan", Duration.ofSeconds(30), () -> {
            boolean[] r = office.query(c -> new boolean[] {
                c.tickets().of(m).size() == 3, c.desk().of(m).stream().anyMatch(MissingToolsApiTest::missingTools)
            });
            assertThat(!r[0] || r[1]).as("a plan without its question").isTrue();
            return r[0];
        });
        cancelMission(m);
    }

    /** MAJOR 2: answered "I install it", the mission waits until the new measure has decided. */
    @Test
    void afterAnInstallAnswerNothingRunsUntilTheNewMeasureDecides() throws Exception {
        MissionId m = planned();
        Question q = awaitQuestion(m);
        approveAll(m);

        CountDownLatch release = new CountDownLatch(1);
        measuring = new CountDownLatch(1);
        gate.set(release);
        output.set(WITH);
        api().body("{\"answer\":\"" + OptionCode.INSTALL_MISSING.name() + "\"}")
                .post("/api/questions/" + q.id().value() + "/answer")
                .then()
                .statusCode(200);
        assertThat(measuring.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // The question is closed, the measure not done: still held.
        nothingStarts(m, "no ticket starts while the install is not measured");

        gate.set(null);
        release.countDown();
        await(
                "the hold lifts once measured",
                Duration.ofSeconds(20),
                () -> !office.query(c -> dev.floor42.server.app.LiveSeams.waiting(c, m)));
        assertThat(asked(m)).as("the tools are there now: no second question").hasSize(1);
        cancelMission(m);
    }

    /** MEDIUM 3: "I do the step myself" holds the ticket's review for the director; it is never reopened for it. */
    @Test
    void whenTheDirectorDoesTheStepARejectedReviewGoesToThemNotBackToWork() throws Exception {
        MissionId m = planned(" [fake:rejected]");
        Question q = awaitQuestion(m);
        TicketId render = q.ticket();
        api().body("{\"answer\":\"" + OptionCode.DO_IT_MYSELF.name() + "\"}")
                .post("/api/questions/" + q.id().value() + "/answer")
                .then()
                .statusCode(200);
        // The check is done; the developer's session runs as without a sandbox (no wsl.exe in tests).
        setting(null);
        api().body("{\"tickets\":[\"" + render.value() + "\"]}")
                .post("/api/missions/" + m.value() + "/tickets/approve")
                .then()
                .statusCode(200);
        live.nudge(m);

        await("the review held for the director", Duration.ofSeconds(60), () -> {
            office.query(c -> c.desk().of(m).stream()
                            .filter(x -> x.status() == QuestionStatus.OPEN && x.action() != null)
                            .map(x -> x.id().value())
                            .toList())
                    .forEach(x -> api().body("{\"answer\":\"ALLOW\"}").post("/api/questions/" + x + "/answer"));
            return office.query(c -> c.desk().of(m).stream()
                    .anyMatch(x -> x.status() == QuestionStatus.OPEN
                            && x.kind() == dev.floor42.domain.QuestionKind.REVIEW_ESCALATION
                            && render.equals(x.ticket())));
        });
        Ticket t = office.query(c -> c.tickets().find(render).orElseThrow());
        assertThat(t.status()).as("waits in review for the director").isEqualTo(TicketStatus.IN_REVIEW);
        Question held = office.query(c -> c.desk().of(m).stream()
                .filter(x -> x.kind() == dev.floor42.domain.QuestionKind.REVIEW_ESCALATION)
                .reduce((a, b) -> b)
                .orElseThrow());
        assertThat(held.offers(OptionCode.DO_IT_MYSELF)).isTrue();
        assertThat(held.phrase().params().get("missing")).isEqualTo("chromium, pdftotext");
        cancelMission(m);
    }

    static boolean missingTools(Question q) {
        return q.phrase() != null && q.phrase().code().startsWith("missingTools");
    }

    List<Question> asked(MissionId m) {
        return office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.phrase() != null && q.phrase().code().startsWith("missingTools"))
                .toList());
    }

    Question awaitQuestion(MissionId m) throws Exception {
        await("the missing-tools question", Duration.ofSeconds(30), () -> !asked(m).isEmpty());
        return asked(m).getFirst();
    }

    @Test
    void aTicketThatNeedsAnAbsentToolRaisesOneBlockingQuestionAndNothingRunsMeanwhile() throws Exception {
        MissionId m = planned();
        Question q = awaitQuestion(m);
        Ticket render = office.query(c -> c.tickets().of(m).get(2));

        assertThat(asked(m)).hasSize(1);
        assertThat(runs).as("one measure: the last one did not know the tools").hasSize(1);
        assertThat(String.join(" ", runs.getFirst()))
                .as("the sandbox's script, through wsl.exe on Windows and directly on Linux")
                .contains(System.getProperty("os.name").startsWith("Windows") ? "wsl.exe" : "sandbox/run.sh");
        assertThat(q.blocking()).isTrue();
        assertThat(q.askedBy()).as("Floor42 asks, not an agent").isNull();
        assertThat(q.ticket()).isEqualTo(render.id());
        assertThat(q.options().stream().map(q::codeOf))
                .containsExactly(
                        OptionCode.INSTALL_MISSING,
                        OptionCode.DO_IT_MYSELF,
                        OptionCode.DROP_TICKET,
                        OptionCode.RUN_ANYWAY);
        Map<String, String> p = q.phrase().params();
        assertThat(q.phrase().code()).isEqualTo("missingTools");
        assertThat(p.get("tools"))
                .as("python3 is there; the first ticket needs nothing")
                .isEqualTo("chromium, pdftotext");
        assertThat(p.get("tickets")).isEqualTo(render.id().value());
        assertThat(p.get("command"))
                .isEqualTo(dev.floor42.server.runtime.WslSandbox.installHint("chromium poppler-utils"));
        assertThat(q.context()).contains(p.get("command")).contains("Floor42 installs nothing");

        // The plan approved, nothing starts while the question is open: the same wall would stop the developer.
        String first = office.query(c -> c.tickets().of(m).get(1).id().value());
        api().body("{\"tickets\":[\"" + first + "\",\"" + render.id().value() + "\"]}")
                .post("/api/missions/" + m.value() + "/tickets/approve")
                .then()
                .statusCode(200);
        live.nudge(m);
        SchedulerQuiet.await(scheduler);
        List<?> working = office.query(c -> c.sessions().of(m).stream()
                .filter(s -> s.ticket().value().equals(first) || s.ticket().equals(render.id()))
                .toList());
        assertThat(working)
                .as("no session on the planned tickets while the question is open")
                .isEmpty();

        // Drop that deliverable: the ticket that needs the tools is cancelled, the other one goes on.
        api().body("{\"answer\":\"" + OptionCode.DROP_TICKET.name() + "\"}")
                .post("/api/questions/" + q.id().value() + "/answer")
                .then()
                .statusCode(200);
        await(
                "the ticket dropped",
                Duration.ofSeconds(20),
                () -> office.query(
                                c -> c.tickets().find(render.id()).orElseThrow().status())
                        == TicketStatus.CANCELLED);
        cancelMission(m);
    }

    @Test
    void installedThenMeasuredAgainAsksNothingMore() throws Exception {
        MissionId m = planned();
        Question q = awaitQuestion(m);
        int before = runs.size();

        output.set(WITH);
        api().body("{\"answer\":\"" + OptionCode.INSTALL_MISSING.name() + "\"}")
                .post("/api/questions/" + q.id().value() + "/answer")
                .then()
                .statusCode(200);
        await("a new measure", Duration.ofSeconds(20), () -> runs.size() > before);
        SchedulerQuiet.await(scheduler);

        assertThat(asked(m)).as("no second question: the tools are there now").hasSize(1);
        boolean chromium = office.query(c -> c.preferences()
                .sandboxTools()
                .orElseThrow()
                .tool("chromium")
                .orElseThrow()
                .present());
        assertThat(chromium).isTrue();
        cancelMission(m);
    }

    @Test
    void stillAbsentAfterTheInstallAsksAgainOnce() throws Exception {
        MissionId m = planned();
        Question q = awaitQuestion(m);

        api().body("{\"answer\":\"" + OptionCode.INSTALL_MISSING.name() + "\"}")
                .post("/api/questions/" + q.id().value() + "/answer")
                .then()
                .statusCode(200);
        await("asked again", Duration.ofSeconds(20), () -> asked(m).size() == 2);
        SchedulerQuiet.await(scheduler);
        assertThat(asked(m)).hasSize(2);
        assertThat(asked(m).get(1).status()).isEqualTo(QuestionStatus.OPEN);
        cancelMission(m);
    }

    /** The check and the ask are one command under the office's lock: two callers at once ask once. */
    @Test
    void twoChecksAtOnceAskOnce() throws Exception {
        MissionId m = planned();
        Question q = awaitQuestion(m);
        api().body("{\"answer\":\"" + OptionCode.RUN_ANYWAY.name() + "\"}")
                .post("/api/questions/" + q.id().value() + "/answer")
                .then()
                .statusCode(200);
        SchedulerQuiet.await(scheduler);
        TicketId render = q.ticket();
        CountDownLatch go = new CountDownLatch(1);
        List<Boolean> results = new CopyOnWriteArrayList<>();
        Runnable check = () -> {
            try {
                go.await();
                results.add(missingTools.check(m, Map.of(render, List.of("chromium"))));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Thread a = Thread.ofVirtual().start(check);
        Thread b = Thread.ofVirtual().start(check);
        go.countDown();
        a.join();
        b.join();

        TicketStatus status =
                office.query(c -> c.tickets().find(render).orElseThrow().status());
        assertThat(status).as("still proposed: the director approved nothing").isEqualTo(TicketStatus.PROPOSED);
        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(asked(m).stream().filter(x -> x.status() == QuestionStatus.OPEN))
                .hasSize(1);
        cancelMission(m);
    }

    @Test
    void withoutTheSandboxNothingIsAskedNorMeasured() throws Exception {
        setting(null);
        MissionId m = planned();
        live.nudge(m);
        SchedulerQuiet.await(scheduler);
        assertThat(asked(m)).isEmpty();
        assertThat(runs).isEmpty();
        cancelMission(m);
    }

    /** What a daemon started now would hold: the company rebuilt from the journal alone, nothing from memory. */
    boolean heldAfterRestart(MissionId m) {
        return LiveSeams.waiting(JournalReplay.run(journal.replay()).company(), m);
    }

    /** D25: a settled "I install it" is read from the journal, so a restart neither holds nor measures it again. */
    @Test
    void aRestartAfterASettledInstallAnswerNeitherHoldsNorMeasuresAgain() throws Exception {
        MissionId m = planned();
        Question q = awaitQuestion(m);
        output.set(WITH);
        api().body("{\"answer\":\"" + OptionCode.INSTALL_MISSING.name() + "\"}")
                .post("/api/questions/" + q.id().value() + "/answer")
                .then()
                .statusCode(200);
        await(
                "the hold lifts once measured",
                Duration.ofSeconds(20),
                () -> !office.query(c -> LiveSeams.waiting(c, m)));
        SchedulerQuiet.await(scheduler);
        int before = runs.size();

        assertThat(heldAfterRestart(m))
                .as("the journal says the answer's measure was taken")
                .isFalse();
        LiveSeams.measurePendingInstalls(answers);
        SchedulerQuiet.await(scheduler);
        assertThat(runs).as("no measure at start-up for a settled answer").hasSize(before);
        assertThat(asked(m)).hasSize(1);
        cancelMission(m);
    }

    /**
     * D25: an answer whose measure the journal does not hold (the daemon stopped in between) holds the mission, the stall
     * diagnosis names it, and the start-up measures it once. The answer's own measure, released after, asks nothing more:
     * the record and the ask are one command under the office lock, which sees the question already asked.
     */
    @Test
    void anUnsettledInstallAnswerIsShownHeldAndMeasuredOnceAtRestart() throws Exception {
        MissionId m = planned();
        Question q = awaitQuestion(m);
        approveAll(m);
        CountDownLatch release = new CountDownLatch(1);
        measuring = new CountDownLatch(1);
        gate.set(release);
        api().body("{\"answer\":\"" + OptionCode.INSTALL_MISSING.name() + "\"}")
                .post("/api/questions/" + q.id().value() + "/answer")
                .then()
                .statusCode(200);
        assertThat(measuring.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(heldAfterRestart(m))
                .as("no measure of the answer in the journal")
                .isTrue();
        assertThat(stalls.stall(m, java.time.Instant.now()).reason()).isEqualTo("SANDBOX_MEASURE");

        // The start-up decides now, while the journal holds no measure of the answer. The probe runs one at a time, so
        // its measure follows the released one: whichever records second finds the question already asked.
        gate.set(null);
        int before = runs.size();
        LiveSeams.measurePendingInstalls(answers);
        release.countDown();
        await("asked again", Duration.ofSeconds(20), () -> asked(m).size() == 2);
        SchedulerQuiet.await(scheduler);
        assertThat(asked(m)).as("two measures of one answer ask again once").hasSize(2);

        LiveSeams.measurePendingInstalls(answers);
        SchedulerQuiet.await(scheduler);
        assertThat(runs).as("the start-up measured once, and only once").hasSize(before + 1);
        assertThat(stalls.stall(m, java.time.Instant.now()).reason()).isEqualTo("QUESTION");
        cancelMission(m);
    }

    /** D25: the answer's measure cannot be recorded: the director is asked again, never left with an unexplained hold. */
    @Test
    void aMeasureThatCannotBeRecordedAsksTheDirectorAgain() throws Exception {
        MissionId m = planned();
        Question q = awaitQuestion(m);
        approveAll(m);
        AtomicBoolean failed = new AtomicBoolean();
        LiveSeams.beforeMeasureRecorded(missingTools, () -> {
            if (!failed.getAndSet(true)) throw new IllegalStateException("journal unavailable (test)");
        });
        output.set(WITH);
        api().body("{\"answer\":\"" + OptionCode.INSTALL_MISSING.name() + "\"}")
                .post("/api/questions/" + q.id().value() + "/answer")
                .then()
                .statusCode(200);
        await("asked again", Duration.ofSeconds(20), () -> asked(m).size() == 2);
        SchedulerQuiet.await(scheduler);

        assertThat(failed).isTrue();
        assertThat(asked(m).get(1).status()).isEqualTo(QuestionStatus.OPEN);
        assertThat(stalls.stall(m, java.time.Instant.now()).reason()).isEqualTo("QUESTION");
        cancelMission(m);
    }

    void cancelMission(MissionId m) {
        api().body("{\"reason\":\"test over\"}").post("/api/missions/" + m.value() + "/cancel");
    }
}
