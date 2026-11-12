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

class AgentClientsTest {

    private static final AgentId ADA = new AgentId("ada");
    private static final AgentId BOB = new AgentId("bob");

    @TempDir
    Path dataDir;

    FakeKeycloak kc;
    KeycloakClient keycloak;

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
    }

    @AfterEach
    void stop() {
        kc.close();
    }

    private AgentClients clients(boolean on) {
        var settings = new IamSettings(
                dataDir, Optional.of(on), Optional.of(kc.issuer().toString()), Optional.empty(), new ObjectMapper());
        return new AgentClients(keycloak, settings, () -> List.of(ADA, BOB));
    }

    @Test
    void dismissalRevokesLocallyBeforeKeycloakAnswers() {
        var clients = clients(true);
        clients.ensure(ADA);
        kc.down(true);
        clients.dismissed(ADA);
        assertThat(clients.revoked()).containsExactly(ADA);
    }

    @Test
    void revokeAndRestoreFlipKeycloakAndTheLocalSet() {
        var clients = clients(true);
        clients.ensure(ADA);
        clients.revoke(ADA);
        assertThat(clients.revoked()).contains(ADA);
        assertThat(keycloak.find(ADA).orElseThrow().enabled()).isFalse();
        clients.enable(ADA);
        assertThat(clients.revoked()).isEmpty();
        assertThat(keycloak.find(ADA).orElseThrow().enabled()).isTrue();
    }

    @Test
    void ensureAllMakesAClientForEveryAgentOnStaff() {
        clients(true).ensureAll();
        assertThat(kc.clients()).contains("floor42-agent-ada", "floor42-agent-bob");
    }

    @Test
    void whileIamIsOffNothingTouchesKeycloakOrRevokes() {
        var clients = clients(false);
        clients.ensureAll();
        clients.hired(ADA);
        clients.dismissed(ADA);
        assertThat(kc.requests()).isEmpty();
        assertThat(clients.revoked()).isEmpty();
    }
}
