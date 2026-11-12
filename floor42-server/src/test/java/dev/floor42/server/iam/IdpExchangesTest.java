package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** The exchanges log shows what was asked and answered, and never a secret, a token, a password or a code. */
class IdpExchangesTest {

    private static final String SECRET = "s3cr3t-value-never-shown";

    private static String jwt(String payload) {
        Base64.Encoder b = Base64.getUrlEncoder().withoutPadding();
        return b.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".SIGNATURE" + SECRET;
    }

    @Test
    void masks_every_form_value_but_the_shown_fields() {
        var form = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange&client_id=floor42-agent-ada"
                + "&client_secret=" + SECRET + "&subject_token=" + SECRET + "&audience=floor42-api&code=" + SECRET
                + "&code_verifier=" + SECRET + "&refresh_token=" + SECRET + "&password=" + SECRET;
        var shown = IdpExchanges.maskForm(form);
        assertThat(shown)
                .containsEntry("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                .containsEntry("client_id", "floor42-agent-ada")
                .containsEntry("audience", "floor42-api")
                .containsEntry("client_secret", IdpExchanges.MASK)
                .containsEntry("subject_token", IdpExchanges.MASK)
                .containsEntry("code", IdpExchanges.MASK)
                .containsEntry("code_verifier", IdpExchanges.MASK)
                .containsEntry("refresh_token", IdpExchanges.MASK)
                .containsEntry("password", IdpExchanges.MASK);
        assertThat(shown.toString()).doesNotContain(SECRET);
    }

    @Test
    void keeps_the_claims_that_say_who_and_never_the_token() throws Exception {
        var log = new IdpExchanges();
        String body = "{\"access_token\":\""
                + jwt("{\"azp\":\"floor42-agent-ada\",\"aud\":\"floor42-api\","
                        + "\"act\":{\"sub\":\"director\"},\"exp\":1900000000,\"secret_claim\":\"" + SECRET + "\"}")
                + "\",\"refresh_token\":\"" + SECRET + "\",\"id_token\":\"" + SECRET
                + "\",\"expires_in\":300,\"token_type\":\"Bearer\"}";
        log.record(
                Instant.now(),
                "token exchange",
                "POST",
                URI.create("http://kc/realms/r/protocol/openid-connect/token?x=" + SECRET),
                "client_secret=" + SECRET,
                200,
                12,
                body,
                null);
        var e = log.list().getFirst();
        assertThat(e.path()).isEqualTo("/realms/r/protocol/openid-connect/token");
        assertThat(e.answer())
                .containsEntry("access_token", IdpExchanges.MASK)
                .containsEntry("refresh_token", IdpExchanges.MASK)
                .containsEntry("expires_in", "300")
                .containsEntry("access_token.azp", "floor42-agent-ada")
                .containsEntry("access_token.act", "{\"sub\":\"director\"}")
                .doesNotContainKey("access_token.secret_claim");
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(log.list()))
                .doesNotContain(SECRET);
    }

    @Test
    void masks_a_client_secret_answer_and_counts_lists() {
        var log = new IdpExchanges();
        assertThat(log.answer("{\"type\":\"secret\",\"value\":\"" + SECRET + "\"}"))
                .containsEntry("value", IdpExchanges.MASK);
        assertThat(log.answer("{\"clientId\":\"floor42-agent-ada\",\"secret\":\"" + SECRET + "\"}"))
                .containsEntry("clientId", "floor42-agent-ada")
                .containsEntry("secret", IdpExchanges.MASK);
        assertThat(log.answer("[{\"secret\":\"" + SECRET + "\"},{}]"))
                .containsOnlyKeys("items")
                .containsEntry("items", "2");
        assertThat(log.answer("not json " + SECRET)).isEmpty();
    }

    @Test
    void keeps_the_newest_three_hundred_newest_first() {
        var log = new IdpExchanges();
        for (int i = 0; i < IdpExchanges.CAPACITY + 5; i++) {
            log.record(
                    Instant.EPOCH.plusSeconds(i), "op" + i, "GET", URI.create("http://kc/x"), null, 200, 1, null, null);
        }
        assertThat(log.list()).hasSize(IdpExchanges.CAPACITY);
        assertThat(log.list().getFirst().op()).isEqualTo("op" + (IdpExchanges.CAPACITY + 4));
        assertThat(log.list().getLast().op()).isEqualTo("op5");
    }
}
