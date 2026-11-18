package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The summary is cut at 300 characters: a secret must be masked before the cut, not left half-read by it. */
class ToolCatalogMaskTest {

    @Test
    void aTokenStraddlingTheCutLeavesNothingReadable() {
        // Assembled at run time so no scanner reads this file as a leak.
        String token = "aB3dE5fG7h" + "I9jK1lM3nO5pQ7rS9tU1vW3xY5zA7c";
        for (int offset = 255; offset <= 300; offset++) {
            String cmd = "x".repeat(offset - 1) + " " + token + " tail";
            var input = new ObjectMapper().createObjectNode().put("command", cmd);
            String s = ToolCatalog.summary("Bash", input, "/w");
            for (int i = 0; i + 20 <= token.length(); i++)
                assertThat(s).as("offset " + offset).doesNotContain(token.substring(i, i + 20));
        }
    }
}
