package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.CheckRun;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.api.MissionViews.MissionView;
import dev.floor42.server.api.Scorecard.ScoreLine;
import dev.floor42.server.app.Briefs;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks measured by Floor42 and a budget held between sessions, end to end with the fake Claude Code. */
@QuarkusTest
class ChecksAndBudgetApiTest extends QuestionApiTestBase {

    int sessions(MissionId m) {
        return office.query(c -> c.sessions().of(m).size());
    }

    @Test
    void checksRunBeforeTheLastDeliveryAreNotEvidenceForIt() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Stale checks\",\"objective\":\"Write notes\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        MissionId m = new MissionId(id);
        api().body("{\"command\":\"git log -1 --format=%s\",\"automatic\":false}")
                .put("/api/missions/" + id + "/checks")
                .then()
                .statusCode(200);
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(200);
        await("the first delivery is reviewed", Duration.ofSeconds(30), () -> {
            answerAllOpen(m);
            return office.query(c -> c.tickets().of(m).getFirst().status() == TicketStatus.DONE);
        });
        String producer =
                office.query(c -> c.tickets().of(m).getFirst().assignee().value());
        api().post("/api/missions/" + id + "/workshop/" + producer + "/checks")
                .then()
                .statusCode(200);
        java.util.function.Supplier<String> brief = () -> office.query(c -> Briefs.reviewer(
                c,
                c.missions().find(m).orElseThrow(),
                c.tickets().of(m).getFirst(),
                c.staff().find(new AgentId("darius")).orElseThrow()));
        assertThat(brief.get()).contains("Checks run by Floor42 on this work (measured");

