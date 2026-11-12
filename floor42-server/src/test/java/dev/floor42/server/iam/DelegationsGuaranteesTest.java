package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.MissionId;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Who may still get a token for a mission: revoked, expired, closed or unknown delegations give none. */
class DelegationsGuaranteesTest {

    private static final MissionId M1 = new MissionId("M-1");
    private static final MissionId M2 = new MissionId("M-2");

    @TempDir
    Path dir;

    FakeKeycloak kc;
    SecretVault vault;
    Delegations d;
    Path file;
    boolean iamOn = true;
    Optional<String> keycloakBlocks = Optional.empty();
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-09T10:00:00Z"));

    @BeforeEach
    void start() {
        kc = FakeKeycloak.start("floor42");
        kc.registerClient("floor42-desk", null);
        vault = new SecretVault(dir);
        file = dir.resolve("iam").resolve("delegations.json");
        d = open();
    }

    @AfterEach
    void stop() {
        kc.close();
    }

    private Delegations open() {
        var json = new ObjectMapper();
        var keycloak = new KeycloakClient(kc.issuer().toString(), vault, json);
        Delegations x = new Delegations(file, vault, keycloak, () -> iamOn, () -> keycloakBlocks, null, json);
        x.clock = new Clock() {
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(java.time.ZoneId z) {
                return this;
            }

            public Instant instant() {
                return now.get();
            }
        };
        return x;
    }

    private Tokens offline() {
        return new Tokens(
                "a",
                kc.refreshTokenFor("director", "floor42-desk"),
                null,
                now.get().plusSeconds(60));
    }

    private boolean revokeSeen() {
        return kc.requests().stream().anyMatch(r -> r.get("_path").endsWith("/revoke"));
    }

    // ---- what a delegation may name

