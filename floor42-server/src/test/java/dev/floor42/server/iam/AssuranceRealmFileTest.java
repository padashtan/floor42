package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What the realm file gives the assured approvals (ADR-0081): the strong level only after a second factor that exists
 * already. Reads the file; the behaviour against a real Keycloak is the integration run's (the container part is
 * described, not run here).
 */
class AssuranceRealmFileTest {

    static JsonNode realm;

    @BeforeAll
    static void read() throws IOException {
        realm = new ObjectMapper().readTree(Files.readString(Path.of("..", "iam", "realm", "floor42-realm.json")));
    }

    private static JsonNode flow(String alias) {
        for (JsonNode f : realm.path("authenticationFlows"))
            if (alias.equals(f.path("alias").asText())) return f;
        throw new AssertionError("no flow " + alias);
    }

    private static List<String> authenticators(String alias) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : flow(alias).path("authenticationExecutions"))
            out.add(e.path("authenticator").asText());
        return out;
    }

    private static JsonNode config(String alias) {
        for (JsonNode c : realm.path("authenticatorConfig"))
            if (alias.equals(c.path("alias").asText())) return c.path("config");
        throw new AssertionError("no config " + alias);
    }

    @Test
    void theNamesOfTheLevelsAreMappedAndTheBrowserFlowIsOurs() {
        assertThat(realm.path("browserFlow").asText()).isEqualTo("floor42-browser");
        JsonNode map = realm.path("attributes").path("acr.loa.map");
        assertThat(map.asText()).contains("\"floor42-basic\":1").contains("\"floor42-strong\":2");
    }

    @Test
    void theStrongLevelAsksTheSecondFactorEveryTime() {
        assertThat(authenticators("floor42-loa2"))
                .containsExactly("conditional-level-of-authentication", "auth-otp-form");
        assertThat(config("floor42-loa2-cond").path("loa-condition-level").asText())
                .isEqualTo("2");
        assertThat(config("floor42-loa2-cond").path("loa-max-age").asText())
                .as("asked again at each step-up")
                .isEqualTo("0");
        assertThat(authenticators("floor42-loa1"))
                .contains("auth-username-password-form")
                .doesNotContain("auth-otp-form");
        assertThat(config("floor42-loa1-cond").path("loa-condition-level").asText())
                .isEqualTo("1");
    }

    @Test
    void everyFlowAFlowNamesExists() {
        List<String> known = new ArrayList<>();
        realm.path("authenticationFlows").forEach(f -> known.add(f.path("alias").asText()));
        for (JsonNode f : realm.path("authenticationFlows"))
            for (JsonNode e : f.path("authenticationExecutions"))
                if (e.path("authenticatorFlow").asBoolean())
                    assertThat(known).contains(e.path("flowAlias").asText());
    }

    @Test
    void theStrongStepUpOfAUserWithNoFactorCannotEnrolOne() {
        // Measured: with Configure OTP on, a password alone enrolled a factor in the step-up and got floor42-strong.
        JsonNode totp = null;
        for (JsonNode a : realm.path("requiredActions"))
            if ("CONFIGURE_TOTP".equals(a.path("alias").asText())) totp = a;
        assertThat(totp).isNotNull();
        assertThat(totp.path("enabled").asBoolean()).isFalse();
        for (JsonNode a : realm.path("requiredActions"))
            assertThat(a.path("defaultAction").asBoolean())
                    .as(a.path("alias").asText())
                    .isFalse();
    }

    @Test
    void theReviewerRoleExistsAndNothingGrantsItByDefault() {
        List<String> roles = new ArrayList<>();
        realm.path("roles").path("realm").forEach(r -> roles.add(r.path("name").asText()));
        assertThat(roles).containsExactlyInAnyOrder("floor42-director", "floor42-reviewer");
        assertThat(realm.path("defaultRole").isMissingNode()).isTrue();
        for (JsonNode u : realm.path("users"))
            assertThat(u.path("realmRoles").toString()).doesNotContain("reviewer");
    }

    @Test
    void theDeskClientStillRequiresPkce() {
        for (JsonNode c : realm.path("clients"))
            if ("floor42-desk".equals(c.path("clientId").asText()))
                assertThat(c.path("attributes")
                                .path("pkce.code.challenge.method")
                                .asText())
                        .isEqualTo("S256");
    }
}
