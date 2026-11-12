package dev.floor42.server.iam;

import static io.restassured.RestAssured.given;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The 60 s check: two misses refuse running sessions, one blocks new ones, Keycloak's "inactive" ends a session. */
@QuarkusTest
@TestProfile(IamSessionProfile.class)
class IdentityWatchTest extends IamSessionTestBase {

    private static final long ME = ProcessHandle.current().pid();

    @Inject
    IdentityWatch watch;

    @Inject
    Delegations delegations;

    static final java.util.concurrent.atomic.AtomicInteger recoveries = new java.util.concurrent.atomic.AtomicInteger();

    void onRecovered(@jakarta.enterprise.event.Observes IamRecovered ignored) {
        recoveries.incrementAndGet();
    }

    @BeforeEach
    void keycloakSetUp() {
        var kc = IamOnProfile.fake;
        kc.down(false);
        if (!kc.clients().contains("floor42-api")) kc.registerClient("floor42-api", "api-secret-4567");
        vault.put("floor42-api", "api-secret-4567".getBytes(UTF_8));
        kc.registerClient("floor42-agent-chris", "s"); // enabled again after a test that disabled it
        status.start();
    }

    @AfterEach
    void keycloakBack() {
        watch.clock(System::nanoTime);
        IamOnProfile.fake.down(false);
        IamOnProfile.fake.acceptAllIntrospections();
    }

    private io.restassured.response.ValidatableResponse call(String token) {
        return given().contentType("application/json")
                .header("Authorization", "Bearer " + token)
                .body(preToolUse("Read", UUID.randomUUID().toString()))
                .post("/hooks/pre-tool-use")
                .then();
    }

    @Test
    void twoFailedChecksRefuseRunningSessionsAndOneBlocksNewOnes() throws Exception {
        String token = liveSession(realMissionInProgress(), ME);
        IamOnProfile.fake.down(true);
        watch.checkNow();
        assertThat(status.blocksSessions()).isPresent();
        call(token).statusCode(200);
        watch.checkNow();
        call(token).statusCode(401).body("reason", equalTo("KEYCLOAK_UNREACHABLE"));
        IamOnProfile.fake.down(false);
        watch.checkNow();
        assertThat(status.blocksSessions()).isEmpty();
        call(token).statusCode(200);
    }

    @Test
    void aTokenSpecificRefusalEndsNoSessionAndCountsAsAFailedPass() throws Exception {
        String first = liveSession(realMissionInProgress(), ME);
        String second = liveSession(realMissionInProgress(), ME);
        String third = liveSession(realMissionInProgress(), ME);
        int start = introspections();
        watch.checkNow();
        int perPass = introspections() - start; // every live token, earlier tests' included
        assertThat(perPass).isGreaterThanOrEqualTo(3);
        IamOnProfile.fake.refuseIntrospectionOf(second); // 400 for this token, the caller is fine
        start = introspections();
        watch.checkNow();
        assertThat(introspections() - start).isEqualTo(perPass); // the pass went on
        assertThat(status.view().failedChecks()).isEqualTo(1);
        call(first).statusCode(200);
        call(second).statusCode(200); // never ended on an unproven cause
        call(third).statusCode(200);
    }

    @Test
    void aRefusedApiCredentialIsAStatusFailureNotARevocation() throws Exception {
        String token = liveSession(realMissionInProgress(), ME);
        vault.put("floor42-api", "wrong-secret-0000".getBytes(UTF_8));
        try {
            watch.checkNow();
            assertThat(status.view().code()).isEqualTo(IamStatus.API_CREDENTIAL_REFUSED);
            assertThat(status.view().reason()).contains("re-enter its secret in Settings");
            assertThat(status.blocksSessions()).isPresent(); // new sessions blocked at once
            call(token).statusCode(200); // a running session is not "revoked": one miss
            watch.checkNow();
            call(token)
                    .statusCode(401)
                    .body("reason", equalTo("KEYCLOAK_REFUSED")); // the existing limit, never REVOKED nor UNREACHABLE
        } finally {
            vault.put("floor42-api", "api-secret-4567".getBytes(UTF_8));
        }
        watch.checkNow();
        assertThat(status.blocksSessions()).isEmpty(); // fixed secret: cleared by the next pass
    }

