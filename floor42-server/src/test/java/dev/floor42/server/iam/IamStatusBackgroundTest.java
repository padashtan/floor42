package dev.floor42.server.iam;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.AgentId;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the background checks do to the status: fail closed on a miss, recover only on a good pass, never mask a refusal. */
class IamStatusBackgroundTest {

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

    private IamStatus status(boolean on, boolean sandbox) {
        var json = new ObjectMapper();
        var settings = new IamSettings(
                dataDir, Optional.of(on), Optional.of(kc.issuer().toString()), Optional.empty(), json);
        var keycloak = new KeycloakClient(kc.issuer().toString(), vault, json);
        var clients = new AgentClients(keycloak, settings, () -> List.of(new AgentId("ada")));
        var jwks = new Jwks(
                URI.create(kc.issuer() + "/protocol/openid-connect/certs"),
                HttpClient.newHttpClient(),
                Clock.systemUTC());
        SocketOwners owners = new SocketOwners() {
            public OptionalLong owner(InetSocketAddress client, InetSocketAddress server) {
                return OptionalLong.empty();
            }

            public boolean supported() {
                return true;
            }
        };
        return new IamStatus(settings, vault, owners, jwks, clients, keycloak, () -> sandbox);
    }

    private IamStatus up() {
        IamStatus s = status(true, true);
        s.start();
        assertThat(s.view().health()).isEqualTo(IamStatus.Health.UP);
        return s;
    }

    @Test
    void aMissedCheckBlocksNewSessionsAtOnceAndCountsEachMiss() {
        IamStatus s = up();
        s.checkFailed();
        var v = s.view();
        assertThat(v.health()).isEqualTo(IamStatus.Health.UNREACHABLE);
        assertThat(v.failedChecks()).isEqualTo(1);
        assertThat(v.reason()).isEqualTo("Keycloak did not answer the background check");
        assertThat(v.code()).isNull();
        assertThat(s.blocksSessions()).contains("Keycloak did not answer the background check");
        s.checkFailed();
        assertThat(s.view().failedChecks()).isEqualTo(2);
    }

