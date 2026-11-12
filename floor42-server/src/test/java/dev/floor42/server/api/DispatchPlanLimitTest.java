package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.TicketId;
import dev.floor42.server.app.MissionRecovery;
import dev.floor42.server.app.SchedulerQuiet;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The plan's usage limit (ADR-0019): "Run anyway" covers every task started together, and nothing asks again while a
 * mission rests until the reset. The fixture waits for the window to reset before and after each test.
 */
@QuarkusTest
class DispatchPlanLimitTest extends DispatchFixture {

    @Inject
    MissionRecovery recovery;

    @Test
    void runAnywayCoversEveryTaskStartedTogether() throws Exception {
        MissionId m = mission("Write notes [fake:usage=0.97]");
        approve(m);
        // A second ticket for a second developer: both are produced at once, then both wait for a review.
        TicketId second = ticket(m, "Deliver: more notes", "Write notes [fake:usage=0.97]", List.of());
        assign(second, "chris");
        api().body("{\"name\":\"Runa\",\"workRole\":\"DEVELOPER\",\"model\":\"claude-haiku-5-5\"}")
                .post("/api/agents")
                .then()
                .statusCode(200);

        await("both tickets done", Duration.ofSeconds(120), () -> {
            answerAll(m, "Run anyway");
            return done(m);
        });
        assertThat(planLimitQuestions(m))
                .as("the director answered once for every task started together")
                .isEqualTo(1);
        cancel(m);
    }

    @Tag("slow")
    @Test
    void nothingAsksAgainWhileTheMissionRestsUntilTheReset() throws Exception {
        // 20 s of rest instead of the fake's 6: under load the count below must still fall inside the rest.
        MissionId m = mission("Write notes [fake:usage=0.97] [fake:reset=20]");
        approve(m);
        await("the plan-limit question", Duration.ofSeconds(60), () -> {
            answerAll(m, null);
            return planLimitQuestions(m) == 1;
        });
        answerAll(m, "Wait for the reset");
        // Hiring makes the dispatcher look at every mission at once: the resting one starts nothing. The rest itself
        // is a wake-up due at the reset: everything else must end.
        api().body("{\"name\":\"Rest\",\"workRole\":\"REVIEWER\",\"model\":\"claude-haiku-5-5\"}")
                .post("/api/agents")
                .then()
                .statusCode(200);
        // The rest is the mission's wake-up, counted until it is due and while the task it becomes runs.
        String rest = SchedulerQuiet.planReset(m);
        quietBut(task -> task.equals(rest));
        assertThat(planLimitQuestions(m)).as("no second question while resting").isEqualTo(1);
        // The race left: the window resets about 20 s after the question (the fake's), and the rest then ends and
        // produces again. Were it over already, the count above would have been taken after the rest, not during it:
        // the test then fails at its end, visibly, instead of passing on a check that proved nothing. The rest
        // task still running after the count means the count was taken while it rested.
        boolean countedWhileResting =
                SchedulerQuiet.running(scheduler, task -> task.equals(rest)).contains(rest);

        await("the review, after the reset", Duration.ofSeconds(90), () -> {
            answerAll(m, "Wait for the reset");
            return done(m);
        });
        cancel(m);
        assertThat(countedWhileResting)
                .as("the usage window reset before the count: nothing was checked during the rest")
                .isTrue();
    }

    /**
     * After a restart, "Wait for the reset" is honoured again (recovery's PLAN_LIMIT branch): the mission rests until
     * the reset, one wake-up resumes it, and recovery goes on at once. Before 1.8.0 recovery slept on its own thread
     * until the window reset, holding up every mission after this one.
     */
    @Tag("slow")
    @Test
    void aRestartWaitsForTheResetAgainWithoutHoldingRecovery() throws Exception {
        MissionId m = mission("Write notes [fake:usage=0.97]");
        approve(m);
        await("the plan-limit question", Duration.ofSeconds(60), () -> {
            answerAll(m, null);
            return planLimitQuestions(m) == 1;
        });
        // The window resets in 12 s: long enough for a sleeping recovery to show.
        String session = office.query(c -> c.sessions().of(m).getLast().id());
        long resetsAt = Instant.now().plusSeconds(12).getEpochSecond();
        office.execute(c -> c.verification()
                .observePlanUsage(
                        office.context(Actor.system(), Origin.REAL), session, "five_hour", 0.97, resetsAt, "allowed"));
        answerAll(m, "Wait for the reset");
        await(
                "the rest is scheduled",
                Duration.ofSeconds(10),
                () -> SchedulerQuiet.wakeUps(scheduler).contains(SchedulerQuiet.planReset(m)));

        // What the next daemon does at start, with this answer in the journal and no session since.
        Instant before = Instant.now();
        recovery.recover();
        Duration took = Duration.between(before, Instant.now());

        assertThat(took).as("recovery does not wait for the reset").isLessThan(Duration.ofSeconds(5));
        assertThat(SchedulerQuiet.wakeUps(scheduler))
                .as("one wake-up for the mission, the restart's replacing the first")
                .containsOnlyOnce(SchedulerQuiet.planReset(m));
        quietBut(task -> task.equals(SchedulerQuiet.planReset(m)));
        boolean running = office.query(c -> c.sessions().of(m).stream().anyMatch(AgentSession::isOpen));
        assertThat(running).as("nothing runs while the mission rests").isFalse();
        await("the review, after the reset", Duration.ofSeconds(60), () -> {
            answerAll(m, "Wait for the reset");
            return done(m);
        });
        cancel(m);
    }
}
