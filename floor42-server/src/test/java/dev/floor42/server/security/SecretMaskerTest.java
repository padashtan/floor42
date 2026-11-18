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

    @Test
    void aSignatureInAQueryStringIsMasked() {
        String sas = "https://acct.blob.core.windows.net/c/f.txt?sv=2022-11-02&sig=AbC%2BdSIGQ&se=2026";
        assertThat(SecretMasker.maskText(sas)).doesNotContain("SIGQ").contains("se=2026");
        assertThat(SecretMasker.maskText("https://b.s3.test/k?X-Amz-Signature=fe12SIGQ"))
                .doesNotContain("SIGQ");
    }

    @Test
    void aCookieHeaderIsMasked() {
        assertThat(SecretMasker.maskText("Cookie: session=s3SIGQ; theme=dark\nAccept: */*"))
                .doesNotContain("SIGQ")
                .contains("Accept: */*");
        assertThat(SecretMasker.maskText("Set-Cookie: sid=abSIGQ; HttpOnly")).doesNotContain("SIGQ");
    }

    @Test
    void aSecretNameWrittenUrlEncodedIsMasked() {
        for (String in : new String[] {
            "redirect=https%3A%2F%2Fapp.test%2Fcb%3Faccess%5Ftoken%3Dab12SIGQ",
            "GET /cb?client%5Fsecret=ab12SIGQ",
            "GET /cb?%74oken=ab12SIGQ",
            "GET /cb?x=1&%73ig=ab12SIGQ",
            "body=password%3Dab12SIGQ",
            "%22api%5Fkey%22%3A%22ab12SIGQ%22"
        }) {
            assertThat(SecretMasker.maskText(in)).as(in).doesNotContain("SIGQ");
        }
    }

    @Test
    void aQueryValueWithABlankInsideIsMaskedWhole() {
        assertThat(SecretMasker.maskText("GET /cb?token=abc1 2xyz&x=1"))
                .doesNotContain("abc1", "2xyz")
                .contains("x=1");
        // When in doubt, mask: every following word of URL characters goes with the value (the director's rule).
        assertThat(SecretMasker.maskText("GET /cb?token=abc1 2xyz HTTP/1.1")).doesNotContain("abc1", "2xyz", "HTTP");
        assertThat(SecretMasker.maskText("see https://x.test/?token=abc and more"))
                .doesNotContain("abc", "and", "more");
        assertThat(SecretMasker.maskText("?token=abc1 xyzw")).doesNotContain("xyzw");
        assertThat(SecretMasker.maskText("?sig=abcd efgh12==")).doesNotContain("abcd", "efgh12");
    }

    @Test
    void aQueryValueStopsAtALineEndAQuoteAnAmpersandAHashAnAngleOrAnOddWord() {
        assertThat(SecretMasker.maskText("?token=abc\nnext line")).contains("next line");
        assertThat(SecretMasker.maskText("?token=abc \"quoted\" tail")).contains("\"quoted\" tail");
        assertThat(SecretMasker.maskText("?token=abc and&x=1 more")).contains("&x=1 more");
        assertThat(SecretMasker.maskText("?token=abc and#frag more")).contains("#frag more");
        assertThat(SecretMasker.maskText("?token=abc <b>bold</b>")).contains("<b>bold</b>");
        assertThat(SecretMasker.maskText("?token=abc and, more")).contains("and, more");
    }

    @Test
    void maskingTwiceChangesNothing() {
        for (String in : new String[] {
            "?token=abc and more",
            "https://hooks.slack" + ".com/services/T00000000/B00000000/XXXXXXXXXXXXXXXXXXXXXXXX",
            "/home/ada/work/garden/src/main/java/dev/floor42/server/Thing.java ghp_" + "Zx9Qw3Er7Ty1".repeat(3),
            "{\"Cookie\": \"sid=s3cr3tv4lue\"}"
        }) {
            String once = SecretMasker.maskText(in);
            assertThat(SecretMasker.maskText(once)).as(in).isEqualTo(once);
        }
    }

    @Test
    void aWebhookOrADownloadLinkOrABareSecretStartingWithASlashIsMasked() {
        String t = "Zx9Qw3Er7Ty1".repeat(3);
        assertThat(SecretMasker.maskText(
                        "https://hooks.slack" + ".com/services/T00000000/B00000000/XXXXXXXXXXXXXXXXXXXXXXXX"))
                .doesNotContain("XXXXXXXXXXXXXXXXXXXXXXXX");
        assertThat(SecretMasker.maskText("https://discord.com/api/webhooks/123456789012345678/" + t + "Zx9Qw3Er7Ty1"))
                .doesNotContain(t);
        String hex = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
        assertThat(SecretMasker.maskText("GET /download/" + hex)).doesNotContain(hex);
        assertThat(SecretMasker.maskText("secret=/" + t + "/" + t)).doesNotContain(t);
        assertThat(SecretMasker.maskText("x /" + t + "/Zx9Qw3Er7Ty1 y")).doesNotContain(t);
    }

    @Test
    void aJsonEscapeIsNotAWindowsPath() {
        String t = "Zx9Qw3Er7Ty1".repeat(3);
        assertThat(SecretMasker.maskText("{\"body\":\"{\\\"value\\\":\\\"" + t + "\\\"}\"}"))
                .doesNotContain(t);
        assertThat(SecretMasker.maskText("\"x\\n" + t + "\"")).doesNotContain(t);
        assertThat(SecretMasker.maskText("a\\t" + t + "\\u0041")).doesNotContain(t);
    }

    @Test
    void aQuotedHeaderValueIsMaskedInJsonAndInADict() {
        for (String in : new String[] {
            "{\"Cookie\": \"sid=s3cr3tv4lue\"}",
            "{'Cookie': 'sid=s3cr3tv4lue'}",
            "{\"Set-Cookie\": \"sid=s3cr3tv4lue; Path=/\"}",
            "{\"Authorization\": \"Zx9Qw3Er7Ty1abcd\"}",
            "{\"Authorization\": \"Bearer s3cr3tv4lue\"}",
            "{'Authorization': 'Bearer s3cr3tv4lue'}"
        }) {
            assertThat(SecretMasker.maskText(in)).as(in).doesNotContain("s3cr3tv4lue", "Zx9Qw3Er7Ty1abcd");
        }
    }

    @Test
    void aUrlInsideAUrlIsMasked() {
        for (String in : new String[] {
            "https://a.test/go?redirect=https://x.test/cb?token=ab12SIGQ&y=2",
            "https://a.test/go?redirect=https://x.test/cb?%74oken=ab12SIGQ",
            "https://a.test/go?redirect=https%3A%2F%2Fx.test%2Fcb%3Ftoken%3Dab12SIGQ%26y%3D2"
        }) {
            assertThat(SecretMasker.maskText(in)).as(in).doesNotContain("SIGQ");
        }
    }

    @Test
    void everyPairOfACookieHeaderIsMasked() {
        String out = SecretMasker.maskText("Cookie: a=oneSIGQ; b=twoSIGQ; c=threeSIGQ\nHost: h.test");
        assertThat(out).doesNotContain("SIGQ").contains("Host: h.test");
        assertThat(SecretMasker.maskText("Set-Cookie: sid=abSIGQ; Path=/; HttpOnly"))
                .doesNotContain("SIGQ");
    }

    @Test
    void aFilePathIsNotASecretByItself() {
        for (String path : new String[] {
            "/home/ada/work/garden/src/main/java/dev/floor42/server/Thing.java",
            "/mnt/c/Users/ada/workspace/garden/.claude/worktrees/v14-n2",
            "~/workspace/garden/floor42-server/src/main/java/dev/floor42",
            "C:\\Users\\chris\\AppData\\Local\\Temp\\floor42-session1813758688293085390\\x.txt",
            "C:/Users/ada/AppData/Local/Temp/floor42-session1813758688293085390/x.txt"
        }) {
            assertThat(SecretMasker.maskText("ran: sh '" + path + "' done"))
                    .as(path)
                    .contains(path);
            assertThat(SecretMasker.count(path)).as(path).isZero();
        }
    }

    @Test
    void aTokenAloneIsStillMaskedAndOneBesideAPathToo() {
        String token = "Zx9Qw" + "3Er7Ty1".repeat(1) + "Zx9Qw3Er7Ty1".repeat(2);
        assertThat(SecretMasker.maskText("x " + token + " y")).doesNotContain(token);
        String path = "/mnt/c/Users/ada/workspace/garden/file.txt";
        String out = SecretMasker.maskText(path + " ghp_" + token + " end");
        assertThat(out).contains(path).doesNotContain(token).contains("end");
        assertThat(SecretMasker.maskText("/mnt/c/Users/ada/workspace/garden/cb?token=ab12SIGQ&y=2"))
                .doesNotContain("SIGQ")
                .contains("/mnt/c/Users/ada/workspace/garden/cb?");
        assertThat(SecretMasker.maskText("/home/ada/work/garden/ghp_" + token + "/x"))
                .doesNotContain(token);
    }

    @Test
    void aQuotedHeaderIsMaskedWithoutTheRestOfTheLine() {
        String out = SecretMasker.maskText(
                "curl -H 'Authorization: Bearer ghp_" + "Zx9Qw3Er7Ty1".repeat(3) + "' https://api.example.test/x");
        assertThat(out).doesNotContain("Zx9Qw3").endsWith("' https://api.example.test/x");
        assertThat(SecretMasker.maskText("curl -H \"Cookie: a=oneSIGQ; b=twoSIGQ\" https://h.test/y"))
                .doesNotContain("SIGQ")
                .endsWith("\" https://h.test/y");
    }

    @Test
    void pathsWithADotRelativePathsAndClassNamesStayReadable() {
        for (String s : new String[] {
            "/home/x/.claude/worktrees/v14-integ/floor42-server/src/main/java/x.java",
            "floor42-server/src/main/java/dev/floor42/server/security/SecretMasker.java:109:",
            "/home/x/src/test/java/dev/x/Http2ConnectionHandlerTest.java",
            "/home/x/src/test/java/dev/x/Http2ConnectionHandlerTest",
            "/mnt/c/Users/x/target/test-data/boot-66f14064-ac34-4162-b52b-e50431e72ecd/sandbox/run.sh"
        }) {
            assertThat(SecretMasker.maskText("see " + s + " now")).as(s).isEqualTo("see " + s + " now");
            assertThat(SecretMasker.count(s)).as(s).isZero();
        }
    }

    @Test
    void proseAfterAKeyWordIsLeftAloneButASecretLookingValueIsNot() {
        assertThat(SecretMasker.maskText("Tests pass in 3s")).isEqualTo("Tests pass in 3s");
        assertThat(SecretMasker.maskText("the token expired")).isEqualTo("the token expired");
        assertThat(SecretMasker.maskText("password hunter2 end")).doesNotContain("hunter2");
        assertThat(SecretMasker.maskText("password correcthorse end")).doesNotContain("correcthorse");
        assertThat(SecretMasker.maskText("password=in")).doesNotContain("=in");
    }

    @Test
    void aCapabilityLeftInAPathOrAFragileSpellingIsStillMasked() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        assertThat(SecretMasker.maskText("curl \"${API}/hooks/" + uuid + "\"")).doesNotContain(uuid);
        assertThat(SecretMasker.maskText("/home/x/hooks/" + uuid)).doesNotContain(uuid);
        assertThat(SecretMasker.maskText("-H 'Cookie: \"v1\"; sid=AbC123shortish'"))
                .doesNotContain("AbC123");
        assertThat(SecretMasker.maskText("https://h.test/cb?a=1&amp;token=abc1 2xyz end"))
                .doesNotContain("2xyz");
        assertThat(SecretMasker.maskText("https://h.test/cb%26token=abc1 2xyz end"))
                .doesNotContain("2xyz");
        assertThat(SecretMasker.maskText("https://h.test/cb%3Ftoken=abc1 2xyz end"))
                .doesNotContain("2xyz");
        String random = "aB3dE5fG7hI/jK9lM1nO3pQ/rS5tU7vW9xY";
        assertThat(SecretMasker.maskText("x " + random + " y")).doesNotContain("jK9lM1");
    }

    @Test
    void theNewShapesAreIdempotent() {
        for (String s : new String[] {
            "/home/x/.claude/worktrees/v14-integ/floor42-server/src/main/java/x.java and password hunter2 here",
            "curl \"${API}/hooks/550e8400-e29b-41d4-a716-446655440000\" token abc1 2xyz"
        }) {
            String once = SecretMasker.maskText(s);
            assertThat(SecretMasker.maskText(once)).isEqualTo(once);
        }
    }

    @Test
    void everySecretKeyNameOfTheTextListIsMasked() {
        for (String s : new String[] {
            "SECRET_KEY='dj!ng@-x#y$z'",
            "private_key=abc",
            "access_key=abc",
            "passphrase=correct",
            "secret-key: abc",
            "client_secret=abc"
        }) {
            String v = s.substring(s.indexOf(s.contains(":") ? ':' : '=') + 1).strip();
            assertThat(SecretMasker.maskText("x " + s + " y")).as(s).doesNotContain(v.replace("'", ""));
        }
        assertThat(SecretMasker.isSecretKey("passphrase")).isTrue();
        assertThat(SecretMasker.isSecretKey("secret_key")).isTrue();
    }

    @Test
    void aValueAfterAnExplicitSecretNameIsAlwaysMasked() {
        for (String s : new String[] {"--password letmein", "passphrase abc", "api_key abc", "apikey abc"}) {
            assertThat(SecretMasker.maskText("run " + s + " now"))
                    .as(s)
                    .doesNotContain("abc")
                    .doesNotContain("letmein");
        }
        assertThat(SecretMasker.maskText("Tests pass in 3s")).isEqualTo("Tests pass in 3s");
        assertThat(SecretMasker.maskText("the token expired")).isEqualTo("the token expired");
    }

    @Test
    void aPasswordHoldingAnAtSignIsMaskedWhole() {
        String out = SecretMasker.maskText("git clone https://u:p@ss@host.test/x.git now");
        assertThat(out).doesNotContain("ss@").doesNotContain("p@").contains("host.test/x.git");
    }

    @Test
    void noPieceOfASecretSurvivesAtAnyOffsetOfALogLine() {
        String filler = "2026-11-18 build step ok see output below ";
        String base = filler.repeat(12).substring(0, 500);
        String[] secrets = {
            "aB3dE5fG7hI9jK1lM3nO5pQ7rS9tU1vW3xY5zA7c",
            "ghp_" + "aB3dE5fG7hI9jK1lM3nO5pQ7rS9tU1vW3xY5",
            "AKIAIOSFODNN7EXAMPLE",
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            "550e8400-e29b-41d4-a716-446655440000"
        };
        for (String secret : secrets) {
            for (int off = 0; off <= 400; off++) {
                String line = base.substring(0, off) + " " + secret + " " + base.substring(off);
                var input = JSON.createObjectNode().put("command", line);
                for (String out : new String[] {
                    SecretMasker.maskText(line), dev.floor42.server.runtime.ToolCatalog.summary("Bash", input, "/w")
                }) {
                    for (int i = 0; i + 20 <= secret.length(); i++)
                        assertThat(out).as(secret + "@" + off).doesNotContain(secret.substring(i, i + 20));
                }
            }
        }
    }

    @Test
    void shellAndProseAroundAnAmbiguousWordStayReadable() {
        for (String s : new String[] {
            "pwd && ls",
            "pwd > out.txt",
            "passwd -d root",
            "passwd alice",
            "No secret found in 12 files",
            "Add password reset flow",
            "the secret is out"
        }) {
            assertThat(SecretMasker.maskText(s)).as(s).isEqualTo(s);
        }
    }

    @Test
    void aValueAfterAFlagOrAnUnambiguousNameIsMasked() {
        for (String s : new String[] {
            "--password letmein",
            "--passphrase abc",
            "--secret abc",
            "--api-key abc",
            "--client-secret abc",
            "--token abc",
            "password letmein",
            "passphrase=correct",
            "api_key abc",
            "apikey abc",
            "private_key abc",
            "access_key abc",
            "secret_key abc",
            "client_secret abc",
            "secretKey=abc",
            "privateKey: abc",
            "accessKey=abc",
            "clientSecret=abc"
        }) {
            String out = SecretMasker.maskText("run " + s + " now");
            assertThat(out)
                    .as(s)
                    .doesNotContain("abc")
                    .doesNotContain("letmein")
                    .doesNotContain("correct");
        }
    }
}
