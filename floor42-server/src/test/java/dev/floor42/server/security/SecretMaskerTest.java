package dev.floor42.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** What looks like a secret never leaves the place it was recorded in. */
class SecretMaskerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void knownCredentialShapesAreMasked() {
        for (String s : new String[] {
            "sk-ant-abcdef123456",
            "ghp_abcdef123456",
            "github_pat_abcdef123456",
            "AKIAABCDEFGH1234",
            "eyJhbGciOi.eyJzdWIiOi.c2lnbmF0dXJl",
            "Bearer abcdefgh12345",
            "basic dXNlcjpwYXNz",
            "password=hunter2",
            "token: 'a b c'",
            "api_key \"k\"",
            "0123456789abcdef01234567",
            "abcdefghijklmnopqrstuvwxyzABCDEF",
            "-----BEGIN RSA PRIVATE KEY-----\nMIIB\n-----END RSA PRIVATE KEY-----"
        }) {
            assertThat(SecretMasker.maskText("x " + s + " y")).as(s).isEqualTo("x [masked] y");
            assertThat(SecretMasker.count(s)).as(s).isPositive();
        }
    }

    @Test
    void credentialsInAUrlAreMasked() {
        assertThat(SecretMasker.maskText("https://user:pw@example.com/x")).doesNotContain("user:pw");
    }

    @Test
    void plainTextIsLeftAlone() {
        for (String s : new String[] {"hello world", "sk-1", "Bearer short", "0123456789abcdef"}) {
            assertThat(SecretMasker.maskText(s)).as(s).isEqualTo(s);
            assertThat(SecretMasker.count(s)).isZero();
        }
        assertThat(SecretMasker.maskText(null)).isNull();
        assertThat(SecretMasker.maskText("")).isEmpty();
        assertThat(SecretMasker.count(null)).isZero();
        assertThat(SecretMasker.count("")).isZero();
    }

    @Test
    void theSecretIsReplacedAndTheRestKept() {
        assertThat(SecretMasker.maskText("use ghp_abcdef123456 now")).isEqualTo("use [masked] now");
        assertThat(SecretMasker.maskText("a ghp_abcdef123456 b sk-ant-abcdef123456 c"))
                .isEqualTo("a [masked] b [masked] c");
        assertThat(SecretMasker.count("a ghp_abcdef123456 b sk-ant-abcdef123456 c"))
                .isEqualTo(2);
    }

    @Test
    void keysNamingASecretAreRecognised() {
        for (String k :
                new String[] {"token", "API_KEY", "client_secret", "Password", "authorization", "cookie", "private-key"
                }) {
            assertThat(SecretMasker.isSecretKey(k)).as(k).isTrue();
        }
        for (String k : new String[] {"name", "path", "mission"})
            assertThat(SecretMasker.isSecretKey(k)).as(k).isFalse();
    }

    @Test
    void aTreeIsMaskedOnACopyAtAnyDepth() throws Exception {
        JsonNode in = JSON.readTree("""
                {"name":"ada","password":"p","nested":{"api_key":{"a":1},"note":"ghp_abcdef123456"},
                 "list":["ok","sk-ant-abcdef123456",{"token":"t"}],"input_tokens":12,"auth_tokens":"x",
                 "author":"bob","token":null,"n":5}""");
        String before = in.toString();
        JsonNode out = SecretMasker.mask(in);
        assertThat(in.toString()).isEqualTo(before);
        assertThat(out.path("name").asText()).isEqualTo("ada");
        assertThat(out.path("password").asText()).isEqualTo("[masked]");
        assertThat(out.path("nested").path("api_key").asText()).isEqualTo("[masked]");
        assertThat(out.path("nested").path("note").asText()).isEqualTo("[masked]");
        assertThat(out.path("list").get(0).asText()).isEqualTo("ok");
        assertThat(out.path("list").get(1).asText()).isEqualTo("[masked]");
        assertThat(out.path("list").get(2).path("token").asText()).isEqualTo("[masked]");
        assertThat(out.path("input_tokens").asInt()).isEqualTo(12);
        assertThat(out.path("auth_tokens").asText()).isEqualTo("[masked]");
        assertThat(out.path("author").asText()).isEqualTo("bob");
        assertThat(out.path("token").isNull()).isTrue();
        assertThat(out.path("n").asInt()).isEqualTo(5);
    }

    @Test
    void aBearerSchemeAfterAKeyDoesNotHideTheCredentialBehindIt() {
        // Found by fuzzing: "token: Bearer x" took "Bearer" as the value and left x in clear.
        assertThat(SecretMasker.maskText("x-auth-token: Bearer abc12345xyz end"))
                .doesNotContain("abc12345xyz");
        assertThat(SecretMasker.maskText("password=basic aaaaaaaaaaaa")).doesNotContain("aaaaaaaaaaaa");
    }

    private static final String Q = "\"";

    @Test
    void everyShapeOfASecretKeyHidesItsValue() {
        String[][] cases = {
            {"x-auth-token: Token abc12345xyz", "abc12345xyz"},
            {"x-api-key: ApiKey abc12345xyz", "abc12345xyz"},
            {"password: Digest abc12345xyz", "abc12345xyz"},
            {"x-auth-token: BEARER abc12345xyz", "abc12345xyz"},
            {"{" + Q + "token" + Q + ":" + Q + "abc12345xyz" + Q + "}", "abc12345xyz"},
            {"{" + Q + "password" + Q + ": " + Q + "hunter2hunter2" + Q + "}", "hunter2hunter2"},
            {"{" + Q + "api_key" + Q + ":" + Q + "abc12345xyz" + Q + "}", "abc12345xyz"},
            {"{" + Q + "client_secret" + Q + " : " + Q + "abc12345xyz" + Q + "}", "abc12345xyz"},
            {"GET /cb?state=1&auth=abc12345xyz HTTP/1.1", "abc12345xyz"},
            {"GET /cb?code=abc12345xyz&x=1", "abc12345xyz"},
            {"/x?KEY=abc12345xyz#top", "abc12345xyz"},
            {"/x?access_token=abc12345xyz", "abc12345xyz"},
            {"/x?refresh_token=abc12345xyz&id_token=zzz98765qqq", "zzz98765qqq"},
            {"https://u:abc12345xyz@host/x", "abc12345xyz"},
        };
        for (String[] c : cases)
            assertThat(SecretMasker.maskText(c[0])).as(c[0]).doesNotContain(c[1]);
        assertThat(SecretMasker.maskText("GET /cb?state=1&page=2")).isEqualTo("GET /cb?state=1&page=2");
    }

    @Test
    void aRunOfCombiningMarksAndRepeatedStartsAreReadInLinearTime() {
        // Found by fuzzing: 40000 marks took 19 s, "://a:" 16000 times 7 s (see MaskingScalingTest for the growth).
        assertThat(SecretMasker.maskText("a" + "́".repeat(40_000))).isNotNull();
        assertThat(SecretMasker.maskText("://a:".repeat(16_000))).isNotNull();
    }

    @Test
    void pastAnyBoundTheValueIsMaskedToItsEnd() {
        String words = "Zq7 words with spaces ".repeat(100);
        String[] inputs = {
            "token \"" + words + "TAILQ\"",
            "password: \"" + words + "TAILQ\"",
            "secret='" + words + "TAILQ'",
            "{" + Q + "token" + Q + ":" + Q + "x".repeat(2400) + "TAILQ" + Q + "}",
            "token \"" + words + "TAILQ",
            "https://" + "u".repeat(600) + ":pw.Q!-SIGQ@host/x",
            "https://u:" + "ab.!".repeat(150) + "SIGQ@host/x",
            "eyJ" + "a".repeat(5000) + ".bbbbbbbbbSIGQ.ccccccccc",
            "eyJabcdef.bbbbbbbbbSIGQ.ccccccccc",
        };
        for (String in : inputs)
            assertThat(SecretMasker.maskText(in)).as(in.length() + " chars").doesNotContain("SIGQ", "TAILQ");
    }

    @Test
    void quotesEscapesAndOtherSpellingsOfAKeyAreRead() {
        String bs = "\\";
        String[] inputs = {
            "{" + Q + "password" + Q + ":" + Q + "ab" + bs + Q + "cSIGQ" + Q + "}",
            "{'token': 'abcSIGQ'}",
            "{'token': 'ab" + bs + "'cSIGQ'}",
            "Authorization: Digest xSIGQ",
            "authorization: digest ySIGQ",
            "Authorization: Negotiate zSIGQ",
            "{" + Q + "auth" + Q + ":" + Q + "vSIGQ" + Q + "}",
            "{" + Q + "credentials" + Q + ": " + Q + "wSIGQ" + Q + "}",
            "GET /cb?code=ab'SIGQ",
            "GET /cb?x=1&amp;code=abSIGQ&y=2",
            "/cb?token=a" + Q + "SIGQ",
        };
        for (String in : inputs) assertThat(SecretMasker.maskText(in)).as(in).doesNotContain("SIGQ");
        assertThat(SecretMasker.maskText("the author wrote: hello, credentials are stored"))
                .isEqualTo("the author wrote: hello, credentials are stored");
        // Prose that happens to say "authorization:" is not a header.
        String prose = "chris ran Bash without an Floor42 authorization: curl y";
        assertThat(SecretMasker.maskText(prose)).isEqualTo(prose);
        assertThat(SecretMasker.maskText("Authorization: AWS4-HMAC-SHA256 Credential=xSIGQ/2026"))
                .doesNotContain("SIGQ");
        assertThat(SecretMasker.maskText("Authorization: abcdefghijkl12345SIGQ"))
                .doesNotContain("SIGQ");
        assertThat(SecretMasker.count("a token \"x\" b")).isEqualTo(1);
    }
}
