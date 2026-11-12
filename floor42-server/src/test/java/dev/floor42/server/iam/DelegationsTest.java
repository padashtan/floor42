package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.MissionId;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A mission's delegation against a fake Keycloak that mirrors what Keycloak 26.8.1 does (measured): flows inside one
 * SSO session share one offline session, a flow with {@code prompt=login} opens its own.
 */
class DelegationsTest {

    private static final String REDIRECT = "http://127.0.0.1:7420/auth/callback";
    private static final MissionId M3 = new MissionId("M-3");
    private static final MissionId M4 = new MissionId("M-4");

    @TempDir
    Path dir;

    FakeKeycloak fake;
    SecretVault vault;
    KeycloakClient keycloak;
    Delegations delegations;
    boolean iamOn = true;
    Optional<String> keycloakBlocks = Optional.empty();
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() {
        fake = FakeKeycloak.start("floor42");
        fake.registerClient("floor42-desk", null);
        ObjectMapper json = new ObjectMapper();
        vault = new SecretVault(dir);
        keycloak = new KeycloakClient(fake.issuer().toString(), vault, json);
        delegations = new Delegations(
                dir.resolve("iam").resolve("delegations.json"),
                vault,
                keycloak,
                () -> iamOn,
                () -> keycloakBlocks,
                null,
                json);
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    /** The offline tokens of a code flow; {@code stepUp} asks for the password again (a new SSO session). */
    private Tokens offline(boolean stepUp) throws Exception {
        String verifier = "v".repeat(43);
        String challenge = Jwts.base64url(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String query = "response_type=code&client_id=floor42-desk&redirect_uri="
                + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
                + "&scope=openid+offline_access&state=s&nonce=n&code_challenge=" + challenge
                + "&code_challenge_method=S256" + (stepUp ? "&prompt=login&max_age=0" : "");
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(fake.issuer() + "/protocol/openid-connect/auth?" + query))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        String location = r.headers().firstValue("Location").orElseThrow();
        String code = location.replaceAll(".*[?&]code=([^&]+).*", "$1");
        return keycloak.redeemCode(code, verifier, URI.create(REDIRECT));
    }

    private boolean revokeSeen() {
        return fake.requests().stream().anyMatch(r -> r.get("_path").endsWith("/revoke"));
    }

    @Test
    void approvalStoresTheTokenOnlyEncrypted() throws Exception {
        Tokens t = offline(true);
        MissionDelegation d = delegations.grant(M3, t, List.of("crm", "wiki"));
        assertThat(d.resources()).containsExactly("crm", "wiki");
        assertThat(d.vaultEntry()).isEqualTo("delegation-m-3");
        String metadata = Files.readString(dir.resolve("iam").resolve("delegations.json"));
        assertThat(metadata)
                .doesNotContain("eyJ")
                .doesNotContain(t.refreshToken())
                .doesNotContain(t.accessToken());
        assertThat(vault.get("delegation-m-3")).hasValue(t.refreshToken().getBytes(StandardCharsets.UTF_8));
        try (Stream<Path> files = Files.walk(dir.resolve("iam").resolve("vault"))) {
            for (Path f : files.filter(Files::isRegularFile).toList())
                assertThat(new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1))
                        .doesNotContain(t.refreshToken());
        }
        assertThat(delegations.blocks(M3, false)).isEmpty();
        assertThat(delegations.subjectTokenFor(M3)).isNotBlank();
    }

    @Test
    void revokeCutsOffLocallyFirstThenRevokesInKeycloakAndDeletesTheEntry() throws Exception {
        delegations.grant(M3, offline(true), List.of());
        delegations.revokeLocally(M3);
        assertThat(revokeSeen()).isFalse();
        assertThat(delegations.blocks(M3, false)).contains("delegation revoked");
        assertThatThrownBy(() -> delegations.subjectTokenFor(M3)).isInstanceOf(IdpRefusedException.class);
        delegations.revokeRemote(M3);
        assertThat(revokeSeen()).isTrue();
        assertThat(vault.get("delegation-m-3")).isEmpty();
        assertThat(delegations.find(M3))
                .get()
                .extracting(MissionDelegation::revoked)
                .isEqualTo(true);
    }

    @Test
    void aStepUpDelegationIsIndependentOfTheOthers() throws Exception {
        delegations.grant(M3, offline(true), List.of());
        delegations.grant(M4, offline(true), List.of());
        delegations.revoke(M3);
        assertThat(delegations.subjectTokenFor(M4)).isNotBlank();
        assertThat(delegations.blocks(M4, false)).isEmpty();
    }

    @Test
    void twoFlowsInOneSsoSessionShareOneOfflineSessionAsKeycloakDoes() throws Exception {
        Tokens first = offline(false);
        Tokens second = offline(false);
        keycloak.revoke(first.refreshToken());
        assertThatThrownBy(() -> keycloak.refresh(second.refreshToken(), "floor42-desk"))
                .isInstanceOf(IdpRefusedException.class);
    }

    @Test
    void theStoredTokenIsReplacedWhenKeycloakRotatesIt() throws Exception {
        Tokens t = offline(true);
        delegations.grant(M3, t, List.of());
        delegations.subjectTokenFor(M3);
        assertThat(vault.get("delegation-m-3"))
                .isPresent()
                .get()
                .isNotEqualTo(t.refreshToken().getBytes(StandardCharsets.UTF_8));
        assertThat(delegations.subjectTokenFor(M3)).isNotBlank();
    }

