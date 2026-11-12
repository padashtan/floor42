package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.app.LiveMissions;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Who takes which ticket (ADR-0019): one session per agent whatever the missions, a developer whose finished changes
 * wait for the director takes nothing new, and a ticket that builds on another developer's branch merges it first, or
 * asks the director when that work was never accepted.
 */
@QuarkusTest
class DispatchAgentsTest extends DispatchFixture {

    @Inject
    LiveMissions liveMissions;

    @Test
    void anAgentNeverRunsTwoSessionsAtOnceWhateverTheMissions() throws Exception {
        Instant from = Instant.now();
        MissionId a = mission("Describe this repository [fake:slow=2]");
        MissionId b = mission("Describe this repository [fake:slow=2]");
        approve(a);
        approve(b);
        await("both missions done", Duration.ofSeconds(120), () -> {
            answerAll(a, null);
            answerAll(b, null);
            return done(a) && done(b);
        });
        quiet();
        Instant to = Instant.now();

        // Every session of every mission that ran while this test did, not only those of a and b: the dispatcher
        // started each test quiet, so nothing another test left behind is counted.
        List<AgentSession> all = office.query(c -> c.missions().all().stream()
                .flatMap(m -> c.sessions().of(m.id()).stream())
                .filter(s -> !s.isOpen())
                .filter(s -> s.endedAt().isAfter(from) && s.startedAt().isBefore(to))
                .toList());
        List<Object> ours = office.query(c -> Stream.concat(c.sessions().of(a).stream(), c.sessions().of(b).stream())
                .map(s -> (Object) s.id())
                .toList());
        assertThat(all).extracting(s -> (Object) s.id()).containsAll(ours);
        assertThat(ours).hasSizeGreaterThanOrEqualTo(2);
        assertNoAgentRanTwoAtOnce(all);

        // And, as before the split, over the whole journal: every real session any test of this application ran so
        // far, per agent. The rehearsals (Origin.SIMULATED, PracticeScenario) are left out: they play a script on
        // their own thread and are not sessions the dispatcher starts.
        List<AgentSession> everyReal = office.query(c -> c.missions().all().stream()
                .filter(m -> m.origin() == dev.floor42.domain.Origin.REAL)
                .flatMap(m -> c.sessions().of(m.id()).stream())
                .filter(s -> !s.isOpen())
                .toList());
        assertThat(everyReal).extracting(s -> (Object) s.id()).containsAll(ours);
        assertNoAgentRanTwoAtOnce(everyReal);
        cancel(a);
        cancel(b);
    }

    static void assertNoAgentRanTwoAtOnce(List<AgentSession> sessions) {
        for (AgentSession x : sessions) {
            for (AgentSession y : sessions) {
                if (x == y || !x.agent().equals(y.agent())) continue;
                boolean overlap =
                        x.startedAt().isBefore(y.endedAt()) && y.startedAt().isBefore(x.endedAt());
                assertThat(overlap)
                        .as("%s ran %s and %s at once", x.agent(), x.id(), y.id())
                        .isFalse();
            }
        }
    }

    @Test
    void aDeveloperTakesNoNewTicketWhileItsFinishedChangesWaitForTheDirector() throws Exception {
        MissionId m = mission("Write notes [fake:touch=notes.txt]");
        TicketId first = office.query(c -> c.tickets().of(m).getFirst().id());
        approve(m);
        TicketId second =
                ticket(m, "Deliver: more notes", "Write notes [fake:touch=notes.txt] [fake:slow=3]", List.of(first));

        await("the first ticket is done", Duration.ofSeconds(60), () -> {
            answerAll(m, null);
            return status(first) == TicketStatus.DONE;
        });
        String producer = assignee(first);
        await(
                "the producer waits for the director",
                Duration.ofSeconds(10),
                () -> api().get("/api/missions/" + m.value() + "/held")
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath()
                        .getList("agent")
                        .contains(producer));
        String heldFor = api().get("/api/missions/" + m.value() + "/held")
                .then()
                .extract()
                .path("find { it.agent == '" + producer + "' }.ticket");
        assertThat(heldFor).isEqualTo(first.value());
        // Its worktree holds notes.txt from the first ticket: the second, which builds on it, does not start, even
        // when the dispatcher looks again.
        liveMissions.nudge(m);
        quiet();
        assertThat(status(second)).isEqualTo(TicketStatus.TODO);
        boolean started = office.query(
                c -> c.sessions().of(m).stream().anyMatch(s -> s.ticket().equals(second)));
        assertThat(started).isFalse();

        api().body("{\"message\":\"Notes of the first ticket\"}")
                .post("/api/missions/" + m.value() + "/workshop/" + producer + "/accept")
                .then()
                .statusCode(200);

        // While the second ticket is reviewed, its changes are those of the ticket under way: the developer is at
        // work, not held for the first ticket it finished (seen on M-8, 2026-10-22).
        await("the second ticket in review", Duration.ofSeconds(60), () -> {
            answerAll(m, null);
            return status(second) == TicketStatus.IN_REVIEW;
        });
        List<String> heldDuringReview = api().get("/api/missions/" + m.value() + "/held")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("agent");
        assertThat(heldDuringReview).doesNotContain(producer);
        await("the second ticket is done", Duration.ofSeconds(60), () -> {
            answerAll(m, null);
            return status(second) == TicketStatus.DONE;
        });
        assertThat(assignee(second)).as("it builds on the same worktree").isEqualTo(producer);
        cancel(m);
    }