        // The director sends the work back: the run above came before the new delivery and proves nothing about it.
        String ticket = office.query(c -> c.tickets().of(m).getFirst().id().value());
        api().body("{\"ticket\":\"" + ticket + "\",\"directive\":\"Add a line\"}")
                .post("/api/missions/" + id + "/workshop/directive")
                .then()
                .statusCode(200);
        await("the second delivery is reviewed", Duration.ofSeconds(30), () -> {
            answerAllOpen(m);
            return office.query(c -> c.sessions().of(m).stream()
                                    .filter(s -> s.agent().value().equals(producer))
                                    .count()
                            == 2
                    && c.tickets().of(m).getFirst().status() == TicketStatus.DONE);
        });
        assertThat(brief.get())
                .contains("have not run on this delivery")
                .doesNotContain("Checks run by Floor42 on this work (measured");
        api().body("{\"reason\":\"test done\"}").post("/api/missions/" + id + "/cancel");
    }

    void answerAllOpen(MissionId m) {
        office.query(c -> c.desk().of(m).stream()
                        .filter(q -> q.status() == dev.floor42.domain.QuestionStatus.OPEN)
                        .map(q -> q.id().value())
                        .toList())
                .forEach(q -> api().body("{\"answer\":\"ALLOW\"}").post("/api/questions/" + q + "/answer"));
    }

    @Test
    void checksAreEvidenceAndTheBudgetHoldsTheNextSession() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Checked\",\"objective\":\"Write notes\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        MissionId m = new MissionId(id);

        // The director's checks, after every delivery: a command that works with cmd.exe and sh alike.
        api().body("{\"command\":\"git log -1 --format=%s\",\"automatic\":true}")
                .put("/api/missions/" + id + "/checks")
                .then()
                .statusCode(200)
                .body("checkCommand", equalTo("git log -1 --format=%s"))
                .body("checksAutomatic", equalTo(true));
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(200);
        answer(m, "ALLOW"); // git status
        answer(m, "ALLOW"); // the write
        await(
                "the review",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.tickets().of(m).getFirst().status() == TicketStatus.DONE));

        List<CheckRun> runs = office.query(c -> c.verification().checkRuns(m));
        assertThat(runs).singleElement().satisfies(r -> {
            assertThat(r.trigger()).isEqualTo("after delivery");
            assertThat(r.passed()).isTrue();
            assertThat(r.output()).contains("init");
        });
        String brief = office.query(c -> Briefs.reviewer(
                c,
                c.missions().find(m).orElseThrow(),
                c.tickets().of(m).getFirst(),
                c.staff().find(new AgentId("darius")).orElseThrow()));
        assertThat(brief)
                .contains("Checks run by Floor42 on this work (measured")
                .contains("exited with 0");

        // What the checks create (build output) never mixes with the agent's work.
        api().body("{\"command\":\"git log -1 --format=%s > created-by-checks.txt\",\"automatic\":false}")
                .put("/api/missions/" + id + "/checks")
                .then()
                .statusCode(200);
        api().post("/api/missions/" + id + "/workshop/chris/checks")
                .then()
                .statusCode(200)
                .body("passed", equalTo(true))
                .body("output", org.hamcrest.Matchers.containsString("Floor42 removed 1 file(s)"));
        api().get("/api/missions/" + id + "/workshop")
                .then()
                .statusCode(200)
                .body(
                        "workspaces[0].changes.path",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("created-by-checks.txt")));

        // Run from the workshop, a failing command reports its real exit code.
        api().body("{\"command\":\"git rev-parse --verify no-such-ref\",\"automatic\":false}")
                .put("/api/missions/" + id + "/checks")
                .then()
                .statusCode(200);
        api().post("/api/missions/" + id + "/workshop/chris/checks")
                .then()
                .statusCode(200)
                .body("passed", equalTo(false))
                .body("exitCode", equalTo(128))
                .body("trigger", equalTo("director"));

        // A budget below what was already spent: the next session is held and the director asked.
        api().body("{\"usd\":0.01}")
                .put("/api/missions/" + id + "/budget")
                .then()
                .statusCode(200);
        int before = sessions(m);
        String t = office.query(c -> c.tickets().of(m).getFirst().id().value());
        api().body("{\"ticket\":\"" + t + "\",\"directive\":\"Shorter notes\"}")
                .post("/api/missions/" + id + "/workshop/directive")
                .then()
                .statusCode(200);
        await("the budget question", Duration.ofSeconds(20), () -> open(m) != null);
        assertThat(open(m).kind()).isEqualTo(QuestionKind.BUDGET_REACHED);
        assertThat(sessions(m)).as("no session started over budget").isEqualTo(before);

        // Raised: the producer starts again; its session spends the new budget, so the review is held in turn.
        answer(m, "Raise the budget by 50%");
        await("the producer", Duration.ofSeconds(20), () -> sessions(m) == before + 1 && open(m) != null);
        assertThat(open(m).kind()).isEqualTo(QuestionKind.BUDGET_REACHED);
        // Raised from the desk instead: the open budget question is settled and the held review runs (D9: read from the
        // journal, not from memory).
        api().body("{\"usd\":50}").put("/api/missions/" + id + "/budget").then().statusCode(200);
        await("the held review", Duration.ofSeconds(20), () -> sessions(m) == before + 2 && open(m) == null);
        java.util.List<String> budgetQuestions = office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.kind() == QuestionKind.BUDGET_REACHED)
                .map(q -> q.status().name())
                .toList());
        assertThat(budgetQuestions).containsExactly("ANSWERED", "WITHDRAWN");

        MissionView view = api().get("/api/missions/" + id)
                .then()
                .statusCode(200)
                .extract()
                .as(MissionView.class);
        assertThat(view.scorecard())
                .filteredOn(l -> l.label().equals("Checks"))
                .singleElement()
                .extracting(ScoreLine::basis)
                .isEqualTo("measured by Floor42");
        assertThat(view.scorecard())
                .filteredOn(l -> l.label().equals("Cost"))
                .singleElement()
                .extracting(ScoreLine::basis)
                .isEqualTo("estimated by Claude Code");
    }
}
