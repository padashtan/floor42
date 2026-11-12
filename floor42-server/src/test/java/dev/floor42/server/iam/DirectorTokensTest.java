package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.security.SessionRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The director's Keycloak tokens: held per live browser session, refreshed before expiry, dropped on sign-out. */
class DirectorTokensTest {

    @TempDir
    Path dir;

    FakeKeycloak kc;
    SessionRegistry registry;
    DirectorTokens held;
    String id;

    @BeforeEach
    void start() throws Exception {
        kc = FakeKeycloak.start("floor42");
        kc.registerClient("floor42-desk", null);
        registry = new SessionRegistry();
        for (String f : new String[] {"idleHours", "absoluteHours"}) {
            var field = SessionRegistry.class.getDeclaredField(f);
            field.setAccessible(true);
            field.setLong(registry, 24);
        }
        held = new DirectorTokens();
        held.sessions = registry;
        held.keycloak = new KeycloakClient(kc.issuer().toString(), new SecretVault(dir), new ObjectMapper());
        id = registry.openBrowser("ua").session().id();
    }

    @AfterEach
    void stop() {
        kc.close();
    }

    private Tokens tokens(String access, String refresh, String idToken, long secondsLeft) {
        return new Tokens(access, refresh, idToken, Instant.now().plusSeconds(secondsLeft));
    }

    private long refreshCalls() {
        return kc.requests().stream()
                .filter(r -> "refresh_token".equals(r.get("grant_type")))
                .count();
    }

    @Test
    void aFreshLoginIsGivenBackWithoutCallingKeycloak() {
        Tokens t = tokens("a", "r", "i", 3600);
        held.put(id, t);
        assertThat(held.live(id)).contains(t);
        assertThat(refreshCalls()).isZero();
    }

    @Test
    void nothingHeldOrAnEndedSessionGivesNothingAndForgets() {
        assertThat(held.live(id)).isEmpty();
        assertThat(held.live("B-unknown")).isEmpty();
        held.put("B-gone", tokens("a", "r", null, 3600));
        assertThat(held.live("B-gone")).isEmpty();
        assertThat(held.loginEndsAt("B-gone")).as("dropped with the session").isEmpty();
        held.put(id, tokens("a", "r", null, 3600));
        registry.revoke(id);
        assertThat(held.live(id)).isEmpty();
        assertThat(held.loginEndsAt(id))
                .as("forgotten once the session is gone")
                .isEmpty();
    }

    @Test
    void aLoginExpiringWithin30SecondsIsRefreshedAndKeepsWhatKeycloakDidNotResend() {
        String refresh = kc.refreshTokenFor("director", "floor42-desk");
        held.put(id, tokens("old-access", refresh, "old-id", 20));
        Tokens now = held.live(id).orElseThrow();
        assertThat(refreshCalls()).isEqualTo(1);
        assertThat(now.accessToken()).isNotEqualTo("old-access");
        assertThat(now.expiresAt()).isAfter(Instant.now().plusSeconds(60));
        assertThat(now.refreshToken()).isNotNull();
        assertThat(now.idToken()).isNotNull();
        assertThat(held.live(id)).contains(now);
        assertThat(refreshCalls()).as("the refreshed login is fresh").isEqualTo(1);
    }

    @Test
    void aLoginJustOutsideTheRefreshWindowIsNotRefreshed() {
        held.put(id, tokens("a", "r", null, 40));
        assertThat(held.live(id)).isPresent();
        assertThat(refreshCalls()).isZero();
    }

    @Test
    void aLoginKeycloakRefusesToRefreshEndsTheLogin() {
        held.put(id, tokens("a", "not-a-refresh-token", null, 5));
        assertThat(held.live(id)).isEmpty();
        assertThat(held.loginEndsAt(id)).isEmpty();
    }

    @Test
    void aLoginWithoutARefreshTokenThatExpiresIsDropped() {
        held.put(id, tokens("a", null, null, 5));
        assertThat(held.live(id)).isEmpty();
        assertThat(held.loginEndsAt(id)).isEmpty();
        assertThat(refreshCalls()).isZero();
    }

    @Test
    void aKeycloakThatIsAwayLeavesTheTokensButGivesNoneNow() {
        String refresh = kc.refreshTokenFor("director", "floor42-desk");
        held.put(id, tokens("a", refresh, null, 5));
        kc.down(true);
        assertThat(held.live(id)).isEmpty();
        kc.down(false);
        assertThat(held.live(id)).isPresent();
    }

    @Test
    void signOutDropsTheTokensAndEndsTheKeycloakSession() {
        held.put(id, tokens("a", "refresh-value", null, 3600));
        held.signOut(id);
        assertThat(held.live(id)).isEmpty();
        assertThat(kc.requests()).anyMatch(r -> r.get("_path").endsWith("/logout"));
    }

    @Test
    void signOutNeverFailsBecauseKeycloakDid() {
        kc.logoutStatus(500);
        held.put(id, tokens("a", "refresh-value", null, 3600));
        held.signOut(id);
        assertThat(held.live(id)).isEmpty();
        held.signOut(id);
        held.signOut("B-unknown");
    }

    @Test
    void signOutWithoutARefreshTokenCallsNothing() {
        held.put(id, tokens("a", null, null, 3600));
        held.signOut(id);
        assertThat(kc.requests()).noneMatch(r -> r.get("_path").endsWith("/logout"));
        assertThat(held.live(id)).isEmpty();
    }

    @Test
    void removeForgetsOnlyThatSession() {
        String other = registry.openBrowser("ua").session().id();
        held.put(id, tokens("a", "r", null, 3600));
        held.put(other, tokens("b", "r", null, 3600));
        held.remove(id);
        assertThat(held.live(id)).isEmpty();
        assertThat(held.live(other)).isPresent();
    }

    private static String jwtWithExp(long exp) {
        String h = Jwts.base64url("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String p = Jwts.base64url(("{\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return h + "." + p + ".c2ln";
    }

    @Test
    void theLoginEndsWhenTheRefreshTokenDoesElseWhenTheAccessTokenDoes() {
        Instant accessEnds = Instant.now().plusSeconds(3600);
        held.put(id, new Tokens("a", jwtWithExp(1_900_000_000L), null, accessEnds));
        assertThat(held.loginEndsAt(id)).contains(Instant.ofEpochSecond(1_900_000_000L));
        held.put(id, new Tokens("a", "opaque", null, accessEnds));
        assertThat(held.loginEndsAt(id)).contains(accessEnds);
        held.put(id, new Tokens("a", jwtWithExp(0), null, accessEnds));
        assertThat(held.loginEndsAt(id)).contains(accessEnds);
        assertThat(held.loginEndsAt("B-none")).isEmpty();
    }

    @Test
    void anyGivesTheFreshestLiveLoginOfAllBrowsers() {
        assertThat(held.any()).isEmpty();
        String other = registry.openBrowser("ua").session().id();
        held.put(id, tokens("soon", "r", null, 600));
        held.put(other, tokens("later", "r", null, 7200));
        held.put("B-gone", tokens("latest", "r", null, 99999));
        assertThat(held.any()).map(Tokens::accessToken).contains("later");
    }
}
