package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.Workspace;
import dev.floor42.server.app.AgentReservations;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.Workshop;
import dev.floor42.server.runtime.Git;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * A workshop accept never commits while anyone works on the mission, and leaves the worktree as it was when it fails:
 * the agent and the mission are held under the dispatch lock while git runs, a failed commit unstages, a failed record
 * moves the branch back with the director's files kept.
 */
@QuarkusTest
class WorkshopHoldTest extends ApiTestBase {

    @Inject
    OfficeService office;

    @Inject
    AgentReservations reservations;

    @Inject
    Git git;

    @Inject
    dev.floor42.server.app.LiveMissions live;

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    /** A mission whose producer is done and idle, with a change of the director's waiting in its worktree. */
    record Ready(String id, AgentId producer, Path worktree, Path repo, String head) {}

    Ready ready() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Hold\",\"objective\":\"Write notes\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        MissionId m = new MissionId(id);
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(200);
        await("the reviewed ticket", Duration.ofSeconds(60), () -> {
            office.query(c -> c.desk().of(m).stream()
                            .filter(q -> q.status() == QuestionStatus.OPEN)
                            .map(q -> q.id().value())
                            .toList())
                    .forEach(q -> api().body("{\"answer\":\"ALLOW\"}").post("/api/questions/" + q + "/answer"));
            return office.query(c -> c.tickets().of(m).getFirst().status()) == TicketStatus.DONE
                    && office.query(c -> c.sessions().of(m).stream().noneMatch(s -> s.isOpen()));
        });
        Workspace w = office.query(c -> c.workshop().workspaces(m).getFirst());
        Path worktree = Path.of(w.path());
        Files.writeString(worktree.resolve("director.txt"), "the director's change\n");
        return new Ready(id, w.agent(), worktree, repo, out(worktree, "rev-parse", "HEAD"));
    }

    static String out(Path dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        return LiveMissionTest.output(dir, cmd);
    }

    Response accept(Ready r) {
        return api().body("{\"message\":\"The director's change\"}")
                .post("/api/missions/" + r.id() + "/workshop/" + r.producer().value() + "/accept");
    }

    void assertAsItWas(Ready r) throws Exception {
        assertThat(out(r.worktree(), "rev-parse", "HEAD")).as("no commit").isEqualTo(r.head());
        assertThat(out(r.worktree(), "diff", "--cached", "--name-only"))
                .as("nothing staged")
                .isEmpty();
        assertThat(Files.readString(r.worktree().resolve("director.txt"))).isEqualTo("the director's change\n");
    }

    @Test
    void aWorktreeMovedOffItsMissionBranchIsNotCommitted() throws Exception {
        Ready r = ready();
        String main = out(r.repo(), "symbolic-ref", "--short", "HEAD");
        String tip = out(r.repo(), "rev-parse", main);
        // What a command in the sandbox could do when the worktree's HEAD was writable.
        out(r.worktree(), "symbolic-ref", "HEAD", "refs/heads/" + main);

        assertThat(accept(r).statusCode()).isGreaterThanOrEqualTo(400);

        assertThat(out(r.repo(), "rev-parse", main))
                .as("nothing committed on " + main)
                .isEqualTo(tip);
        assertThat(out(r.worktree(), "diff", "--cached", "--name-only"))
                .as("nothing staged")
                .isEmpty();
    }

    @Test
    void anAgentHeldElsewhereRefusesTheAcceptThenItGoesThrough() throws Exception {
        Ready r = ready();
        AgentReservations.Reservation held = reservations.reserve(r.producer()).orElseThrow();
        try {
            accept(r).then().statusCode(409).body("code", equalTo("AGENT_AT_WORK"));
            assertAsItWas(r);
        } finally {
            reservations.release(held);
        }
        accept(r).then().statusCode(200);
        assertThat(out(r.worktree(), "rev-parse", "HEAD")).isNotEqualTo(r.head());
    }

    @Test
    void whileGitRunsTheAgentCannotBeGivenWork() throws Exception {
        Ready r = ready();
        CountDownLatch inGit = new CountDownLatch(1);
        CountDownLatch gitMayGo = new CountDownLatch(1);
        QuarkusMock.installMockForType(
                new Workshop(office, git, dataDir, reservations) {
                    @Override
                    protected String commit(Workspace w, MissionId mission, String message) {
                        inGit.countDown();
                        try {
                            gitMayGo.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            // Handled: the test is ending; the commit goes on
                            Thread.currentThread().interrupt();
                        }
                        return super.commit(w, mission, message);
                    }
                },
                Workshop.class);
        // Another developer, free, and a ticket for them: without the mission's hold the dispatcher starts it at once.
        String other = api().body("{\"name\":\"Holder " + r.id() + "\",\"workRole\":\"DEVELOPER\","
                        + "\"model\":\"claude-haiku-5-5\"}")
                .post("/api/agents")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        MissionId m = new MissionId(r.id());
        CompletableFuture<Integer> accepted =
                CompletableFuture.supplyAsync(() -> accept(r).statusCode());
        assertThat(inGit.await(30, TimeUnit.SECONDS)).isTrue();
        dev.floor42.domain.TicketId next = ((dev.floor42.domain.event.TicketEvent.TicketCreated) office.execute(
                                c -> c.tickets()
                                        .create(
                                                office.context(
                                                        dev.floor42.domain.Actor.system(),
                                                        dev.floor42.domain.Origin.REAL),
                                                m,
                                                "Deliver: another report",
                                                "Describe this repository",
                                                dev.floor42.domain.Priority.NORMAL,
                                                List.of(),
                                                List.of("The report answers the objective")))
                        .getFirst()
                        .event())
                .ticket();
        office.execute(c -> c.tickets()
                .assign(
                        office.context(dev.floor42.domain.Actor.system(), dev.floor42.domain.Origin.REAL),
                        next,
                        new AgentId(other)));
        live.nudge(m);
        Thread.sleep(3_000);
        Optional<AgentReservations.Reservation> taken = reservations.reserve(r.producer());
        taken.ifPresent(reservations::release);
        boolean started = office.query(
                c -> c.sessions().of(m).stream().anyMatch(s -> s.ticket().equals(next)));
        gitMayGo.countDown();
        assertThat(taken)
                .as("the dispatcher cannot take the agent while the director commits")
                .isEmpty();
        assertThat(started)
                .as("nothing starts on the mission while the director commits")
                .isFalse();
        assertThat(accepted.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        await(
                "the other ticket started once the hold ended",
                Duration.ofSeconds(30),
                () -> office.query(c ->
                        c.sessions().of(m).stream().anyMatch(s -> s.ticket().equals(next))));
        api().body("{\"reason\":\"test over\"}").post("/api/missions/" + r.id() + "/cancel");
    }

    @Test
    void aRecordThatFailsMovesTheBranchBackAndKeepsTheFiles() throws Exception {
        Ready r = ready();
        QuarkusMock.installMockForType(
                new Workshop(office, git, dataDir, reservations) {
                    @Override
                    protected void record(
                            MissionId mission, AgentId agent, String sha, String message, List<String> files) {
                        throw new IllegalStateException("the journal could not be written");
                    }
                },
                Workshop.class);
        assertThat(accept(r).statusCode()).isGreaterThanOrEqualTo(400);
        assertAsItWas(r);
    }

    @Test
    void aCommitThatFailsUnstagesWhatItStaged() throws Exception {
        Ready r = ready();
        Path hook = r.repo().resolve(".git").resolve("hooks").resolve("pre-commit");
        Files.createDirectories(hook.getParent());
        Files.writeString(hook, "#!/bin/sh\necho refused by the test hook >&2\nexit 1\n");
        hook.toFile().setExecutable(true);
        try {
            assertThat(accept(r).statusCode()).isGreaterThanOrEqualTo(400);
            assertAsItWas(r);
        } finally {
            Files.delete(hook);
        }
    }
}
