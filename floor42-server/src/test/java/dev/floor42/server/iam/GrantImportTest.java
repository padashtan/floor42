package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.iam.McpServers.Listed;
import dev.floor42.server.iam.McpServers.McpServer;
import dev.floor42.server.iam.McpServers.McpTool;
import dev.floor42.server.iam.ResourceCatalogue.Resource;
import dev.floor42.server.iam.fga.FgaTuple;
import dev.floor42.server.iam.fga.GrantImport;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The first start of 1.3.0 in IAM mode imports grants.json and the MCP ticks into OpenFGA once, journals it, and renames
 * the file; a downgrade finds no grants.json and fails closed (ADR-0080).
 */
class GrantImportTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MissionId M = new MissionId("M-1");

    @TempDir
    Path dir;

    TestGrants grants;
    Delegations delegations;
    ResourceCatalogue catalogue;
    McpServers servers;
    GrantImport importer;
    Path iam;

    @BeforeEach
    void start() throws Exception {
        iam = dir.resolve("iam");
        delegations = new Delegations(
                iam.resolve("delegations.json"), new SecretVault(dir), null, () -> true, Optional::empty, null, JSON);
        grants = new TestGrants();
        delegations.listen(grants.tuples);
        catalogue = new ResourceCatalogue(dir, JSON, delegations, grants.tuples);
        catalogue.put(new Resource(
                "mcp-demo",
                URI.create("https://mcp.example.test"),
                "mcp-api",
                List.of(),
                List.of("POST"),
                List.of("/mcp"),
                1000,
                false));
        servers = new McpServers(dir, JSON, catalogue, grants.tuples);
        servers.put("mcp-demo", "/mcp");
        servers.pin("mcp-demo", List.of(new Listed("echo", "Echoes", JSON.createObjectNode())), Instant.now());
        // What 1.2.0 wrote: a grants file, and a ticked tool in mcp-servers.json
        Files.writeString(iam.resolve("grants.json"), "{\"ada\":[\"mcp-demo\"]}");
        McpServer s = servers.find("mcp-demo").orElseThrow();
        McpTool t = s.tools().getFirst();
        McpServer old = new McpServer(
                s.resource(),
                s.path(),
                s.baseUrl(),
                s.audience(),
                s.pinnedAt(),
                List.of(new McpTool(t.name(), t.description(), t.inputSchema(), t.sha256(), true, false)));
        Files.writeString(iam.resolve("mcp-servers.json"), JSON.writeValueAsString(List.of(old)));
        importer = new GrantImport(iam, JSON, grants.tuples, servers, catalogue, delegations);
    }

    @Test
    void theImportRunsOnceAndRenamesTheFile() throws Exception {
        delegations.grant(M, new Tokens("a", "refresh", null, Instant.now().plusSeconds(60)), List.of("mcp-demo"));
        grants.rows.clear();
        String sha = servers.find("mcp-demo").orElseThrow().tools().getFirst().sha256();

        assertThat(importer.importFiles()).isTrue();

        assertThat(grants.fga.stored())
                .contains(
                        new FgaTuple("agent:ada", "granted", "resource:mcp-demo"),
                        new FgaTuple("agent:*", "enabled", "mcp_tool:mcp-demo/echo/" + sha),
                        new FgaTuple("resource:mcp-demo", "server", "mcp_tool:mcp-demo/echo/" + sha),
                        new FgaTuple("mission:M-1", "delegated", "resource:mcp-demo"));
        assertThat(grants.rows).hasSize(1);
        assertThat(grants.rows.getFirst().reason()).isEqualTo("IMPORT");
        assertThat(grants.rows.getFirst().imported()).isTrue();
        assertThat(iam.resolve("grants.json")).doesNotExist();
        assertThat(iam.resolve("grants.json.imported-1.3.0")).exists();
        assertThat(Files.readString(iam.resolve("mcp-servers.json")))
                .as("the file keeps the definitions only")
                .doesNotContain("\"ticked\" : true")
                .doesNotContain("\"ticked\":true");

        grants.rows.clear();
        assertThat(importer.importFiles()).as("already imported").isFalse();
        assertThat(grants.rows).isEmpty();
        assertThat(catalogue.grantsOf()).containsEntry("ada", List.of("mcp-demo"));
        assertThat(servers.find("mcp-demo").orElseThrow().tools().getFirst().ticked())
                .isTrue();
    }

    @Test
    void aRunThatStoppedBeforeTheRenameWritesAndJournalsNothingTwice() throws Exception {
        importer.importFiles();
        // the rename never happened
        Files.move(iam.resolve("grants.json.imported-1.3.0"), iam.resolve("grants.json"));
        grants.rows.clear();
        importer.importFiles();
        assertThat(grants.rows).isEmpty();
        assertThat(iam.resolve("grants.json")).doesNotExist();
    }

    @Test
    void aDowngradeFindsNoGrantsFile() throws Exception {
        importer.importFiles();
        // 1.2.0 reads grants.json: it is not there, so no agent holds anything and every resource call is refused
        assertThat(Files.exists(iam.resolve("grants.json"))).isFalse();
        // and the release note's way back: the file renamed to its name restores the 1.2.0 grants
        assertThat(Files.readString(iam.resolve("grants.json.imported-1.3.0"))).contains("mcp-demo");
    }

    @Test
    void aFileThatCannotBeReadIsLeftAloneAndNothingIsImported() throws Exception {
        Files.writeString(iam.resolve("grants.json"), "[1,");
        assertThat(importer.importFiles()).isFalse();
        assertThat(iam.resolve("grants.json")).exists();
        assertThat(grants.fga.stored()).isEmpty();
    }

    @Test
    void anAgentOfTheFileIsAGrantTupleAndNotAnotherAgents() {
        importer.importFiles();
        assertThat(grants.tuples.allowed(new AgentId("bob"), M, "mcp-demo")).isFalse();
    }

    @Test
    void aGrantTakenBackIsNotBroughtBackByAFileLeftBehind() throws Exception {
        importer.importFiles();
        catalogue.grant(new AgentId("ada"), List.of());
        // the rename never happened, then the daemon restarts
        Files.writeString(iam.resolve("grants.json"), "{\"ada\":[\"mcp-demo\"]}");
        assertThat(importer.importFiles()).isFalse();
        assertThat(catalogue.grantsOf().getOrDefault("ada", List.of())).isEmpty();
        assertThat(iam.resolve("grants.json")).doesNotExist();
    }

    @Test
    void aClosedMissionsDelegationIsNeitherWrittenBackByTheReconcilerNorHealed() throws Exception {
        delegations.grant(M, new Tokens("a", "refresh", null, Instant.now().plusSeconds(60)), List.of("mcp-demo"));
        catalogue.grant(new AgentId("ada"), List.of("mcp-demo"));
        FgaTuple delegated = new FgaTuple("mission:M-1", "delegated", "resource:mcp-demo");
        assertThat(grants.fga.stored()).contains(delegated);

        delegations.closeToWork(M);
        assertThat(grants.fga.stored()).doesNotContain(delegated);
        importer.reconcile();
        assertThat(grants.fga.stored()).as("the reconciler").doesNotContain(delegated);
        assertThat(catalogue.allowed(new AgentId("ada"), M, "mcp-demo"))
                .as("the heal")
                .isFalse();
        assertThat(grants.fga.stored()).doesNotContain(delegated);
    }

    @Test
    void aJournalRowThatCannotBeWrittenIsToldAndRetried() {
        grants.failJournal = true;
        catalogue.grant(new AgentId("ada"), List.of("mcp-demo"));
        assertThat(grants.fga.stored()).contains(new FgaTuple("agent:ada", "granted", "resource:mcp-demo"));
        assertThat(grants.tuples.unjournaled()).isEqualTo(1);
        grants.tuples.retryJournal();
        assertThat(grants.tuples.unjournaled()).as("still failing").isEqualTo(1);

        grants.failJournal = false;
        grants.tuples.retryJournal();
        assertThat(grants.tuples.unjournaled()).isZero();
        assertThat(grants.rows).extracting(r -> r.reason()).contains("GRANT");
    }
}
