package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ResourceBrokerCutTest {

    private static final String TOKEN = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl";

    @Test
    void aTokenCutByTheSizeCapLeavesNoPrefixOfItInTheBody() {
        byte[] echoed = ("pad " + TOKEN).getBytes(StandardCharsets.UTF_8);
        // the cap falls inside the token: 4 + 20 characters kept
        String body = ResourceBroker.cutAndScrub(echoed, 24, TOKEN);
        assertThat(body).doesNotContain(TOKEN.substring(0, 8)).endsWith("[redacted]");
    }

    @Test
    void aWholeTokenIsScrubbedAndShortPrefixesSurvive() {
        assertThat(ResourceBroker.cutAndScrub(("a " + TOKEN + " b").getBytes(StandardCharsets.UTF_8), 100, TOKEN))
                .isEqualTo("a [redacted] b");
        assertThat(ResourceBroker.cutAndScrub("ok eyJh".getBytes(StandardCharsets.UTF_8), 100, TOKEN))
                .isEqualTo("ok eyJh");
    }
}
