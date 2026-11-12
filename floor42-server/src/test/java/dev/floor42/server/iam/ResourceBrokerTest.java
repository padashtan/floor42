package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.floor42.server.iam.ResourceBroker.BrokerResult;
import dev.floor42.server.iam.ResourceBroker.Refusal;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** The broker against a local HTTP server as the resource and the fake Keycloak as the issuer. */
@QuarkusTest
@TestProfile(IamSessionProfile.class)
class ResourceBrokerTest extends BrokerTestBase {

    @Inject
    ResourceBroker broker;

    private static void refusedWith(String code, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(Refusal.class, r -> assertThat(r.code()).isEqualTo(code));
    }

    @Test
    void aRefusedMethodOrPathSaysWhatWasRefusedAndEchoesNoQueryOrBody() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));

        assertThatThrownBy(() -> broker.call(claims(s), RESOURCE, "DELETE", "/api/x", "{\"secret\":1}"))
                .hasMessage("DELETE is not allowed for " + RESOURCE);
        assertThatThrownBy(() -> broker.call(claims(s), RESOURCE, "GET", "/admin/users?token=abc", null))
                .hasMessage("/admin/users is outside the paths allowed for " + RESOURCE);
    }

    @Test
    void callsTheResourceWithAShortTokenForExactlyItsAudience() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        BrokerResult r = broker.call(claims(s), RESOURCE, "get", "/api/users?first=0", null);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"ok\":true}");
        assertThat(r.audience()).isEqualTo(AUDIENCE);
        assertThat(hits).containsExactly("GET /api/users?first=0");
        var exchange = IamOnProfile.fake.requests().stream()
                .filter(f -> AUDIENCE.equals(f.get("audience")))
                .toList();
        assertThat(exchange).hasSize(1);
        assertThat(exchange.getFirst()).containsEntry("scope", "read");
        String sent = authorizations.getFirst().substring("Bearer ".length());
        var claims = Jwts.parse(sent).claims();
        assertThat(claims.path("aud").asText()).isEqualTo(AUDIENCE);
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isLessThanOrEqualTo(300);
        assertThat(claims.path("jti").asText()).isEqualTo(r.jti());
    }

    @Test
    void exchangedTokenIsNeverInTheResultTheLogsOrTheJournal() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        BrokerResult r = broker.call(claims(s), RESOURCE, "GET", "/api/leak", null);

        String sent = authorizations.getFirst().substring("Bearer ".length());
        assertThat(r.body()).doesNotContain(sent).doesNotContain("eyJ").contains("[redacted]");
        assertThat(r.toString()).doesNotContain("eyJ");
        assertThat(r.headers().toString()).doesNotContain("eyJ");
        assertThat(journalText()).doesNotContain("eyJ");
    }

    @Test
    void redirectToAnotherHostIsNotFollowed() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        BrokerResult r = broker.call(claims(s), RESOURCE, "GET", "/api/redirect", null);

        assertThat(r.status()).isEqualTo(302);
        assertThat(r.headers()).doesNotContainKey("location");
        assertThat(hits).containsExactly("GET /api/redirect");
    }

    @Test
    void responseIsCappedAndSaysSo() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        BrokerResult r = broker.call(claims(s), RESOURCE, "GET", "/api/big", null);

        assertThat(r.truncated()).isTrue();
        assertThat(r.body()).hasSize(1000);
    }

    @Test
    void noCookieIsKept() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        broker.call(claims(s), RESOURCE, "GET", "/api/cookie", null);
        BrokerResult r = broker.call(claims(s), RESOURCE, "GET", "/api/cookie-check", null);

        assertThat(r.body()).isEqualTo("cookie=null");
    }

    @Test
    void needsBothAgentAndMissionGrant() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of());
        refusedWith("RESOURCE_NOT_GRANTED", () -> broker.call(claims(s), RESOURCE, "GET", "/api/x", null));
        catalogue.grant(CHRIS, List.of(RESOURCE));
        delegations.revokeLocally(s.mission());
        refusedWith("RESOURCE_NOT_GRANTED", () -> broker.call(claims(s), RESOURCE, "GET", "/api/x", null));
        Setup other = brokerSetup(List.of(), List.of(RESOURCE));
        refusedWith("RESOURCE_NOT_GRANTED", () -> broker.call(claims(other), RESOURCE, "GET", "/api/x", null));
        assertThat(hits).isEmpty();
    }

    @Test
    void unknownResourceAndUnsafePathsAreRefusedBeforeAnyToken() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        int exchanges = IamOnProfile.fake.requests().size();
        refusedWith("RESOURCE_UNKNOWN", () -> broker.call(claims(s), "nope", "GET", "/api/x", null));
        refusedWith("PATH_NOT_ALLOWED", () -> broker.call(claims(s), RESOURCE, "GET", "/api/../admin", null));
        refusedWith("METHOD_NOT_ALLOWED", () -> broker.call(claims(s), RESOURCE, "DELETE", "/api/x", null));
        assertThat(IamOnProfile.fake.requests()).hasSize(exchanges);
        assertThat(hits).isEmpty();
    }

    @Test
    void aTokenThatIsNotWhatWasAskedForIsNeverUsed() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        String base = IamOnProfile.fake.exchangeForTest(
                "floor42-agent-chris", IamOnProfile.fake.directorToken("director"), Map.of("audience", AUDIENCE));
        Map<String, Consumer<ObjectNode>> bad = Map.of(
                "two audiences", b -> ((ArrayNode) b.putArray("aud").add(AUDIENCE)).add("floor42-api"),
                "the API audience", b -> b.put("aud", "floor42-api"),
                "an hour of life", b -> b.put("exp", b.path("iat").asLong() + 3600),
                "another agent", b -> ((ObjectNode) b.path("act")).put("client_id", "floor42-agent-mallory"),
                "no act", b -> b.remove("act"));
        bad.forEach((name, edit) -> {
            String forged = IamOnProfile.fake.forge(base, edit);
            ResourceBroker forgedBroker = new ResourceBroker(
                    catalogue, new SafeUrls(), new PinnedClients(), java.time.Duration.ofSeconds(30), (c, r) -> forged);
            assertThatThrownBy(() -> forgedBroker.call(claims(s), RESOURCE, "GET", "/api/x", null))
                    .as(name)
                    .isInstanceOfSatisfying(
                            Refusal.class, r -> assertThat(r.code()).isEqualTo("IDENTITY_UNAVAILABLE"))
                    .message()
                    .doesNotContain("eyJ");
        });
        assertThat(hits).isEmpty();
    }

    @Test
    void keycloakDownRefusesTheCall() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        IamOnProfile.fake.down(true);
        try {
            refusedWith("IDENTITY_UNAVAILABLE", () -> broker.call(claims(s), RESOURCE, "GET", "/api/x", null));
        } finally {
            IamOnProfile.fake.down(false);
        }
        assertThat(hits).isEmpty();
    }

    @Test
    void aServerThatDripsItsBodyIsCutAtTheDeadline() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        String token = IamOnProfile.fake.exchangeForTest(
                "floor42-agent-chris", IamOnProfile.fake.directorToken("director"), Map.of("audience", AUDIENCE));
        ResourceBroker slow = new ResourceBroker(
                catalogue, new SafeUrls(), new PinnedClients(), java.time.Duration.ofSeconds(1), (c, r) -> token);
        long begin = System.nanoTime();
        refusedWith("RESOURCE_UNREACHABLE", () -> slow.call(claims(s), RESOURCE, "GET", "/api/drip", null));
        assertThat((System.nanoTime() - begin) / 1_000_000).isLessThan(5000);
    }

    @Test
    void aFailureInTheVaultOrTheExchangeIsAnIdentityRefusalNotACrash() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        // the agent has a delegation but no secret in the vault under this name
        vault.delete(KeycloakClient.vaultName(CHRIS));
        refusedWith("IDENTITY_UNAVAILABLE", () -> broker.call(claims(s), RESOURCE, "GET", "/api/x", null));
    }

    @Test
    void removingAResourceRemovesItsGrantsAndTheCatalogueTakesOriginsOnly() throws Exception {
        brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        catalogue.remove(RESOURCE);
        assertThat(catalogue.grantsOf().get(CHRIS.value())).doesNotContain(RESOURCE);
        var base = catalogue.find(RESOURCE);
        assertThat(base).isEmpty();
        var withPath = new ResourceCatalogue.Resource(
                "p",
                java.net.URI.create("https://kc.example.test/admin"),
                "a",
                List.of(),
                List.of("GET"),
                List.of("/"),
                10,
                false);
        var plainHttp = new ResourceCatalogue.Resource(
                "q",
                java.net.URI.create("http://kc.example.test"),
                "a",
                List.of(),
                List.of("GET"),
                List.of("/"),
                10,
                false);
        assertThatThrownBy(() -> catalogue.put(withPath)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalogue.put(plainHttp)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCompletedMissionGetsNoResourceToken() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        delegations.closeToWork(s.mission());
        refusedWith("IDENTITY_REFUSED", () -> broker.call(claims(s), RESOURCE, "GET", "/api/x", null));
        assertThat(hits).isEmpty();
    }
}
