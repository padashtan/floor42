package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.ActionStage;
import dev.floor42.domain.ConsultationKind;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.ReviewVerdict;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.app.OfficeService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A reviewer in doubt asks for help once (ADR-0018): a peer reads the worktree and advises, or a research session with
 * web tools only answers, if the mission's table allows; then the reviewer concludes with the answer in hand.
 */
@QuarkusTest
class ConsultationApiTest extends ApiTestBase {

    @Inject
    OfficeService office;

    @Inject
    dev.floor42.server.journal.Journal journal;

    boolean isNora(dev.floor42.domain.PermissionRule r) {
        return r.agent() != null && r.agent().value().equals("nora");
    }

    Ticket ticket(MissionId m) {
        return office.query(c -> c.tickets().of(m).getFirst());
    }

    MissionId mission(String objective) throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Review help\",\"objective\":\"" + objective + "\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        return new MissionId(id);
    }

    /** Approves the mission and waits for its ticket to close, allowing every permission asked on the way. */
    void runToTheEnd(MissionId m) throws Exception {
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + m.value() + "/approve")
                .then()
                .statusCode(200);
        await("the reviewed ticket", Duration.ofSeconds(60), () -> {
            office.query(c -> c.desk().of(m).stream()
                            .filter(q -> q.status() == QuestionStatus.OPEN)
                            .map(q -> q.id().value())
                            .toList())
                    .forEach(q -> api().body("{\"answer\":\"ALLOW\"}").post("/api/questions/" + q + "/answer"));
            return ticket(m).status() == TicketStatus.DONE;
        });
    }

    List<String> sessions(MissionId m) {
        return office.query(
                c -> c.sessions().of(m).stream().map(s -> s.agent().value()).toList());
    }

    @Test
    void theReviewerConsultsAPeerThenConcludes() throws Exception {
        MissionId m = mission("Describe this repository [fake:consult]");
        runToTheEnd(m);

        Ticket t = ticket(m);
        assertThat(t.consultations()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(ConsultationKind.CONSULT);
            assertThat(c.role()).isEqualTo("security");
            assertThat(c.question()).isEqualTo("Is a static token acceptable for this service?");
            assertThat(c.answeredBy().value()).isEqualTo("nora");
            assertThat(c.answer()).startsWith("No: a static token");
        });
        assertThat(t.reviews())
                .singleElement()
                .satisfies(r -> assertThat(r.verdict()).isEqualTo(ReviewVerdict.VALIDATED));
        assertThat(sessions(m)).containsExactly("chris", "dehren", "nora", "dehren");
        // Nora is denied writing, the shell and the network for the consultation, by rule, and only for it.
        List<String> rules = journal.readAll().stream()
                .map(e -> e.event())
                .map(e -> switch (e) {
                    case dev.floor42.domain.event.MissionEvent.PermissionRuleAdded a
                    when a.mission().equals(m) && isNora(a.rule()) ->
                        "+" + a.rule().decision();
                    case dev.floor42.domain.event.MissionEvent.PermissionRuleRemoved r
                    when r.mission().equals(m) && isNora(r.rule()) ->
                        "-" + r.rule().decision();
                    default -> "";
                })
                .filter(x -> !x.isEmpty())
                .toList();
        assertThat(rules)
                .as("the consultant changes nothing, by rule, for the consultation only")
                .containsExactly("+DENY", "+DENY", "+DENY", "-DENY", "-DENY", "-DENY");
        boolean lifted = office.query(c ->
                c.missions().find(m).orElseThrow().policy().rules().stream().noneMatch(this::isNora));
        assertThat(lifted).isTrue();
    }

    @Test
    void researchRunsOnlyWhenTheTableAllowsWebTools() throws Exception {
        // The default table denies the web: the research is not run, and the reviewer concludes without it.
        MissionId denied = mission("Describe this repository [fake:research]");
        runToTheEnd(denied);
        assertThat(ticket(denied).consultations()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(ConsultationKind.RESEARCH);
            assertThat(c.answeredBy()).isNull();
            assertThat(c.answer()).startsWith("Research not run");
        });
        assertThat(sessions(denied)).containsExactly("chris", "dehren", "dehren");

        // Allowed by the table: a session with web tools only, in an empty directory, answers with its sources.
        MissionId allowed = mission("Describe this repository [fake:research]");
        api().body("{\"decision\":\"ALLOW\"}")
                .put("/api/missions/" + allowed.value() + "/tools/WebSearch")
                .then()
                .statusCode(200);
        runToTheEnd(allowed);
        assertThat(ticket(allowed).consultations()).singleElement().satisfies(c -> {
            assertThat(c.answeredBy().value()).isEqualTo("noe");
            assertThat(c.answer()).contains("https://junit.org/junit4/");
        });
        assertThat(sessions(allowed)).containsExactly("chris", "dehren", "noe", "dehren");
        List<String> noeActions = office.query(c -> c.actions().of(allowed).stream()
                .filter(a -> a.agent().value().equals("noe"))
                .map(a -> a.tool() + " " + a.stage())
                .toList());
        assertThat(noeActions).containsExactly("WebSearch " + ActionStage.EXECUTED);
    }
}