    @Test
    void resourceNamesAreCleanedDedupedAndBounded() {
        assertThat(Delegations.cleanResources(null)).isEmpty();
        assertThat(Delegations.cleanResources(List.of(" crm ", "crm", "wiki.v2", "a_b-c")))
                .containsExactly("crm", "wiki.v2", "a_b-c");
        for (String bad : new String[] {"", "Crm", "-x", ".x", "a b", "a/b", "x".repeat(65)})
            assertThatThrownBy(() -> Delegations.cleanResources(List.of(bad)))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Delegations.cleanResources(java.util.Arrays.asList("ok", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Delegations.cleanResources(List.of("x".repeat(64)))).hasSize(1);
        List<String> many = new ArrayList<>();
        for (int i = 0; i < Delegations.MAX_RESOURCES; i++) many.add("r" + i);
        assertThat(Delegations.cleanResources(many)).hasSize(Delegations.MAX_RESOURCES);
        many.add("r-over");
        assertThatThrownBy(() -> Delegations.cleanResources(many)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDelegationNeedsAnOfflineRefreshTokenAndLastsThirtyDays() {
        assertThatThrownBy(() -> d.grant(M1, null, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> d.grant(M1, new Tokens("a", null, null, now.get()), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        var g = d.grant(M1, offline(), List.of("crm"));
        assertThat(g.grantedAt()).isEqualTo(now.get());
        assertThat(g.expiresAt()).isEqualTo(now.get().plus(Delegations.LIFETIME));
        assertThat(g.revoked()).isFalse();
        assertThat(Delegations.entryOf(M1)).isEqualTo("delegation-m-1");
    }

    @Test
    void grantingAgainRevokesTheTokenItReplacesAtKeycloak() {
        d.grant(M1, offline(), List.of());
        assertThat(revokeSeen()).isFalse();
        d.grant(M1, offline(), List.of());
        assertThat(revokeSeen()).isTrue();
    }

    // ---- who gets a token

    @Test
    void noDelegationRevokedExpiredOrTokenlessGivesNoSubjectToken() {
        assertThatThrownBy(() -> d.subjectTokenFor(M1))
                .isInstanceOf(IdpRefusedException.class)
                .hasMessageContaining("No delegation");
        d.grant(M1, offline(), List.of());
        assertThat(d.subjectTokenFor(M1)).isNotBlank();
        now.set(now.get().plus(Delegations.LIFETIME).minusSeconds(1));
        assertThat(d.subjectTokenFor(M1)).isNotBlank();
        now.set(now.get().plusSeconds(1));
        assertThatThrownBy(() -> d.subjectTokenFor(M1)).hasMessageContaining("expired");
        d.grant(M2, offline(), List.of());
        d.revokeLocally(M2);
        assertThatThrownBy(() -> d.subjectTokenFor(M2)).hasMessageContaining("revoked");
        d.grant(new MissionId("M-3"), offline(), List.of());
        vault.delete(Delegations.entryOf(new MissionId("M-3")));
        assertThatThrownBy(() -> d.subjectTokenFor(new MissionId("M-3"))).hasMessageContaining("no token");
    }

    @Test
    void aGrantKeycloakEndedRevokesTheDelegationHereToo() {
        d.grant(M1, new Tokens("a", "not-a-real-refresh-token", null, now.get()), List.of());
        assertThatThrownBy(() -> d.subjectTokenFor(M1)).isInstanceOf(IdpRefusedException.class);
        assertThat(d.isRevoked(M1)).isTrue();
        assertThat(d.find(M1)).get().extracting(MissionDelegation::revoked).isEqualTo(true);
    }

    @Test
    void aRotatedRefreshTokenReplacesTheStoredOne() {
        d.grant(M1, offline(), List.of());
        byte[] before = vault.get(Delegations.entryOf(M1)).orElseThrow();
        d.subjectTokenFor(M1);
        byte[] after = vault.get(Delegations.entryOf(M1)).orElseThrow();
        assertThat(after).isNotEmpty();
        assertThat(d.subjectTokenFor(M1)).isNotBlank();
        assertThat(new String(before, StandardCharsets.UTF_8)).isNotBlank();
    }

    // ---- when the agents must not start

    @Test
    void theBlockReasonsAreInOrderAndAlwaysEmptyWhileIamIsOff() {
        assertThat(d.blocks(M1, false)).isEmpty();
        assertThat(d.blocks(M1, true)).contains("no delegation: approve the plan again with a Keycloak sign-in");
        keycloakBlocks = Optional.of("keycloak down");
        assertThat(d.blocks(M1, false)).contains("keycloak down");
        d.grant(M1, offline(), List.of());
        assertThat(d.blocks(M1, true)).contains("keycloak down");
        keycloakBlocks = Optional.empty();
        assertThat(d.blocks(M1, true)).isEmpty();
        now.set(now.get().plus(Delegations.LIFETIME));
        assertThat(d.blocks(M1, true)).contains("delegation expired");
        now.set(now.get().minus(Delegations.LIFETIME));
        d.revokeLocally(M1);
        assertThat(d.blocks(M1, true)).contains("delegation revoked");
        iamOn = false;
        assertThat(d.blocks(M1, true)).isEmpty();
        assertThat(d.blocks(M2, true)).isEmpty();
    }

    // ---- revocation

    @Test
    void revokingCutsOffLocallyAtOnceAndKeycloakAndTheVaultFollow() {
        d.grant(M1, offline(), List.of());
        d.grant(M2, offline(), List.of());
        assertThat(d.liveMissions()).containsExactly(M1, M2);
        d.revokeLocally(M1);
        assertThat(d.liveMissions()).containsExactly(M2);
        assertThat(d.isRevoked(M1)).isTrue();
        assertThat(d.isRevoked(M2)).isFalse();
        assertThat(vault.get(Delegations.entryOf(M1))).isPresent();
        assertThat(revokeSeen()).isFalse();
        d.revokeRemote(M1);
        assertThat(revokeSeen()).isTrue();
        assertThat(vault.get(Delegations.entryOf(M1))).isEmpty();
        assertThat(vault.get(Delegations.entryOf(M2))).isPresent();
    }

    @Test
    void revokeRemoteOfALiveOrUnknownDelegationDoesNothing() {
        d.grant(M1, offline(), List.of());
        d.revokeRemote(M1);
        d.revokeRemote(M2);
        assertThat(revokeSeen()).isFalse();
        assertThat(vault.get(Delegations.entryOf(M1))).isPresent();
        assertThat(d.isRevoked(M2)).isFalse();
    }

    @Test
    void aRevokeKeycloakDidNotTakeIsKeptAndRetriedUntilItHolds() {
        d.grant(M1, offline(), List.of());
        kc.revokeStatus(500);
        d.revoke(M1);
        assertThat(vault.get(Delegations.entryOf(M1)))
                .as("kept encrypted for the retry")
                .isPresent();
        d.retryRevocations();
        assertThat(vault.get(Delegations.entryOf(M1))).isPresent();
        kc.revokeStatus(200);
        d.retryRevocations();
        assertThat(vault.get(Delegations.entryOf(M1))).isEmpty();
        int calls = kc.requests().size();
        d.retryRevocations();
        assertThat(kc.requests()).hasSize(calls);
    }

    @Test
    void theStartRetryRunsOnlyWithIamOnAndKeycloakUsable() {
        d.grant(M1, offline(), List.of());
        d.revokeLocally(M1);
        iamOn = false;
        d.retryAtStart();
        assertThat(vault.get(Delegations.entryOf(M1))).isPresent();
        iamOn = true;
        keycloakBlocks = Optional.of("down");
        d.retryAtStart();
        assertThat(vault.get(Delegations.entryOf(M1))).isPresent();
        keycloakBlocks = Optional.empty();
        d.retryAtStart();
        assertThat(vault.get(Delegations.entryOf(M1))).isEmpty();
    }

    @Test
    void aRevokeOrAClosingAfterTheChecksBegunStillBites() {
        Instant checked = now.get();
        assertThat(d.revokedSince(M1, checked)).isFalse();
        now.set(checked.plusSeconds(5));
        d.grant(M1, offline(), List.of());
        d.revokeLocally(M1);
        assertThat(d.revokedSince(M1, checked)).isTrue();
        assertThat(d.revokedSince(M1, now.get())).as("at the very instant").isTrue();
        assertThat(d.revokedSince(M1, now.get().plusMillis(1))).isFalse();
        d.closeToWork(M2);
        assertThat(d.revokedSince(M2, checked)).isTrue();
        assertThat(d.revokedSince(M2, now.get().plusSeconds(1))).isFalse();
        assertThat(d.revokedSince(new MissionId("M-9"), checked)).isFalse();
    }

    @Test
    void aClosedMissionIsClosedToWorkButItsDelegationStaysForTheRetrospective() {
        d.grant(M1, offline(), List.of());
        assertThat(d.isClosed(M1)).isFalse();
        d.closeToWork(M1);
        assertThat(d.isClosed(M1)).isTrue();
        assertThat(d.isClosed(M2)).isFalse();
        assertThat(d.isRevoked(M1)).isFalse();
    }

    // ---- persistence

    @Test
    void delegationsSurviveARestartRevokedOnesStayRevoked() {
        d.grant(M1, offline(), List.of("crm", "wiki"));
        d.grant(M2, offline(), List.of());
        d.revokeLocally(M2);
        Delegations again = open();
        assertThat(again.find(M1))
                .get()
                .extracting(MissionDelegation::resources)
                .isEqualTo(List.of("crm", "wiki"));
        assertThat(again.find(M1).orElseThrow().revoked()).isFalse();
        assertThat(again.find(M1).orElseThrow().expiresAt()).isEqualTo(now.get().plus(Delegations.LIFETIME));
        assertThat(again.isRevoked(M2)).isTrue();
        assertThat(again.isRevoked(M1)).isFalse();
    }

    @Test
    void aDelegationWithoutTheRevokedFieldIsReadAsRevoked() throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                "[{\"mission\":\"M-1\",\"grantedAt\":\"2026-11-09T10:00:00Z\",\"expiresAt\":\"2026-12-09T10:00:00Z\",\"resources\":[]}]");
        assertThat(open().isRevoked(M1)).isTrue();
    }

    @Test
    void anUnreadableFileTrustsNoDelegation() throws Exception {
        d.grant(M1, offline(), List.of());
        Files.writeString(file, "{not json");
        Delegations again = open();
        assertThat(again.find(M1)).isEmpty();
        assertThat(again.liveMissions()).isEmpty();
        assertThat(again.blocks(M1, true)).isPresent();
    }
}
