package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.iam.ResourceBroker.BrokerResult;
import dev.floor42.server.iam.ResourceBroker.Refusal;
import dev.floor42.server.iam.ResourceCatalogue.Resource;
import dev.floor42.server.iam.fga.EvaluatingFga.Check;
import dev.floor42.server.iam.fga.FgaException;
import dev.floor42.server.iam.fga.FgaTuple;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OpenFGA decides at the broker (ADR-0080, S1): the grant AND the mission's delegation, the session's agent and mission as
 * the contextual {@code runner}, fail closed, and a revocation that bites before any network call. The OpenFGA here is
 * the in-memory one that evaluates the model's {@code can_call}.
 */
class FgaBrokerTest {

    private static final MissionId M = new MissionId("M-1");
    private static final MissionId OTHER = new MissionId("M-2");
    private static final AgentId ADA_ID = new AgentId("ada");
    private static final SessionClaims ADA =
            new SessionClaims("11111111-1111-1111-1111-111111111111", ADA_ID, M, new TicketId("T-1"));

    @TempDir
    Path dir;

    FakeKeycloak kc;
    HttpServer server;
    TestGrants grants;
    Delegations delegations;
    ResourceCatalogue catalogue;
    ResourceBroker broker;

    @BeforeEach
    void start() throws Exception {
        kc = FakeKeycloak.start("floor42");
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/", x -> {
            byte[] b = "ok".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        server.start();
        ObjectMapper json = new ObjectMapper();
        SecretVault vault = new SecretVault(dir);
        delegations = new Delegations(
                dir.resolve("iam").resolve("delegations.json"),
                vault,
                new KeycloakClient(kc.issuer().toString(), vault, json),
                () -> true,
                Optional::empty,
                null,
                json);
        grants = new TestGrants();
        delegations.listen(grants.tuples);
        catalogue = new ResourceCatalogue(dir, json, delegations, grants.tuples);
        catalogue.put(new Resource(
                "demo-api",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "target-api",
                List.of(),
                List.of("GET"),
                List.of("/api/"),
                1000,
                true));
        broker = new ResourceBroker(
                catalogue, new SafeUrls(), new PinnedClients(), Duration.ofSeconds(2), (c, r) -> token());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        kc.close();
    }

    private String token() {
        return kc.forge(
                kc.exchangeForTest("floor42-agent-ada", kc.directorToken("director"), Map.of("audience", "target-api")),
                b -> {});
    }

    private void delegate(MissionId m, String... resources) {
        delegations.grant(m, new Tokens("a", "refresh", null, Instant.now().plusSeconds(60)), List.of(resources));
    }

    private Refusal refused() {
        Throwable t = catchThrowable(() -> broker.call(ADA, "demo-api", "GET", "/api/x", null));
        assertThat(t).isInstanceOf(Refusal.class);
        return (Refusal) t;
    }

    @Test
    void aGrantWithTheMissionsDelegationIsAllowedAndOpenFgaWasAskedWithTheRunnerTuple() {
        catalogue.grant(ADA_ID, List.of("demo-api"));
        delegate(M, "demo-api");
        BrokerResult r = broker.call(ADA, "demo-api", "GET", "/api/x", null);
        assertThat(r.status()).isEqualTo(200);
        Check check = grants.fga.checks().getLast();
        assertThat(check.user()).isEqualTo("agent:ada");
        assertThat(check.relation()).isEqualTo("can_call");
        assertThat(check.object()).isEqualTo("resource:demo-api");
        assertThat(check.contextual()).containsExactly(new FgaTuple("agent:ada", "runner", "mission:M-1"));
    }

    @Test
    void aGrantWithoutTheMissionsDelegationIsRefused() {
        catalogue.grant(ADA_ID, List.of("demo-api"));
        delegate(M);
        assertThat(refused().code()).isEqualTo("RESOURCE_NOT_GRANTED");
    }

    @Test
    void aDelegationOfAnotherMissionDoesNotCount() {
        catalogue.grant(ADA_ID, List.of("demo-api"));
        delegate(M);
        delegate(OTHER, "demo-api");
        assertThat(grants.fga.stored()).contains(new FgaTuple("mission:M-2", "delegated", "resource:demo-api"));
        assertThat(refused().code()).isEqualTo("RESOURCE_NOT_GRANTED");
    }

    @Test
    void aDelegationWithoutTheAgentsGrantIsRefused() {
        delegate(M, "demo-api");
        assertThat(refused().code()).isEqualTo("RESOURCE_NOT_GRANTED");
    }

