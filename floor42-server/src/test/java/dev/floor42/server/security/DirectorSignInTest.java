package dev.floor42.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The single-use sign-in link: never handed back to the caller, short-lived, bounded, and a reuse revokes. */
class DirectorSignInTest {

    private static final String BASE = "http://127.0.0.1:7420";

    DirectorSignIn signIn;
    SessionRegistry registry;

    @BeforeEach
    void start() {
        registry = new SessionRegistry();
        registry.idleHours = 8;
        registry.absoluteHours = 24;
        signIn = new DirectorSignIn();
        signIn.delivery = "none";
        signIn.ttlSeconds = 60;
        signIn.registry = registry;
    }

    private static String codeOf(String link) {
        return link.substring(link.indexOf("code=") + 5);
    }

    private String issue() {
        assertThat(signIn.open(BASE)).isTrue();
        return codeOf(signIn.lastLink());
    }

    @Test
    void theLinkPointsAtTheGivenBaseWithAnUnguessableCode() {
        String code = issue();
        assertThat(signIn.lastLink()).isEqualTo(BASE + "/auth?code=" + code);
        assertThat(code).hasSize(32).matches("[A-Za-z0-9_-]+");
        signIn.clear();
        assertThat(issue()).isNotEqualTo(code);
    }

    @Test
    void aSecondLinkTooSoonIsRefusedAndTheFirstStaysTheOnlyOne() {
        String first = issue();
        assertThat(signIn.open(BASE)).isFalse();
        assertThat(codeOf(signIn.lastLink())).isEqualTo(first);
        signIn.noPause();
        assertThat(signIn.open(BASE)).isTrue();
        assertThat(codeOf(signIn.lastLink())).isNotEqualTo(first);
    }

    @Test
    void aValidCodeOpensABreakGlassBrowserSessionOnce() {
        String code = issue();
        String secret = signIn.redeem(code, "agent/1").orElseThrow();
        var session = registry.authenticate(secret).orElseThrow();
        assertThat(session.kind()).isEqualTo(SessionRegistry.Kind.BROWSER);
        assertThat(session.breakGlass()).isTrue();
        assertThat(session.userAgent()).isEqualTo("agent/1");
        assertThat(session.scope()).isEqualTo(SessionRegistry.Scope.ALL);
    }

    @Test
    void aLinkUsedTwiceRevokesTheSessionItOpenedAndIsDead() {
        String code = issue();
        String secret = signIn.redeem(code, "ua").orElseThrow();
        assertThat(signIn.redeem(code, "intruder")).isEmpty();
        assertThat(registry.authenticate(secret)).isEmpty();
        assertThat(registry.list()).isEmpty();
        assertThat(signIn.redeem(code, "again")).isEmpty();
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void unknownOrNearMissCodesOpenNothing() {
        String code = issue();
        assertThat(signIn.redeem(null, "ua")).isEmpty();
        assertThat(signIn.redeem("", "ua")).isEmpty();
        assertThat(signIn.redeem(code + "x", "ua")).isEmpty();
        assertThat(signIn.redeem(code.substring(1), "ua")).isEmpty();
        assertThat(signIn.redeem("A".repeat(32), "ua")).isEmpty();
        assertThat(registry.list()).isEmpty();
        assertThat(signIn.redeem(code, "ua")).isPresent();
    }

    @Test
    void aCodeOlderThanItsLifetimeOpensNothing() throws Exception {
        signIn.ttlSeconds = 0;
        String code = issue();
        Thread.sleep(20);
        assertThat(signIn.redeem(code, "ua")).isEmpty();
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void aLiveCodeSurvivesThePurgeOfOldOnes() {
        String code = issue();
        signIn.noPause();
        issue();
        assertThat(signIn.redeem(code, "ua")).isPresent();
    }

    @Test
    void atMostThirtyTwoLinksArePendingTheOldestIsDropped() {
        signIn.noPause();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 33; i++) codes.add(issue());
        assertThat(signIn.redeem(codes.get(0), "ua")).isEmpty();
        assertThat(signIn.redeem(codes.get(1), "ua")).isPresent();
        assertThat(signIn.redeem(codes.get(32), "ua")).isPresent();
    }

    @Test
    void clearForgetsEveryLink() {
        String code = issue();
        signIn.clear();
        assertThat(signIn.redeem(code, "ua")).isEmpty();
        assertThat(signIn.open(BASE)).isTrue();
    }

    @Test
    void inDockerTheLinkIsPrintedOnStandardOutputAndNeverReturned() {
        signIn.delivery = "STDOUT";
        PrintStream real = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true));
        try {
            assertThat(signIn.open(BASE)).isTrue();
        } finally {
            System.setOut(real);
        }
        String printed = out.toString();
        assertThat(printed).contains("single use, 60 s").contains(BASE + "/auth?code=");
        assertThat(signIn.lastLink()).isNull();
        assertThat(signIn.redeem(codeOf(printed.trim()), "ua")).isPresent();
    }

    @Test
    void theBrowserIsOpenedWithoutAShell() {
        String os = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            assertThat(DirectorSignIn.browserCommand("L"))
                    .containsExactly("rundll32", "url.dll,FileProtocolHandler", "L");
            System.setProperty("os.name", "Mac OS X");
            assertThat(DirectorSignIn.browserCommand("L")).containsExactly("open", "L");
            System.setProperty("os.name", "Linux");
            assertThat(DirectorSignIn.browserCommand("L")).containsExactly("xdg-open", "L");
        } finally {
            System.setProperty("os.name", os);
        }
    }
}
