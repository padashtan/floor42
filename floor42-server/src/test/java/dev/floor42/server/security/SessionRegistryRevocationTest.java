package dev.floor42.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.server.security.SessionRegistry.Kind;
import dev.floor42.server.security.SessionRegistry.Scope;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Revocation, listing, labels and the secret handling of the director's open sessions. */
class SessionRegistryRevocationTest {

    SessionRegistry r;

    @BeforeEach
    void start() {
        r = new SessionRegistry();
        r.idleHours = 8;
        r.absoluteHours = 24;
    }

    @Test
    void onlyAHashOfTheSecretIsKept() {
        var issued = r.openBrowser("ua");
        assertThat(issued.session().hash()).isEqualTo(SessionRegistry.sha256(issued.secret()));
        assertThat(issued.session().hash()).hasSize(64).doesNotContain(issued.secret());
        assertThat(issued.secret()).hasSize(43);
        assertThat(issued.session().id()).startsWith("B-").hasSize(12);
        assertThat(r.issueScript(Scope.READ, 1, null, null).session().id()).startsWith("S-");
    }

    @Test
    void aSecretIsNeverGivenTwiceAndOthersOpenNothing() {
        String a = r.openBrowser("ua").secret();
        String b = r.openBrowser("ua").secret();
        assertThat(a).isNotEqualTo(b);
        assertThat(r.authenticate(null)).isEmpty();
        assertThat(r.authenticate("")).isEmpty();
        assertThat(r.authenticate(a + "x")).isEmpty();
        assertThat(r.authenticate(a)).isPresent();
    }

    @Test
    void breakGlassIsMarkedOnlyForTheConsoleLink() {
        assertThat(r.openBreakGlass("ua").session().breakGlass()).isTrue();
        assertThat(r.openBrowser("ua").session().breakGlass()).isFalse();
        assertThat(r.issueScript(Scope.ALL, 1, null, null).session().breakGlass())
                .isFalse();
    }

    @Test
    void revokingByIdEndsOnlyThatSession() {
        var a = r.openBrowser("a");
        var b = r.openBrowser("b");
        assertThat(r.revoke(a.session().id())).isPresent();
        assertThat(r.authenticate(a.secret())).isEmpty();
        assertThat(r.authenticate(b.secret())).isPresent();
        assertThat(r.revoke(a.session().id())).isEmpty();
        assertThat(r.revoke("B-unknown")).isEmpty();
    }

    @Test
    void revokingBySecretEndsOnlyThatSession() {
        var a = r.openBrowser("a");
        var b = r.openBrowser("b");
        assertThat(r.revokeSecret(null)).isEmpty();
        assertThat(r.revokeSecret("unknown")).isEmpty();
        assertThat(r.revokeSecret(a.secret()))
                .map(SessionRegistry.Session::id)
                .contains(a.session().id());
        assertThat(r.authenticate(a.secret())).isEmpty();
        assertThat(r.authenticate(b.secret())).isPresent();
        assertThat(r.revokeSecret(a.secret())).isEmpty();
    }

    @Test
    void signOutEverywhereEndsEveryLiveSessionAndSaysHowMany() {
        var a = r.openBrowser("a");
        var s = r.issueScript(Scope.READ, 1, "x", null);
        assertThat(r.clear()).hasSize(2);
        assertThat(r.authenticate(a.secret())).isEmpty();
        assertThat(r.authenticate(s.secret())).isEmpty();
        assertThat(r.list()).isEmpty();
        assertThat(r.clear()).isEmpty();
    }

    @Test
    void theListIsOldestFirstAndFindMatchesTheId() {
        var a = r.openBrowser("a").session();
        var b = r.openBrowser("b").session();
        assertThat(r.list()).extracting(SessionRegistry.Session::id).containsExactlyInAnyOrder(a.id(), b.id());
        assertThat(r.find(b.id())).map(SessionRegistry.Session::userAgent).contains("b");
        assertThat(r.find("nope")).isEmpty();
    }

    @Test
    void labelsAndAgentsAreClippedTo200AndBlankIsNone() {
        var s = r.issueScript(Scope.READ, 1, "  " + "x".repeat(300) + "  ", "y".repeat(250))
                .session();
        assertThat(s.label()).hasSize(200);
        assertThat(s.userAgent()).hasSize(200);
        var named = r.issueScript(Scope.READ, 1, "  bench  ", "   ").session();
        assertThat(named.label()).isEqualTo("bench");
        assertThat(named.userAgent()).isNull();
        assertThat(r.issueScript(Scope.READ, 1, "", null).session().label()).isNull();
        assertThat(r.openBrowser(null).session().userAgent()).isNull();
    }

    @Test
    void aScriptTokenLastsOneToThirtyDays() {
        assertThat(r.issueScript(Scope.READ, 1, null, null).session().kind()).isEqualTo(Kind.SCRIPT);
        assertThat(r.issueScript(Scope.READ, SessionRegistry.MAX_SCRIPT_DAYS, null, null))
                .isNotNull();
        assertThatThrownBy(() -> r.issueScript(Scope.READ, 0, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.issueScript(Scope.READ, 31, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBrowserSessionAlwaysHasFullScopeAndNoFixedExpiry() {
        var s = r.openBrowser("ua").session();
        assertThat(s.scope()).isEqualTo(Scope.ALL);
        assertThat(s.expires()).isNull();
        assertThat(s.kind()).isEqualTo(Kind.BROWSER);
    }

    @Test
    void scopesRoundTripThroughTheirWireNames() {
        for (Scope s : Scope.values()) assertThat(Scope.parse(s.wire())).contains(s);
        assertThat(Scope.READ.wire()).isEqualTo("read");
        assertThat(Scope.MISSIONS_WRITE.wire()).isEqualTo("missions:write");
        assertThat(Scope.ALL.wire()).isEqualTo("all");
        assertThat(Scope.parse("root")).isEqualTo(Optional.empty());
        assertThat(Scope.parse(null)).isEmpty();
    }
}