    @Test
    void concurrentMissesAreAllCounted() throws Exception {
        IamStatus s = up();
        int n = 8, each = 200;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        var go = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < n; i++)
            done.add(pool.submit(() -> {
                go.await();
                for (int j = 0; j < each; j++) s.checkFailed();
                return null;
            }));
        go.countDown();
        for (var f : done) f.get();
        pool.shutdown();
        assertThat(s.view().failedChecks()).isEqualTo(n * each);
    }

    @Test
    void aPassThatRanOutOfTimeIsTheSameFailureToldAsWhatItIs() {
        IamStatus s = up();
        s.checkTimedOut();
        assertThat(s.view().health()).isEqualTo(IamStatus.Health.UNREACHABLE);
        assertThat(s.view().reason()).isEqualTo("The identity check ran out of time before it asked every session");
        assertThat(s.view().failedChecks()).isEqualTo(1);
    }

    @Test
    void aGoodPassMakesAnUnreachableKeycloakWellAgainAndResetsTheCount() {
        IamStatus s = up();
        s.checkFailed();
        s.checkFailed();
        s.checkOk();
        var v = s.view();
        assertThat(v.health()).isEqualTo(IamStatus.Health.UP);
        assertThat(v.failedChecks()).isZero();
        assertThat(v.reason()).isNull();
        assertThat(s.blocksSessions()).isEmpty();
    }

    @Test
    void aGoodPassOnAWellStatusOnlyRefreshesTheCheckTime() throws Exception {
        IamStatus s = up();
        var before = s.view().lastCheck();
        Thread.sleep(5);
        s.checkOk();
        assertThat(s.view().lastCheck()).isAfter(before);
        assertThat(s.view().health()).isEqualTo(IamStatus.Health.UP);
        assertThat(s.view().failedChecks()).isZero();
    }

    @Test
    void aRefusedApiCredentialBlocksLikeAMissAndOnlyAGoodPassFreesIt() {
        IamStatus s = up();
        s.credentialRefused();
        var v = s.view();
        assertThat(v.health()).isEqualTo(IamStatus.Health.REFUSED_TO_START);
        assertThat(v.code()).isEqualTo(IamStatus.API_CREDENTIAL_REFUSED);
        assertThat(v.reason()).contains("floor42-api").contains("Settings");
        assertThat(v.failedChecks()).isEqualTo(1);
        assertThat(s.blocksSessions()).isPresent();
        s.credentialRefused();
        assertThat(s.view().failedChecks()).isEqualTo(2);
        s.checkOk();
        assertThat(s.view().health()).isEqualTo(IamStatus.Health.UP);
        assertThat(s.view().code()).isNull();
        assertThat(s.view().failedChecks()).isZero();
    }

    @Test
    void aMissingApiSecretBlocksAndIsNeverUp() {
        IamStatus s = up();
        s.apiSecretMissing();
        var v = s.view();
        assertThat(v.health()).isEqualTo(IamStatus.Health.REFUSED_TO_START);
        assertThat(v.code()).isEqualTo(IamStatus.API_SECRET_MISSING);
        assertThat(v.reason()).contains("no floor42-api secret");
        assertThat(v.failedChecks()).isEqualTo(1);
        s.checkOk();
        assertThat(s.view().health()).isEqualTo(IamStatus.Health.UP);
    }

    @Test
    void aMissFromAnApiBlockedStatusBecomesAnUnreachableOneWithoutTheCode() {
        IamStatus s = up();
        s.credentialRefused();
        s.checkFailed();
        assertThat(s.view().health()).isEqualTo(IamStatus.Health.UNREACHABLE);
        assertThat(s.view().code()).isNull();
        assertThat(s.view().failedChecks()).isEqualTo(2);
        s.apiSecretMissing();
        assertThat(s.view().code()).isEqualTo(IamStatus.API_SECRET_MISSING);
        s.credentialRefused();
        assertThat(s.view().code()).isEqualTo(IamStatus.API_CREDENTIAL_REFUSED);
    }

    @Test
    void aRefusalToStartIsNotMaskedByTheBackgroundChecks() {
        IamStatus s = status(true, false);
        s.start();
        assertThat(s.view().code()).isEqualTo(IamStatus.NEEDS_SANDBOX);
        int failed = s.view().failedChecks();
        s.checkFailed();
        s.checkTimedOut();
        s.credentialRefused();
        s.apiSecretMissing();
        s.checkOk();
        assertThat(s.view().health()).isEqualTo(IamStatus.Health.REFUSED_TO_START);
        assertThat(s.view().code()).isEqualTo(IamStatus.NEEDS_SANDBOX);
        assertThat(s.view().failedChecks()).isEqualTo(failed);
        assertThat(s.blocksSessions()).isPresent();
    }

    @Test
    void anUnreachableStatusTellsWhereKeycloakWasLookedFor() {
        kc.down(true);
        IamStatus s = status(true, true);
        s.start();
        assertThat(s.view().reason()).isEqualTo("Keycloak does not answer at " + kc.issuer());
    }

    @Test
    void noApiSecretAtStartIsToldAtStart() {
        vault.delete("floor42-api");
        IamStatus s = status(true, true);
        s.start();
        assertThat(s.view().health()).isEqualTo(IamStatus.Health.REFUSED_TO_START);
        assertThat(s.view().code()).isEqualTo(IamStatus.API_SECRET_MISSING);
    }

    @Test
    void anIamThatIsOnButNotStartedIsNotReady() {
        IamStatus s = status(true, true);
        assertThat(s.blocksSessions()).contains("IAM mode is not ready");
        assertThat(status(false, true).blocksSessions()).isEmpty();
    }

    @Test
    void onlyTheTwoApiCodesMarkTheStatusAsApiBlocked() {
        assertThat(IamStatus.apiBlocked(IamStatus.API_CREDENTIAL_REFUSED)).isTrue();
        assertThat(IamStatus.apiBlocked(IamStatus.API_SECRET_MISSING)).isTrue();
        assertThat(IamStatus.apiBlocked(IamStatus.NEEDS_SANDBOX)).isFalse();
        assertThat(IamStatus.apiBlocked(null)).isFalse();
    }
}
