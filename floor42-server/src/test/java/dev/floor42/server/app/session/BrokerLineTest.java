package dev.floor42.server.app.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The broker's own result line keeps its jti through masking; nothing else gets that exception. */
class BrokerLineTest {

    private static final String JTI = "09d81046-33d1-4312-9e6f-3925d03a06b7";
    private static final String LINE =
            "Resource demo-api GET /api/users -> 200 (exchanged token " + JTI + " for target-api)";

    @Test
    void theJtiOfAGatewayLineSurvivesAndTheBodyIsStillMasked() {
        String out = BrokerLine.mask(LINE + "\npassword=hunter2 ghp_abcdef123456", "mcp__floor42__call_resource");
        assertThat(out).startsWith(LINE + "\n").doesNotContain("hunter2").doesNotContain("ghp_");
        assertThat(BrokerLine.mask(
                        "MCP demo-api echo -> ok (exchanged token onrtac:" + JTI + " for target-api, fga:m1)",
                        "mcp__floor42__call_mcp"))
                .contains("onrtac:" + JTI);
    }

    @Test
    void aSecretInTheLineOrAnotherToolOrAnotherShapeIsMasked() {
        assertThat(BrokerLine.mask(LINE, "Bash")).doesNotContain(JTI);
        assertThat(BrokerLine.mask(LINE, null)).doesNotContain(JTI);
        String spoof = "Resource x GET /p -> 200 (exchanged token ghp_abcdef123456 for t)";
        assertThat(BrokerLine.mask(spoof, "mcp__floor42__call_resource")).doesNotContain("ghp_");
        String path = "Resource x GET /p?token=abcd1234 -> 200 (exchanged token " + JTI + " for t)";
        assertThat(BrokerLine.mask(path, "mcp__floor42__call_resource"))
                .doesNotContain("abcd1234")
                .contains(JTI);
        assertThat(BrokerLine.mask(
                        "Resource x GET /p -> 200 (exchanged token " + JTI + " for t) tail",
                        "mcp__floor42__call_resource"))
                .doesNotContain(JTI);
    }

    @Test
    void aForeignServersToolOfTheSameNameGetsNoException() {
        assertThat(BrokerLine.mask(LINE, "mcp__x__call_resource")).doesNotContain(JTI);
        assertThat(BrokerLine.mask(LINE, "mcp__floor42__call_resource")).contains(JTI);
    }
}