    @Test
    void fgaUnreachableRefusesTheCall() {
        catalogue.grant(ADA_ID, List.of("demo-api"));
        delegate(M, "demo-api");
        grants.fga.unavailable(true);
        Refusal r = refused();
        assertThat(r.code()).isEqualTo("FGA_UNAVAILABLE");
        assertThat(r.getMessage()).doesNotContain("RESOURCE_NOT_GRANTED");
    }

    @Test
    void removingAResourceWithOpenFgaOutRemovesNothing() {
        catalogue.grant(ADA_ID, List.of("demo-api"));
        grants.fga.unavailable(true);
        assertThatThrownBy(() -> catalogue.remove("demo-api")).isInstanceOf(FgaException.class);
        grants.fga.unavailable(false);
        assertThat(catalogue.find("demo-api")).isPresent();
        assertThat(catalogue.grantsOf().get("ada")).containsExactly("demo-api");
    }

    @Test
    void removingAResourceForgetsItsGrantsSoARecreatedOneStartsEmpty() {
        Resource demo = catalogue.find("demo-api").orElseThrow();
        catalogue.grant(ADA_ID, List.of("demo-api"));
        catalogue.remove("demo-api");
        assertThat(catalogue.find("demo-api")).isEmpty();
        catalogue.put(demo);
        assertThat(catalogue.grantsOf().getOrDefault("ada", List.of())).isEmpty();
    }

    @Test
    void aRevokedDelegationIsRefusedBeforeFga() {
        catalogue.grant(ADA_ID, List.of("demo-api"));
        delegate(M, "demo-api");
        delegations.revokeLocally(M);
        grants.fga.checks().clear();
        assertThat(refused().code()).isEqualTo("RESOURCE_NOT_GRANTED");
        assertThat(grants.fga.checks())
                .as("no OpenFGA call: a revocation needs no network to bite")
                .isEmpty();
    }

    @Test
    void aRevokedDelegationLosesItsTuplesAndAFailedDeleteCannotKeepItAlive() {
        catalogue.grant(ADA_ID, List.of("demo-api"));
        delegate(M, "demo-api");
        grants.fga.unavailable(true);
        delegations.revokeLocally(M);
        grants.fga.unavailable(false);
        assertThat(grants.fga.stored())
                .as("the delete never reached OpenFGA")
                .contains(new FgaTuple("mission:M-1", "delegated", "resource:demo-api"));
        assertThat(refused().code()).isEqualTo("RESOURCE_NOT_GRANTED");
    }

    @Test
    void aDeletedTupleRefusesTheNextCall() {
        catalogue.grant(ADA_ID, List.of("demo-api"));
        delegate(M, "demo-api");
        assertThat(broker.call(ADA, "demo-api", "GET", "/api/x", null).status()).isEqualTo(200);
        catalogue.grant(ADA_ID, List.of());
        assertThat(refused().code()).isEqualTo("RESOURCE_NOT_GRANTED");
    }

    @Test
    void aDelegationOpenFgaMissedIsWrittenOnceFromTheLocalRecord() {
        catalogue.grant(ADA_ID, List.of("demo-api"));
        grants.fga.unavailable(true);
        delegate(M, "demo-api");
        grants.fga.unavailable(false);
        assertThat(grants.fga.stored()).noneMatch(t -> t.relation().equals("delegated"));
        assertThat(broker.call(ADA, "demo-api", "GET", "/api/x", null).status()).isEqualTo(200);
        assertThat(grants.fga.stored()).contains(new FgaTuple("mission:M-1", "delegated", "resource:demo-api"));
    }

    @Test
    void theBasisNamesTheModel() {
        catalogue.grant(ADA_ID, List.of("demo-api"));
        delegate(M, "demo-api");
        assertThat(broker.call(ADA, "demo-api", "GET", "/api/x", null).basis()).isEqualTo("fga:01TESTMODEL");
        catalogue.grant(ADA_ID, List.of());
        assertThat(refused().getMessage()).contains("fga:01TESTMODEL");
    }

    @Test
    void everyChangeIsOneRowAndTheListCoversNoMoreThanTheCatalogue() {
        catalogue.grant(ADA_ID, List.of("demo-api"));
        assertThat(grants.rows).hasSize(1);
        assertThat(grants.rows.getFirst().reason()).isEqualTo("GRANT");
        assertThat(catalogue.grantsOf()).containsEntry("ada", List.of("demo-api"));
        catalogue.grant(ADA_ID, List.of("demo-api"));
        assertThat(grants.rows)
                .as("the same grant again changes nothing and journals nothing")
                .hasSize(1);
    }
}