    @Test
    void aRevokeKeycloakDidNotTakeIsKeptEncryptedAndRetried() throws Exception {
        delegations.grant(M3, offline(true), List.of());
        fake.down(true);
        delegations.revoke(M3);
        assertThat(vault.get("delegation-m-3")).isPresent();
        assertThat(delegations.blocks(M3, false)).contains("delegation revoked");
        fake.down(false);
        delegations.retryRevocations();
        assertThat(vault.get("delegation-m-3")).isEmpty();
        assertThat(revokeSeen()).isTrue();
    }

    @Test
    void aRevokeRacingASessionStartNeverLetsItStartAfterwards() throws Exception {
        delegations.grant(M3, offline(true), List.of());
        AtomicBoolean revoked = new AtomicBoolean();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger late = new AtomicInteger();
        Runnable starter = () -> {
            while (!stop.get()) {
                boolean afterRevoke = revoked.get();
                try {
                    delegations.subjectTokenFor(M3);
                    if (afterRevoke) late.incrementAndGet();
                } catch (IdpRefusedException e) {
                    // Handled: refused is the right answer once revoked.
                }
            }
        };
        Thread a = new Thread(starter);
        Thread b = new Thread(starter);
        a.start();
        b.start();
        Thread.sleep(50);
        delegations.revokeLocally(M3);
        revoked.set(true);
        Thread.sleep(50);
        stop.set(true);
        a.join();
        b.join();
        assertThat(late.get()).isZero();
    }

    @Test
    void blocksOnlyWhenIamIsOnAndSomethingIsWrong() throws Exception {
        assertThat(delegations.blocks(M3, false)).isEmpty();
        keycloakBlocks = Optional.of("Keycloak is unreachable");
        assertThat(delegations.blocks(M3, false)).contains("Keycloak is unreachable");
        iamOn = false;
        assertThat(delegations.blocks(M3, false)).isEmpty();
    }

    @Test
    void anUnknownResourceNameIsRefused() {
        assertThatThrownBy(() -> Delegations.cleanResources(List.of("Bad Name!")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Delegations.cleanResources(null)).isEmpty();
    }

    @Test
    void metadataSurvivesARestartAndAnUnreadableFileTrustsNothing() throws Exception {
        delegations.grant(M3, offline(true), List.of("crm"));
        Delegations again = new Delegations(
                dir.resolve("iam").resolve("delegations.json"),
                vault,
                keycloak,
                () -> true,
                Optional::empty,
                null,
                new ObjectMapper());
        assertThat(again.find(M3))
                .get()
                .extracting(MissionDelegation::resources)
                .isEqualTo(List.of("crm"));
        Files.writeString(dir.resolve("iam").resolve("delegations.json"), "not json");
        Delegations broken = new Delegations(
                dir.resolve("iam").resolve("delegations.json"),
                vault,
                keycloak,
                () -> true,
                Optional::empty,
                null,
                new ObjectMapper());
        assertThat(broken.find(M3)).isEmpty();
    }

    @Test
    void aMissionPastApprovalWithoutDelegationIsBlocked() {
        MissionId m5 = new MissionId("M-5");
        assertThat(delegations.blocks(m5, true))
                .contains("no delegation: approve the plan again with a Keycloak sign-in");
        assertThat(delegations.blocks(m5, false)).isEmpty();
        iamOn = false;
        assertThat(delegations.blocks(m5, true)).isEmpty();
    }

    @Test
    void aRevokeKeycloakRefusedIsNotARevokeAndIsRetried() throws Exception {
        delegations.grant(M3, offline(true), List.of());
        fake.revokeStatus(400);
        delegations.revoke(M3);
        assertThat(vault.get("delegation-m-3")).isPresent();
        fake.revokeStatus(200);
        delegations.retryRevocations();
        assertThat(vault.get("delegation-m-3")).isEmpty();
        assertThat(revokeSeen()).isTrue();
    }

    @Test
    void theStartRetriesWhatKeycloakMissedOnlyOnceItIsUsable() throws Exception {
        delegations.grant(M3, offline(true), List.of());
        fake.down(true);
        delegations.revoke(M3);
        fake.down(false);
        keycloakBlocks = Optional.of("Keycloak is unreachable");
        delegations.retryAtStart();
        assertThat(vault.get("delegation-m-3")).isPresent();
        keycloakBlocks = Optional.empty();
        delegations.retryAtStart();
        assertThat(vault.get("delegation-m-3")).isEmpty();
    }

    @Test
    void aGrantThatCannotBeSavedLeavesNoTokenInTheVault() throws Exception {
        Files.writeString(dir.resolve("blocker"), "a file, not a folder");
        Delegations unwritable = new Delegations(
                dir.resolve("blocker").resolve("delegations.json"),
                vault,
                keycloak,
                () -> true,
                Optional::empty,
                null,
                new ObjectMapper());
        assertThatThrownBy(() -> unwritable.grant(M3, offline(true), List.of()))
                .isInstanceOf(java.io.UncheckedIOException.class);
        assertThat(vault.get("delegation-m-3")).isEmpty();
        assertThat(unwritable.find(M3)).isEmpty();
    }
}