    @Test
    void noApiSecretInTheVaultIsAStatusFailureNeverUp() throws Exception {
        String token = liveSession(realMissionInProgress(), ME);
        vault.delete("floor42-api");
        try {
            watch.checkNow();
            assertThat(status.view().code()).isEqualTo(IamStatus.API_SECRET_MISSING);
            assertThat(status.view().reason()).contains("floor42-api").contains("Settings");
            assertThat(status.blocksSessions()).isPresent();
            call(token).statusCode(200); // one miss
            watch.checkNow();
            call(token).statusCode(401).body("reason", equalTo("KEYCLOAK_REFUSED")); // never REVOKED
        } finally {
            vault.put("floor42-api", "api-secret-4567".getBytes(UTF_8));
        }
        watch.checkNow();
        assertThat(status.blocksSessions()).isEmpty();
    }

    @Test
    void aPassStopsAtItsDeadlineAndTheNextOneStartsWithWhatItMissed() throws Exception {
        for (int i = 0; i < 4; i++) liveSession(realMissionInProgress(), ME);
        String last = liveSession(realMissionInProgress(), ME);
        watch.clock(new Ticking(20_000_000_000L));
        int start = introspections();
        watch.checkNow();
        // the probe, then the sessions read at 20 s and 40 s; the 60 s read is past the 50 s deadline
        assertThat(introspections() - start).isEqualTo(3);
        var first = watch.behind();
        assertThat(first).hasSizeGreaterThanOrEqualTo(3);
        assertThat(status.view().reason()).contains("ran out of time");
        assertThat(status.view().failedChecks()).isEqualTo(1); // an incomplete pass is a failed one
        call(last).statusCode(200); // never ended on that account
        watch.clock(new Ticking(20_000_000_000L));
        watch.checkNow();
        var askedSecond = new java.util.HashSet<>(first);
        askedSecond.removeAll(watch.behind());
        assertThat(askedSecond)
                .as("the second pass asked two of the ones the first missed")
                .hasSize(2);
        watch.clock(System::nanoTime);
        watch.checkNow();
        assertThat(watch.behind()).isEmpty();
        assertThat(status.blocksSessions()).isEmpty();
    }

    /** A clock that moves {@code step} nanoseconds at every reading: no wall-clock wait. */
    private static final class Ticking implements java.util.function.LongSupplier {
        private final long step;
        private long now;

        Ticking(long step) {
            this.step = step;
        }

        @Override
        public long getAsLong() {
            long t = now;
            now += step;
            return t;
        }
    }

    private static int introspections() {
        return (int) IamOnProfile.fake.requests().stream()
                .filter(r -> r.get("_path").endsWith("/token/introspect"))
                .count();
    }

    @Test
    void keycloakComingBackNudgesTheMissionsThatWaited() throws Exception {
        recoveries.set(0);
        IamOnProfile.fake.down(true);
        watch.checkNow();
        watch.checkNow();
        assertThat(recoveries.get()).isZero();
        IamOnProfile.fake.down(false);
        watch.checkNow();
        assertThat(recoveries.get()).isEqualTo(1);
        watch.checkNow();
        assertThat(recoveries.get()).isEqualTo(1);
    }

    @Test
    void disabledInKeycloakIsRefusedAfterOneCheck() throws Exception {
        String token = liveSession(realMissionInProgress(), ME);
        call(token).statusCode(200);
        IamOnProfile.fake.disableClient("floor42-agent-chris");
        watch.checkNow();
        call(token).statusCode(401).body("reason", equalTo("REVOKED"));
    }

    @Test
    void delegationRevokedInKeycloakIsRefusedAfterOneCheck() throws Exception {
        String token = liveSession(realMissionInProgress(), ME);
        call(token).statusCode(200);
        IamOnProfile.fake.revokeAll();
        watch.checkNow();
        call(token).statusCode(401).body("reason", equalTo("REVOKED"));
    }

    @Test
    void aCompletedMissionCutsItsRunningSessionsButNotALaterRetrospective() throws Exception {
        var s = realMissionInProgress();
        String running = liveSession(s, ME);
        call(running).statusCode(200);
        delegations.closeToWork(s.mission());
        call(running).statusCode(401).body("reason", equalTo("REVOKED"));
        call(liveSession(s, ME)).statusCode(200); // registered after completion: a retrospective's kind
    }
}
