package dev.floor42.server.security;

import dev.floor42.server.Scaling;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link SecretMasker} and {@link ReplaySanitizer} read agent output: time grows linearly with its length. */
class MaskingScalingTest {

    private static final List<String> UNITS = List.of(
            "\u0301",
            "\u030a",
            "\u200d",
            "\ufe0f",
            "a",
            "0",
            "a1",
            " ",
            "\t",
            "://a:",
            "eyJ",
            "eyJa.",
            "token ",
            "token \"",
            "password=",
            "bearer ",
            "bearer a",
            "sk-",
            "ghp_",
            "AKIA",
            "-----BEGIN PRIVATE KEY-----",
            "?token=",
            "&code=",
            "\"token\":",
            "x-api-key: ApiKey ",
            "C:\\",
            "C:\\Users\\",
            "\\\\a",
            "/home/",
            "/c/Users/",
            "/mnt/c/Users/a",
            "0123456789abcdef");

    @Test
    void maskTextIsLinear() {
        for (String u : UNITS) Scaling.linear("maskText " + u, "", u, SecretMasker::maskText);
        Scaling.linear("maskText hex then mark", "", "0123456789abcdef0123456789abcdef\u0301 ", SecretMasker::maskText);
    }

    @Test
    void countIsLinear() {
        for (String u : UNITS) Scaling.linear("count " + u, "", u, SecretMasker::count);
    }

    @Test
    void theReplaySanitizerIsLinear() {
        Map<String, String> roots = Map.of("C:\\Users\\Ada\\work", "<repository>", "/home/ada/work", "<repository>");
        for (String u : UNITS)
            Scaling.linear(
                    "sanitizer " + u,
                    "",
                    u,
                    s -> new ReplaySanitizer("tok-123-secret-value", roots).text(1, "text", s));
    }
}
