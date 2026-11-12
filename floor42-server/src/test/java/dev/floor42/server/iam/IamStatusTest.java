package dev.floor42.server.iam;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.AgentId;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The start-up checks of IAM mode: every one fails closed, with a code the screen explains. */
class IamStatusTest {

    private static final AgentId ADA = new AgentId("ada");

    @TempDir
    Path dataDir;

    FakeKeycloak kc;
    SecretVault vault;

    @BeforeEach
    void start() {
        kc = FakeKeycloak.start("floor42");
        kc.registerClient("floor42-daemon", "daemon-secret-0123");
        kc.registerClient("floor42-api", "api-secret-4567");
        kc.registerClient("floor42-desk", null);
        vault = new SecretVault(dataDir);
        vault.put("floor42-daemon", "daemon-secret-0123".getBytes(UTF_8));
        vault.put("floor42-api", "api-secret-4567".getBytes(UTF_8));
    }

    @AfterEach
    void stop() {
        kc.close();
    }

    private static SocketOwners owners(boolean supported) {
        return new SocketOwners() {
            @Override
            public OptionalLong owner(InetSocketAddress client, InetSocketAddress server) {
                return OptionalLong.empty();
            }

            @Override
            public boolean supported() {
                return supported;
            }
        };
    }

    private IamStatus status(boolean on, boolean sandbox, boolean bindable) {
        return status(on, (BooleanSupplier) () -> sandbox, owners(bindable));
    }

    private IamStatus status(boolean on, BooleanSupplier sandbox, SocketOwners owners) {
        var settings = new IamSettings(
                dataDir, Optional.of(on), Optional.of(kc.issuer().toString()), Optional.empty(), new ObjectMapper());
        var keycloak = new KeycloakClient(kc.issuer().toString(), vault, new ObjectMapper());
        var clients = new AgentClients(keycloak, settings, () -> List.of(ADA));
        var jwks = new Jwks(
                URI.create(kc.issuer() + "/protocol/openid-connect/certs"),
                HttpClient.newHttpClient(),
                Clock.systemUTC());
        return new IamStatus(settings, vault, owners, jwks, clients, keycloak, sandbox);
    }

    @Test
    void offByDefaultBlocksNothing() {
        var s = status(false, false, false);
        s.start();
        assertThat(s.view().health()).isEqualTo(IamStatus.Health.OFF);
        assertThat(s.blocksSessions()).isEmpty();
        assertThat(kc.requests()).isEmpty();
    }

    @Test
    void withoutAWorkingSandboxIamRefusesToStart() {
        var s = status(true, false, true);
        s.start();
        assertThat(s.view().health()).isEqualTo(IamStatus.Health.REFUSED_TO_START);
        assertThat(s.view().code()).isEqualTo("IAM_NEEDS_SANDBOX");
        assertThat(s.blocksSessions()).isPresent();
        assertThat(kc.requests()).as("Keycloak is not even asked").isEmpty();
    }

    @Test
    void unsupportedPlatformRefusesToStart() {
        var s = status(true, true, false);
        s.start();
        assertThat(s.view().health()).isEqualTo(IamStatus.Health.REFUSED_TO_START);
        assertThat(s.view().code()).isEqualTo("IAM_NEEDS_PROCESS_BINDING");
        assertThat(s.blocksSessions().orElseThrow()).startsWith("process binding unavailable on ");
    }

    @Test
    void aVaultKeyOfTheWrongSizeRefusesToStartAndIsNeverRegenerated() throws IOException {
        Files.write(vault.keyFile(), new byte[5]);
        var s = status(true, true, true);
        s.start();
        assertThat(s.view().health()).isEqualTo(IamStatus.Health.REFUSED_TO_START);
        assertThat(s.view().code()).isEqualTo("IAM_VAULT_KEY_INVALID");
        assertThat(s.blocksSessions().orElseThrow()).contains("32 bytes");
        assertThat(Files.size(vault.keyFile())).isEqualTo(5);
    }

    @Test
    void upWarmsTheKeysAndMakesTheClients() {
        var s = status(true, true, true);
        s.start();
        assertThat(s.view().health()).isEqualTo(IamStatus.Health.UP);
        assertThat(s.blocksSessions()).isEmpty();
        assertThat(kc.clients()).contains("floor42-agent-ada");
        assertThat(kc.requests()).anyMatch(r -> String.valueOf(r).contains("certs"));
    }

    @Test
    void aRefusedApiCredentialIsToldAtStartAndBlocksSessions() {
        vault.put("floor42-api", "not-the-secret-99".getBytes(UTF_8));
        var s = status(true, true, true);
        s.start();
        assertThat(s.view().health()).isEqualTo(IamStatus.Health.REFUSED_TO_START);
        assertThat(s.view().code()).isEqualTo(IamStatus.API_CREDENTIAL_REFUSED);
        assertThat(s.view().reason()).contains("floor42-api").contains("Settings");
        assertThat(s.blocksSessions()).isPresent();
    }

    @Test
    void anUnreachableKeycloakBlocksSessions() {
        kc.down(true);
        var s = status(true, true, true);
        s.start();
        assertThat(s.view().health()).isEqualTo(IamStatus.Health.UNREACHABLE);
        assertThat(s.view().failedChecks()).isEqualTo(1);
        assertThat(s.blocksSessions()).isPresent();
    }
}
