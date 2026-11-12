package dev.floor42.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.floor42.server.FuzzBudget;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;

/**
 * {@link SecretMasker#maskText} and {@link ReplaySanitizer}: a credential planted in any text never comes out, the
 * session token never comes out, a clipped text stays within its bound, all in a time budget.
 */
@Tag("fuzz")
class SecretMaskingFuzzTest {

    private static final String BODY_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String[] PREFIXES = {
        "ghp_", "sk-ant-", "sk_live_", "glpat-", "npm_", "AKIA", "xoxb-", "Bearer ", "basic "
    };
    private static final String[] NOISE = {
        " ", "\n", "=", ":", "\"", "'", "token ", "password=", "Authorization: ", "/", "C:\\Users\\a\\"
    };

    private static String body(FuzzedDataProvider d, int min, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = d.consumeInt(min, max); i > 0; i--)
            b.append(BODY_CHARS.charAt(d.consumeInt(0, BODY_CHARS.length() - 1)));
        return b.toString();
    }

    /**
     * Free text around the credential, without a character a key or a token is made of: a word like {@code ghp_Bearer} in front is itself a
     * credential, and what the masker then leaves after it is not the planted one.
     */
    private static String noise(FuzzedDataProvider d) {
        return d.consumeBoolean() ? d.consumeString(30).replaceAll("[A-Za-z0-9_+/=.~-]", " ") : d.pickValue(NOISE);
    }

    @FuzzTest
    void aPlantedCredentialDoesNotSurvive(FuzzedDataProvider d) {
        String body = body(d, 12, 40);
        String secret = d.pickValue(PREFIXES) + body;
        String text = noise(d) + secret + noise(d);
        String out = SecretMasker.maskText(text);
        assertThat(out).as(text).doesNotContain(body);
    }

    @FuzzTest
    void maskingAnyTextIsFastAndNeverGrowsUnbounded(FuzzedDataProvider d) {
        String text = d.consumeRemainingAsString();
        FuzzBudget.within(Duration.ofSeconds(10), () -> {
            String out = SecretMasker.maskText(text);
            assertThat(out.length()).isLessThanOrEqualTo(text.length() * SecretMasker.MASK.length());
        });
    }

    @FuzzTest
    void theReplayNeverCarriesTheTokenAndStaysClipped(FuzzedDataProvider d) {
        String token = "tok-" + body(d, 12, 32);
        Map<String, String> roots = new LinkedHashMap<>();
        roots.put("C:\\Users\\Ada\\work\\garden", "<repository>");
        roots.put("/home/ada/work/garden", "<repository>");
        ReplaySanitizer s = new ReplaySanitizer(token, roots);
        String text = d.consumeString(300) + token + d.consumeRemainingAsString();
        FuzzBudget.within(Duration.ofSeconds(10), () -> {
            String t = s.text(1, "text", text);
            String o = s.output(2, "output", text);
            assertThat(t).doesNotContain(token);
            assertThat(o).doesNotContain(token);
            assertThat(t.length()).isLessThanOrEqualTo(ReplaySanitizer.TEXT_MAX + 20);
            assertThat(o.length()).isLessThanOrEqualTo(ReplaySanitizer.OUTPUT_MAX + 20);
        });
    }
}
