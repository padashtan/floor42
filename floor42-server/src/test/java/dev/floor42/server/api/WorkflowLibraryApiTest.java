package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import dev.floor42.domain.workflow.WorkflowFile;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The workflow library (ADR-0052, increment 2): templates, versions derived from the saves, the version a mission
 * pinned, export and import. Nothing here writes a new kind of event, and an imported file saves nothing by itself.
 */
@QuarkusTest
class WorkflowLibraryApiTest {

    @Inject
    SessionToken token;

    @Inject
    Journal journal;

    RequestSpecification api() {
        return given().header("Authorization", "Bearer " + token.value()).contentType("application/json");
    }

    private static String unique(String name) {
        return name + " " + UUID.randomUUID().toString().substring(0, 6);
    }

    private static String save(String name, String steps, boolean replace) {
        return "{\"name\":\"" + name + "\",\"steps\":[" + steps + "],\"replace\":" + replace + "}";
    }

    private static final String TWO_TICKETS = "{\"step\":\"plan\",\"maxTickets\":2},{\"step\":\"approval\"},"
            + "{\"step\":\"produce\"},{\"step\":\"review\"},{\"step\":\"accept\"}";
    private static final String THREE_TICKETS = TWO_TICKETS.replace("\"maxTickets\":2", "\"maxTickets\":3");
    private static final String DECIDE = "{\"step\":\"approval\"},{\"step\":\"committee\"},{\"step\":\"accept\"}";

