package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.Committee;
import dev.floor42.domain.MissionId;
import dev.floor42.server.app.Briefs;
import dev.floor42.server.app.OfficeService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * An idea committee end to end with the fake Claude Code: no repository yet, a neutral moderator, the director speaking
 * in person in their double's seat, and a debate that ends as soon as the members agree.
 */
@QuarkusTest
class IdeaCommitteeApiTest extends ApiTestBase {

    @Inject
    OfficeService office;

    Committee committee(MissionId m) {
        return office.query(c -> c.committees().latest(m).orElseThrow());
    }

    @Test
    void anIdeaCommitteeMeetsBeforeTheProjectExistsAndStopsDebatingOnceItAgrees() throws Exception {
        api().get("/api/committee/roles")
                .then()
                .statusCode(200)
                .body("key", hasItems("moderator", "lawyer", "altruist", "visionary", "double", "sales", "novice"));
        api().body("{\"title\":\"Tools\",\"objective\":\"x\",\"repository\":\"\",\"committee\":[\"lawyer\"],"
                        + "\"panel\":\"idea\"}")
                .post("/api/missions")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_ARGUMENT"));
        api().body("{\"title\":\"Tools\",\"objective\":\"x\",\"repository\":\"\",\"committee\":[\"operations\","
                        + "\"lawyer\"],\"panel\":\"idea\"}")
                .post("/api/missions")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_ARGUMENT"));

        String id = api().body("{\"title\":\"Tool lending\",\"objective\":\"Should neighbours lend and rent their"
                        + " tools? [fake:slow=2]\",\"repository\":\"\",\"committee\":[\"double\",\"lawyer\","
                        + "\"novice\"],\"panel\":\"idea\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .body("committee.panel", equalTo("idea"))
                .body("committee.moderated", equalTo(true))
                .body("committee.maxDebateRounds", equalTo(3))
                .body("committee.directorSeat", equalTo("double"))
                .body("committee.members.agent", hasItems("lucie", "clara", "marcel", "double"))
                .body("committee.members[0].agent", equalTo("lucie"))
                .body("committee.members[3].agent", equalTo("double"))
                .extract()
                .path("id");
        MissionId m = new MissionId(id);
        String workspace = office.query(c -> c.missions().find(m).orElseThrow().workspace());
        Path project = Path.of(workspace);
        assertThat(project.toString()).contains("projects");
        assertThat(Files.isDirectory(project.resolve(".git"))).isTrue();

        api().body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(200);
        // The director speaks in person before their double, whose turn comes last.
        api().body("{\"text\":\"Lending only to start.\",\"proposal\":\"Lending only\",\"position\":\"NEW\"}")
                .post("/api/missions/" + id + "/committees/1/contribute")
                .then()
                .statusCode(200)
                .body("committee.contributions.find { it.byDirector }.member", equalTo("double"));

        await(
                "the moderator's record",
                Duration.ofSeconds(90),
                () -> committee(m).stage() == Committee.Stage.AWAITING_DIRECTOR);

        Committee c = committee(m);
        // Round 1: the lawyer, the novice and the director in person; round 2: the three agree; then the record.
        assertThat(c.contributions())
                .extracting(Committee.Contribution::kind)
                .containsExactly(
                        Committee.Kind.ANALYSIS,
                        Committee.Kind.ANALYSIS,
                        Committee.Kind.ANALYSIS,
                        Committee.Kind.REBUTTAL,
                        Committee.Kind.REBUTTAL,
                        Committee.Kind.REBUTTAL,
                        Committee.Kind.SYNTHESIS);
        assertThat(c.contributions())
                .filteredOn(x -> x.member().equals(new AgentId("lucie")))
                .extracting(Committee.Contribution::kind)
                .containsExactly(Committee.Kind.SYNTHESIS);
        assertThat(c.agreement()).contains(new AgentId("clara"));
        int sessions = office.query(cc -> cc.sessions().of(m).size());
        assertThat(sessions)
                .as("the double did not speak in the round the director took")
                .isEqualTo(6);
        String ruling = office.query(cc -> cc.desk().of(m).getLast().context());
        assertThat(ruling).contains("agree on clara's proposal");

        String moderatorBrief = office.query(cc -> Briefs.committeeChair(
                cc,
                cc.missions().find(m).orElseThrow(),
                committee(m),
                cc.staff().find(new AgentId("lucie")).orElseThrow()));
        assertThat(moderatorBrief)
                .contains("you take no side")
                .contains("written by the director in person")
                .contains("RECOMMENDATION:");
        String doubleBrief = office.query(cc -> Briefs.committeeMember(
                cc,
                cc.missions().find(m).orElseThrow(),
                committee(m),
                cc.staff().find(new AgentId("double")).orElseThrow()));
        assertThat(doubleBrief)
                .contains("you stand in for the director")
                .contains("decisions backed by measured facts");

        api().body("{\"reason\":\"test done\"}").post("/api/missions/" + id + "/cancel");
    }
}