    @Test
    void aTicketBuildingOnABranchItsTakerLacksWaitsForTheDirector() throws Exception {
        MissionId m = mission("Describe this repository [fake:touch=IDA.txt]");
        TicketId first = office.query(c -> c.tickets().of(m).getFirst().id());
        String ida = hireDeveloper("Ida");
        assign(first, ida);
        approve(m);
        await("Ida's ticket is done", Duration.ofSeconds(60), () -> {
            answerAll(m, null);
            return status(first) == TicketStatus.DONE;
        });
        dismissWhenFree(ida);

        // Ida's work is on her branch only: whoever takes these tickets will not have it.
        TicketId goOn = ticket(m, "Deliver: a follow-up", "Describe this repository", List.of(first));
        TicketId drop = ticket(m, "Deliver: another follow-up", "Describe this repository", List.of(first));
        Function<TicketId, Question> asked = t -> office.query(c -> c.desk().of(m).stream()
                .filter(q -> t.equals(q.ticket()) && q.status() == QuestionStatus.OPEN)
                .findFirst()
                .orElse(null));
        await("the director is asked about both", Duration.ofSeconds(20), () -> {
            answerAll(m, null);
            return asked.apply(goOn) != null && asked.apply(drop) != null;
        });
        Question q = asked.apply(goOn);
        assertThat(q.options()).containsExactly("Go on without it", "Drop the ticket");
        assertThat(q.blocking()).isFalse();
        assertThat(q.context())
                .contains(first.value())
                .contains("no longer on staff")
                .contains("could not merge");
        Optional<Boolean> notMerged = office.query(c -> c.workshop().merge(goOn));
        assertThat(notMerged).as("Ida's work was not accepted").contains(false);
        assertThat(status(goOn)).isEqualTo(TicketStatus.TODO);

        api().body("{\"answer\":\"Drop the ticket\"}")
                .post("/api/questions/" + asked.apply(drop).id().value() + "/answer")
                .then()
                .statusCode(200);
        api().body("{\"answer\":\"Go on without it\"}")
                .post("/api/questions/" + q.id().value() + "/answer")
                .then()
                .statusCode(200);

        await("the dropped one is cancelled", Duration.ofSeconds(10), () -> status(drop) == TicketStatus.CANCELLED);
        await("the other one is done", Duration.ofSeconds(60), () -> {
            answerAll(m, null);
            return status(goOn) == TicketStatus.DONE;
        });
        assertThat(assignee(goOn)).isNotEqualTo(ida);
        cancel(m);
    }

    @Test
    void officeMergesAcceptedWorkFromAnotherBranchBeforeATicketBuildsOnIt() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        MissionId m = mission(repo, "Describe this repository [fake:touch=IDA.txt]", "");
        TicketId first = office.query(c -> c.tickets().of(m).getFirst().id());
        String ida = hireDeveloper("Ida");
        assign(first, ida);
        approve(m);
        await("Ida's ticket is done", Duration.ofSeconds(60), () -> {
            answerAll(m, null);
            return status(first) == TicketStatus.DONE;
        });
        api().body("{\"message\":\"Ida's notes\"}")
                .post("/api/missions/" + m.value() + "/workshop/" + ida + "/accept")
                .then()
                .statusCode(200);
        String idaBranch = office.query(
                c -> c.workshop().workspace(m, new AgentId(ida)).orElseThrow().branch());
        dismissWhenFree(ida);

        TicketId next = ticket(m, "Deliver: a follow-up", "Describe this repository", List.of(first));
        await("the follow-up is done", Duration.ofSeconds(60), () -> {
            answerAll(m, null);
            return status(next) == TicketStatus.DONE;
        });
        Optional<Boolean> merged = office.query(c -> c.workshop().merge(next));
        assertThat(merged).as("merged without asking").contains(true);
        String taker = assignee(next);
        assertThat(taker).isNotEqualTo(ida);
        String takerBranch = office.query(
                c -> c.workshop().workspace(m, new AgentId(taker)).orElseThrow().branch());
        // The taker's branch now contains Ida's accepted commit: measured with git, not assumed.
        LiveMissionTest.run(repo, "git", "merge-base", "--is-ancestor", idaBranch, takerBranch);
        assertThat(LiveMissionTest.output(repo, "git", "ls-tree", "--name-only", takerBranch))
                .contains("IDA.txt");
        boolean notAsked = office.query(c -> c.desk().of(m).stream()
                .noneMatch(q -> next.equals(q.ticket()) && q.options().contains("Go on without it")));
        assertThat(notAsked).isTrue();
        cancel(m);
    }
}
