package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.AuthorizationTuple;
import dev.floor42.domain.MissionId;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.iam.McpServers.Listed;
import dev.floor42.server.iam.OfficeFacts.AuthorizationChange;
import dev.floor42.server.iam.ResourceCatalogue.Resource;
import dev.floor42.server.iam.fga.FgaTuple;
import dev.floor42.server.iam.fga.GrantImport;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every change of the broker authorization is one journal row, ids only; start finds what no row recorded. */
class AuthorizationChangedTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MissionId M = new MissionId("M-1");
    private static final AgentId ADA = new AgentId("ada");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}");

    @TempDir
    Path dir;

    TestGrants grants;
    Delegations delegations;
    ResourceCatalogue catalogue;
    McpServers servers;
    GrantImport start;

    @BeforeEach
    void start() {
        delegations = new Delegations(
                dir.resolve("iam").resolve("delegations.json"),
                new SecretVault(dir),
                null,
                () -> true,
                Optional::empty,
                null,
                JSON);
        grants = new TestGrants();
        delegations.listen(grants.tuples);
        catalogue = new ResourceCatalogue(dir, JSON, delegations, grants.tuples);
        catalogue.put(new Resource(
                "demo",
                URI.create("https://demo.example.test"),
                "aud",
                List.of(),
                List.of("POST"),
                List.of("/mcp"),
                1000,
                false));
        servers = new McpServers(dir, JSON, catalogue, grants.tuples);
        start = new GrantImport(dir.resolve("iam"), JSON, grants.tuples, servers, catalogue, delegations);
    }

    private void delegate() {
        delegations.grant(M, new Tokens("a", "refresh", null, Instant.now().plusSeconds(60)), List.of("demo"));
    }

    @Test
    void everyWriteIsOneRow() {
        catalogue.grant(ADA, List.of("demo"));
        delegate();
        servers.put("demo", "/mcp");
        servers.pin("demo", List.of(new Listed("echo", "Echoes", JSON.createObjectNode())), Instant.now());
        McpTicks.tick(servers, "demo", "echo");
        delegations.revokeLocally(M, "QUARANTINE");

        assertThat(grants.rows)
                .extracting(AuthorizationChange::reason)
                .containsExactly("GRANT", "DELEGATION", "MCP_TICK", "QUARANTINE");
        assertThat(grants.rows.get(0).writes())
                .containsExactly(new AuthorizationTuple("agent:ada", "granted", "resource:demo"));
        assertThat(grants.rows.get(1).writes())
                .containsExactly(new AuthorizationTuple("mission:M-1", "delegated", "resource:demo"));
        assertThat(grants.rows.get(3).deletes())
                .containsExactly(new AuthorizationTuple("mission:M-1", "delegated", "resource:demo"));
        assertThat(grants.rows).allSatisfy(r -> assertThat(r.modelId()).isEqualTo("01TESTMODEL"));
    }

    @Test
    void moreThanAHundredTuplesAreSplitIntoRows() {
        for (int i = 0; i < 130; i++) grants.tuples.granted(new MissionId("M-" + i), List.of("demo"));
        assertThat(grants.rows).hasSize(130);
        grants.rows.clear();
        grants.tuples.importTuples(java.util.stream.IntStream.range(0, 250)
                .mapToObj(i -> new FgaTuple("agent:a" + i, "granted", "resource:demo"))
                .toList());
        assertThat(grants.rows).extracting(r -> r.writes().size()).containsExactly(100, 100, 50);
    }

    @Test
    void aTupleWithoutARowIsReconciledAtStart() {
        catalogue.grant(ADA, List.of("demo"));
        grants.rows.clear();
        // behind Floor42's back, or a crash between the OpenFGA write and its journal row
        grants.fga.write(
                List.of(new FgaTuple("agent:bob", "granted", "resource:demo")),
                List.of(new FgaTuple("agent:ada", "granted", "resource:demo")));

        start.reconcile();

        assertThat(grants.rows).hasSize(1);
        AuthorizationChange row = grants.rows.getFirst();
        assertThat(row.reconciled()).isTrue();
        assertThat(row.reason()).isEqualTo("RECONCILE");
        assertThat(row.writes()).containsExactly(new AuthorizationTuple("agent:bob", "granted", "resource:demo"));
        assertThat(row.deletes()).containsExactly(new AuthorizationTuple("agent:ada", "granted", "resource:demo"));

        grants.rows.clear();
        start.reconcile();
        assertThat(grants.rows)
                .as("idempotent: the journal now agrees with OpenFGA")
                .isEmpty();
    }

    @Test
    void aDelegationOfAMissionThatNoLongerRunsIsDeletedAtStart() {
        grants.fga.write(List.of(new FgaTuple("mission:M-9", "delegated", "resource:demo")), List.of());
        start.reconcile();
        assertThat(grants.fga.stored()).noneMatch(t -> t.relation().equals("delegated"));
        assertThat(grants.rows).anyMatch(r -> r.reconciled() && !r.deletes().isEmpty());
    }

    @Test
    void aRunningDelegationOpenFgaLostIsWrittenAtStart() {
        grants.fga.unavailable(true);
        delegate();
        grants.fga.unavailable(false);
        start.reconcile();
        assertThat(grants.fga.stored()).contains(new FgaTuple("mission:M-1", "delegated", "resource:demo"));
    }

    @Test
    void noTextLooksLikeAJwt() throws Exception {
        catalogue.grant(ADA, List.of("demo"));
        delegate();
        servers.put("demo", "/mcp");
        servers.pin("demo", List.of(new Listed("echo", "Echoes", JSON.createObjectNode())), Instant.now());
        McpTicks.tick(servers, "demo", "echo");
        start.reconcile();
        String all = JSON.writeValueAsString(grants.rows);
        assertThat(all).isNotBlank();
        assertThat(JWT.matcher(all).find()).isFalse();
        assertThat(all).doesNotContain("refresh").doesNotContain("Bearer");
        assertThat(Map.of("rows", grants.rows.size())).containsKey("rows");
    }
}