    private static String importBody(String text) {
        return "{\"text\":" + quote(text) + "}";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void theLibraryListsFloor42sTemplatesWithTheirSteps() {
        api().get("/api/workflows")
                .then()
                .statusCode(200)
                // The test profile opens the writer, so the templates of tasks (schema 3) are offered too.
                .body(
                        "templates.key",
                        contains(
                                "code-change",
                                "light-change",
                                "decision",
                                "document-production",
                                "research",
                                "review-only"))
                .body("templates.revision", contains(1, 1, 1, 1, 1, 1))
                .body(
                        "templates[0].workflow.steps.step",
                        equalTo(api().get("/api/workflows").path("builtIn.steps.step")))
                .body("templates[1].workflow.steps[0].maxTickets", equalTo(3))
                .body("templates[2].workflow.steps.step", contains("approval", "committee", "accept"))
                .body("templates[3].workflow.steps[4].check.title", equalTo("Proofreading"))
                .body("templates[3].workflow.steps[4].check.blocks", equalTo(false))
                .body("templates[3].workflow.steps[4].check.runBy", equalTo("reviewer"));
    }

    @Test
    void savesAreVersionsAndARestoreIsANewSaveOfAnEarlierDocument() {
        String name = unique("Versioned");
        api().body(save(name, TWO_TICKETS, false)).post("/api/workflows").then().statusCode(200);
        api().body(save(name, DECIDE, true)).post("/api/workflows").then().statusCode(200);
        Map<String, Object> first =
                api().get("/api/workflows").path("workflows.find { it.name == '" + name + "' }.versions[0].workflow");
        assertThat(first).containsEntry("name", name);

        // Restore: the first version's steps are saved again over the name, as a third version.
        long before = journal.readAll().size();
        api().body(save(name, TWO_TICKETS, true)).post("/api/workflows").then().statusCode(200);
        assertThat(journal.readAll().size() - before).as("one event, a save").isEqualTo(1);

        api().get("/api/workflows")
                .then()
                .body("workflows.find { it.name == '" + name + "' }.version", equalTo(3))
                .body("workflows.find { it.name == '" + name + "' }.versions.version", contains(1, 2, 3))
                .body(
                        "workflows.find { it.name == '" + name + "' }.versions[2].workflow.steps",
                        equalTo(api().get("/api/workflows")
                                .path("workflows.find { it.name == '" + name + "' }.versions[0].workflow.steps")));

        // A removal starts the count again.
        api().body("{\"name\":\"" + name + "\"}")
                .post("/api/workflows/remove")
                .then()
                .statusCode(200);
        api().body(save(name, DECIDE, false)).post("/api/workflows").then().statusCode(200);
        api().get("/api/workflows")
                .then()
                .body("workflows.find { it.name == '" + name + "' }.versions.version", contains(1));
    }

    @Test
    void aMissionShowsTheVersionItsPinCopiedAndNoneWhenItWasEdited() throws Exception {
        String name = unique("Followed");
        api().body(save(name, TWO_TICKETS, false)).post("/api/workflows").then().statusCode(200);
        api().body(save(name, THREE_TICKETS, true))
                .post("/api/workflows")
                .then()
                .statusCode(200);
        String repo = LiveMissionTest.gitRepository().toString().replace("\\", "\\\\");
        String id = api().body("{\"title\":\"Pinned\",\"objective\":\"o\",\"repository\":\"" + repo
                        + "\",\"plan\":true,\"workflow\":\"" + name + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        api().get("/api/missions/" + id)
                .then()
                .body("workflow.follows.name", equalTo(name))
                .body("workflow.follows.version", equalTo(2));

        // Edited since: the mission still follows the version it copied.
        api().body(save(name, DECIDE, true)).post("/api/workflows").then().statusCode(200);
        api().get("/api/missions/" + id).then().body("workflow.follows.version", equalTo(2));

        // The name removed and created again: the mission still says version 2, of a workflow that is gone.
        api().get("/api/missions/" + id).then().body("workflow.follows.removed", equalTo(false));
        api().body("{\"name\":\"" + name + "\"}")
                .post("/api/workflows/remove")
                .then()
                .statusCode(200);
        api().body(save(name, TWO_TICKETS, false)).post("/api/workflows").then().statusCode(200);
        api().get("/api/missions/" + id)
                .then()
                .body("workflow.follows.name", equalTo(name))
                .body("workflow.follows.version", equalTo(2))
                .body("workflow.follows.removed", equalTo(true));

        // A mission on Floor42's own workflow follows no company version.
        String plain = api().body(
                        "{\"title\":\"Plain\",\"objective\":\"o\",\"repository\":\"" + repo + "\",\"plan\":true}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        api().get("/api/missions/" + plain).then().body("workflow.follows", nullValue());
    }

    @Test
    void anExportedFileImportsBackAsTheSameWorkflowAndSavesNothing() {
        String name = unique("Shared");
        api().body(save(name, TWO_TICKETS, false)).post("/api/workflows").then().statusCode(200);
        Map<String, Object> exported = api().queryParam("name", name)
                .get("/api/workflows/export")
                .then()
                .statusCode(200)
                .body("format", equalTo("floor42-workflow"))
                .body("formatVersion", equalTo(1))
                .body("schemaVersion", equalTo(1))
                .body("document.name", equalTo(name))
                .extract()
                .as(new io.restassured.common.mapper.TypeRef<Map<String, Object>>() {});
        String text = new com.fasterxml.jackson.databind.ObjectMapper()
                .valueToTree(exported)
                .toString();

        long before = journal.readAll().size();
        api().body(importBody(text))
                .post("/api/workflows/import")
                .then()
                .statusCode(200)
                .body("problems.size()", equalTo(0))
                .body("workflow.name", equalTo(name))
                .body("workflow.steps[0].maxTickets", equalTo(2));
        assertThat(journal.readAll().size()).as("an import saves nothing").isEqualTo(before);
    }

    /** A workflow with a check is saved, exported and imported in schema 2, and the file is exactly what the domain writes. */
    @Test
    void aWorkflowWithChecksRoundTripsThroughExportAndImportInSchemaTwo() {
        String name = unique("Checked");
        String check = "{\"step\":\"check\",\"key\":\"sec\",\"title\":\"Security\",\"does\":\"Look\","
                + "\"why\":\"Contract\",\"handsOver\":\"Findings\",\"slot\":\"checks\",\"runBy\":\"reviewer\","
                + "\"blocks\":true}";
        String steps = TWO_TICKETS.replace("{\"step\":\"accept\"}", check + ",{\"step\":\"accept\"}");
        api().body(save(name, steps, false)).post("/api/workflows").then().statusCode(200);
        io.restassured.response.Response response =
                api().queryParam("name", name).get("/api/workflows/export");
        response.then().statusCode(200).body("schemaVersion", equalTo(2));
        assertThat(response.getContentType()).startsWithIgnoringCase("application/json");
        assertThat(response.getContentType().toLowerCase()).contains("charset=utf-8");
        String body = response.getBody().asString();
        dev.floor42.domain.workflow.Workflow saved = dev.floor42.domain.workflow.WorkflowDocument.readCurrent(
                "{\"name\":\"" + name + "\",\"steps\":[" + steps + "]}");
        assertThat(body)
                .isEqualTo(WorkflowFile.export(
                        dev.floor42.domain.workflow.WorkflowDocument.write(saved),
                        dev.floor42.domain.workflow.WorkflowDocument.versionOf(saved)));
        api().body(importBody(body))
                .post("/api/workflows/import")
                .then()
                .statusCode(200)
                .body("problems.size()", equalTo(0))
                .body("workflow.name", equalTo(name))
                .body("workflow.steps.step", contains("plan", "approval", "produce", "review", "check", "accept"))
                .body("workflow.steps[4].check.key", equalTo("sec"));
    }

    /** A workflow with a task is saved, exported and imported in schema 3, and the file is exactly what the domain writes. */
    @Test
    void aWorkflowWithATaskRoundTripsThroughExportAndImportInSchemaThree() {
        String name = unique("Tasked");
        String task = "{\"step\":\"task\",\"key\":\"look\",\"title\":\"Look\",\"does\":\"Read\","
                + "\"why\":\"Facts\",\"handsOver\":\"Notes\",\"slot\":\"beforeAcceptance\",\"runBy\":\"researcher\","
                + "\"reviewed\":false}";
        String steps = TWO_TICKETS.replace("{\"step\":\"accept\"}", task + ",{\"step\":\"accept\"}");
        api().body(save(name, steps, false)).post("/api/workflows").then().statusCode(200);
        io.restassured.response.Response response =
                api().queryParam("name", name).get("/api/workflows/export");
        response.then().statusCode(200).body("schemaVersion", equalTo(3));
        String body = response.getBody().asString();
        dev.floor42.domain.workflow.Workflow saved = dev.floor42.domain.workflow.WorkflowDocument.readCurrent(
                "{\"name\":\"" + name + "\",\"steps\":[" + steps + "]}");
        assertThat(body)
                .isEqualTo(WorkflowFile.export(
                        dev.floor42.domain.workflow.WorkflowDocument.write(saved),
                        dev.floor42.domain.workflow.WorkflowDocument.versionOf(saved)));
        api().body(importBody(body))
                .post("/api/workflows/import")
                .then()
                .statusCode(200)
                .body("problems.size()", equalTo(0))
                .body("workflow.name", equalTo(name))
                .body("workflow.steps.step", contains("plan", "approval", "produce", "review", "task", "accept"))
                .body("workflow.steps[4].task.key", equalTo("look"));
    }

    @Test
    void aBadFileIsRefusedByCodeWithEveryProblemAndTheNamesInItNeverEcho() {
        String evil = "{\"format\":\"floor42-workflow\",\"formatVersion\":1,\"schemaVersion\":1,\"document\":"
                + "{\"name\":\"x\",\"steps\":[{\"step\":\"approval\"},{\"step\":\"rm -rf\"},{\"step\":\"produce\"}]}}";
        api().body(importBody(evil))
                .post("/api/workflows/import")
                .then()
                .statusCode(200)
                .body("workflow", nullValue())
                .body("problems.code", contains("IMPORT_STEP_UNKNOWN"))
                .body("problems[0].subject", nullValue());
        api().body(importBody(
                        "{\"format\":\"floor42-workflow\",\"formatVersion\":1,\"schemaVersion\":9,\"document\":{}}"))
                .post("/api/workflows/import")
                .then()
                .body("problems.code", contains("IMPORT_SCHEMA_NEWER"))
                .body("problems[0].subject", equalTo("9"));
        api().body(importBody("not json"))
                .post("/api/workflows/import")
                .then()
                .body("problems.code", contains("IMPORT_NOT_JSON"));
        api().body(importBody("x".repeat(70_000)))
                .post("/api/workflows/import")
                .then()
                .body("problems.code", contains("IMPORT_TOO_LARGE"));
        api().body("{}").post("/api/workflows/import").then().body("problems.code", contains("IMPORT_EMPTY"));
    }

    @Test
    void anUnknownNameIsNotExported() {
        api().queryParam("name", "Nobody wrote it")
                .get("/api/workflows/export")
                .then()
                .statusCode(404)
                .body("code", equalTo("UNKNOWN_WORKFLOW"));
    }

    @Test
    void aRemovedNameIsNotExported() {
        String name = unique("Removed");
        api().body(save(name, TWO_TICKETS, false)).post("/api/workflows").then().statusCode(200);
        api().queryParam("name", name).get("/api/workflows/export").then().statusCode(200);
        api().body("{\"name\":\"" + name + "\"}")
                .post("/api/workflows/remove")
                .then()
                .statusCode(200);
        api().queryParam("name", name)
                .get("/api/workflows/export")
                .then()
                .statusCode(404)
                .body("code", equalTo("UNKNOWN_WORKFLOW"));
    }

    @Test
    void anImportBodyPastTheCapIsRefusedAtTheBoundaryWith413() {
        long before = journal.readAll().size();
        api().body(importBody("x".repeat(WorkflowsResource.IMPORT_BODY_LIMIT)))
                .post("/api/workflows/import")
                .then()
                .statusCode(413)
                .body("code", equalTo("IMPORT_TOO_LARGE"));
        assertThat(journal.readAll().size()).isEqualTo(before);
    }

    /**
     * A file at the cap made only of control characters: each is escaped in six bytes in the request (a backslash, a u
     * and four digits), so the body is six times the file, and it still reaches the domain's own checks instead of a 413.
     */
    @Test
    void aFileOfControlCharactersWithinTheCapReachesTheDomainsChecks() {
        String escaped = "\\" + "u0001";
        String body = "{\"text\":\"" + escaped.repeat(WorkflowFile.MAX_BYTES) + "\"}";
        assertThat(body.length())
                .isGreaterThan(2 * WorkflowFile.MAX_BYTES)
                .isLessThanOrEqualTo(WorkflowsResource.IMPORT_BODY_LIMIT);
        api().body(body)
                .post("/api/workflows/import")
                .then()
                .statusCode(200)
                .body("workflow", nullValue())
                // The domain's own cap on the file's characters answers, not the request's boundary.
                .body("problems.code", contains("IMPORT_TOO_LONG"));
    }
}
