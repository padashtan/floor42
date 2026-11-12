package dev.floor42.server.iam;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.AgentId;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A dismissal or a revoke cuts the agent off locally at once; Keycloak's side is retried until it holds. */
class AgentClientsDismissalTest {

    private static final AgentId ADA = new AgentId("ada");
    private static final AgentId BOB = new AgentId("bob");

    @TempDir
    Path dataDir;

    FakeKeycloak kc;
    KeycloakClient keycloak;
    AgentClients clients;

    @BeforeEach
    void start() {
        kc = FakeKeycloak.start("floor42");
        kc.registerClient("floor42-daemon", "daemon-secret-0123");
        kc.registerClient("floor42-api", "api-secret-4567");
        kc.registerClient("floor42-desk", null);
        var vault = new SecretVault(dataDir);
        vault.put("floor42-daemon", "daemon-secret-0123".getBytes(UTF_8));
        vault.put("floor42-api", "api-secret-4567".getBytes(UTF_8));
        keycloak = new KeycloakClient(kc.issuer().toString(), vault, new ObjectMapper());
        var settings = new IamSettings(
                dataDir, Optional.of(true), Optional.of(kc.issuer().toString()), Optional.empty(), new ObjectMapper());
        clients = new AgentClients(keycloak, settings, () -> List.of(ADA, BOB));
    }

    @AfterEach
    void stop() {
        kc.close();
    }

    @Test
    void aDismissalDisablesTheClientAndRevokesOnlyThatAgent() {
        clients.ensure(ADA);
        clients.ensure(BOB);
        clients.dismissed(ADA);
        assertThat(clients.revoked()).containsExactly(ADA);
        assertThat(keycloak.find(ADA).orElseThrow().enabled()).isFalse();
        assertThat(keycloak.find(BOB).orElseThrow().enabled()).isTrue();
    }

    @Test
    void aDisableKeycloakMissedIsRetriedUntilItHoldsAndOnlyThen() {
        clients.ensure(ADA);
        kc.down(true);
        clients.dismissed(ADA);
        clients.retryDisables();
        assertThat(clients.revoked())
                .as("local revocation never waits for Keycloak")
                .containsExactly(ADA);
        kc.down(false);
        assertThat(keycloak.find(ADA).orElseThrow().enabled()).isTrue();
        clients.retryDisables();
        assertThat(keycloak.find(ADA).orElseThrow().enabled()).isFalse();
        int disables = kc.requests().size();
        clients.retryDisables();
        assertThat(kc.requests()).as("nothing left to retry").hasSize(disables);
    }

    @Test
    void theDirectorsRevokeShowsAKeycloakFailureAndStillCutsTheAgentOffLocally() {
        clients.ensure(ADA);
        kc.down(true);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> clients.revoke(ADA))
                .isInstanceOf(RuntimeException.class);
        assertThat(clients.revoked()).containsExactly(ADA);
    }

    @Test
    void aHireMakesTheClientAndAFailureIsSwallowed() {
        clients.hired(ADA);
        assertThat(kc.clients()).contains("floor42-agent-ada");
        kc.down(true);
        clients.hired(BOB);
        assertThat(kc.clients()).doesNotContain("floor42-agent-bob");
    }

    @Test
    void theClientTokenLifeFollowsTheConfiguredMaximum() {
        clients.ensure(ADA);
        assertThat(kc.lifespan("floor42-agent-ada")).isEqualTo(String.valueOf(8 * 3600));
    }

    @Test
    void onlyAgentsOnStaffAreOnStaff() {
        assertThat(clients.onStaff(ADA)).isTrue();
        assertThat(clients.onStaff(new AgentId("eve"))).isFalse();
    }

    @Test
    void theIdentityScreenListsEachAgentWithWhatKeycloakHolds() {
        clients.ensure(ADA);
        clients.revokeLocally(ADA);
        var views = clients.views();
        assertThat(views).hasSize(2);
        var ada = views.get(0);
        assertThat(ada.agent()).isEqualTo(ADA);
        assertThat(ada.clientId()).isEqualTo("floor42-agent-ada");
        assertThat(ada.serviceAccountId()).isNotBlank();
        assertThat(ada.enabled()).isTrue();
        assertThat(ada.revoked()).isTrue();
        var bob = views.get(1);
        assertThat(bob.clientId()).isNull();
        assertThat(bob.serviceAccountId()).isNull();
        assertThat(bob.enabled()).isFalse();
        assertThat(bob.revoked()).isFalse();
    }

    @Test
    void rotatingChangesTheSecretKeycloakAccepts() {
        clients.ensure(ADA);
        int before = kc.requests().size();
        clients.rotate(ADA);
        assertThat(kc.requests().size()).isGreaterThan(before);
    }
}
