package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.iam.ScriptTokenBindings.Liveness;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A script token bound to a Keycloak session (ADR-0083), against the fake Keycloak, with a clock the test moves. */
class ScriptTokenBindingsTest {

    /** A clock the test moves. */
    private static final class Moving extends Clock {
        volatile Instant now = Instant.parse("2026-11-16T10:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    @TempDir
    Path dir;

    FakeKeycloak fake;
    SecretVault vault;
    KeycloakClient keycloak;
    ScriptTokenBindings bindings;
    final Moving clock = new Moving();
    final Set<String> open = new HashSet<>();

    @BeforeEach
    void start() {
        fake = FakeKeycloak.start("floor42");
        fake.registerClient("floor42-desk", null);
        vault = new SecretVault(dir);
        keycloak = new KeycloakClient(fake.issuer().toString(), vault, new ObjectMapper());
        bindings = new ScriptTokenBindings(vault, keycloak, open::contains);
        bindings.useClock(clock);
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private String refreshToken;

    private Tokens offline() {
        refreshToken = fake.refreshTokenFor("director-1", "floor42-desk");
        return new Tokens(null, refreshToken, null, clock.instant());
    }

    private void bind(String id) {
        open.add(id);
        bindings.bind(id, offline(), "director-1");
    }

    private long refreshes() {
        return fake.requests().stream()
                .filter(r -> "refresh_token".equals(r.get("grant_type")))
                .count();
    }

    private boolean revokeSeen() {
        return fake.requests().stream().anyMatch(r -> r.get("_path").endsWith("/revoke"));
    }

    @Test
    void aTokenNobodyBoundIsUnbound() {
        assertThat(bindings.peek("s-nothing")).isEqualTo(Liveness.UNBOUND);
        assertThat(bindings.live("s-nothing")).isEqualTo(Liveness.UNBOUND);
    }

    @Test
    void aFreshBindingIsLiveWithoutACall() {
        bind("s-1");
        assertThat(bindings.peek("s-1")).isEqualTo(Liveness.LIVE);
        assertThat(bindings.live("s-1")).isEqualTo(Liveness.LIVE);
        assertThat(bindings.subject("s-1")).hasValue("director-1");
        assertThat(refreshes()).isZero();
    }

    @Test
    void aBoundTokenRefreshesAtMostOncePerMinute() {
        bind("s-1");
        clock.advance(Duration.ofSeconds(61));
        assertThat(bindings.peek("s-1")).isEqualTo(Liveness.STALE);
        for (int i = 0; i < 5; i++) assertThat(bindings.live("s-1")).isEqualTo(Liveness.LIVE);
        assertThat(refreshes()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(59));
        assertThat(bindings.live("s-1")).isEqualTo(Liveness.LIVE);
        assertThat(refreshes()).isEqualTo(1);
        // The refresh token rotates: the next minute works with the one Keycloak handed back.
        clock.advance(Duration.ofSeconds(2));
        assertThat(bindings.live("s-1")).isEqualTo(Liveness.LIVE);
        assertThat(refreshes()).isEqualTo(2);
    }

    @Test
    void aBoundTokenWhoseKeycloakSessionEndedIsRefusedAndNeverRetried() {
        bind("s-1");
        fake.revokeAll();
        clock.advance(Duration.ofSeconds(61));
        assertThat(bindings.live("s-1")).isEqualTo(Liveness.DEAD);
        long calls = fake.requests().size();
        clock.advance(Duration.ofHours(1));
        assertThat(bindings.peek("s-1")).isEqualTo(Liveness.DEAD);
        assertThat(bindings.live("s-1")).isEqualTo(Liveness.DEAD);
        assertThat(fake.requests()).hasSize((int) calls);
        assertThat(vault.get(ScriptTokenBindings.entry("s-1"))).isEmpty();
    }

    @Test
    void keycloakUnreachableRefusesAndIsNotAskedAgainForFiveSeconds() {
        bind("s-1");
        clock.advance(Duration.ofSeconds(61));
        fake.down(true);
        assertThat(bindings.live("s-1")).isEqualTo(Liveness.UNAVAILABLE);
        fake.down(false);
        long calls = fake.requests().size();
        // The failure is remembered: no call, and the guard learns it without a worker.
        assertThat(bindings.peek("s-1")).isEqualTo(Liveness.UNAVAILABLE);
        assertThat(bindings.live("s-1")).isEqualTo(Liveness.UNAVAILABLE);
        assertThat(fake.requests()).hasSize((int) calls);
        clock.advance(Duration.ofSeconds(6));
        assertThat(bindings.peek("s-1")).isEqualTo(Liveness.STALE);
        assertThat(bindings.live("s-1")).isEqualTo(Liveness.LIVE);
    }

    @Test
    void aRefreshThatReturnsAfterTheTokenEndedIsRevokedAndNeverStored() throws Exception {
        bind("s-1");
        clock.advance(Duration.ofSeconds(61));
        fake.delay(Duration.ofMillis(700));
        var refreshing = java.util.concurrent.CompletableFuture.supplyAsync(() -> bindings.live("s-1"));
        Thread.sleep(250);
        bindings.end("s-1");
        assertThat(refreshing.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(Liveness.DEAD);
        fake.delay(Duration.ZERO);
        assertThat(vault.get(ScriptTokenBindings.entry("s-1"))).isEmpty();
        // The ending revoked the stored token, and the late answer's token was revoked too.
        assertThat(fake.requests().stream()
                        .filter(r -> r.get("_path").endsWith("/revoke"))
                        .count())
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void theOfflineTokenNeverLeavesTheVault() throws Exception {
        bind("s-1");
        assertThat(bindings.toString()).doesNotContain(refreshToken);
        assertThat(vault.get(ScriptTokenBindings.entry("s-1"))).hasValue(refreshToken.getBytes(StandardCharsets.UTF_8));
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(Files::isRegularFile).toList())
                assertThat(new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1))
                        .doesNotContain(refreshToken);
        }
    }

    @Test
    void endingATokenRevokesItsSessionAtKeycloak() {
        bind("s-1");
        bindings.end("s-1");
        assertThat(revokeSeen()).isTrue();
        assertThat(bindings.peek("s-1")).isEqualTo(Liveness.UNBOUND);
        assertThat(vault.get(ScriptTokenBindings.entry("s-1"))).isEmpty();
        assertThat(vault.get("scriptbind-index")).isEmpty();
    }

    @Test
    void aRevocationKeycloakMissedIsRefusedAtOnceAndRetriedBySweep() {
        bind("s-1");
        fake.revokeStatus(500);
        open.remove("s-1");
        bindings.end("s-1");
        assertThat(bindings.peek("s-1")).isEqualTo(Liveness.DEAD);
        assertThat(vault.get(ScriptTokenBindings.entry("s-1"))).isPresent();
        fake.revokeStatus(200);
        bindings.sweep();
        assertThat(bindings.peek("s-1")).isEqualTo(Liveness.UNBOUND);
        assertThat(vault.get(ScriptTokenBindings.entry("s-1"))).isEmpty();
    }

    @Test
    void aTokenThatLeftTheRegistryLosesItsSession() {
        bind("s-1");
        bindings.sweep();
        assertThat(bindings.peek("s-1")).isEqualTo(Liveness.LIVE);
        open.remove("s-1");
        bindings.sweep();
        assertThat(revokeSeen()).isTrue();
        assertThat(bindings.peek("s-1")).isEqualTo(Liveness.UNBOUND);
    }

    @Test
    void whatAPreviousRunLeftIsRevokedAtTheNextStart() {
        bind("s-1");
        open.clear();
        ScriptTokenBindings restarted = new ScriptTokenBindings(vault, keycloak, open::contains);
        restarted.loadIndex();
        assertThat(restarted.peek("s-1")).isEqualTo(Liveness.DEAD);
        restarted.sweep();
        assertThat(revokeSeen()).isTrue();
        assertThat(restarted.peek("s-1")).isEqualTo(Liveness.UNBOUND);
        assertThat(vault.get(ScriptTokenBindings.entry("s-1"))).isEmpty();
    }

    @Test
    void theSweepRevokesWhateverIsLeftWhetherOrNotIamModeIsOn() {
        // IAM mode off at start (a daemon restarted with it off): the offline sessions still end.
        bind("s-1");
        open.clear();
        ScriptTokenBindings restarted = new ScriptTokenBindings(vault, keycloak, open::contains);
        restarted.loadIndex();
        restarted.sweep();
        assertThat(revokeSeen()).isTrue();
        assertThat(restarted.peek("s-1")).isEqualTo(Liveness.UNBOUND);
    }
}
