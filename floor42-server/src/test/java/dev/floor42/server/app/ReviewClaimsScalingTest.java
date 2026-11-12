package dev.floor42.server.app;

import dev.floor42.server.Scaling;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link ReviewClaims} reads the reviewer's text: time grows linearly with its length. */
class ReviewClaimsScalingTest {

    private static final List<String> UNITS = List.of(
            "\u0301",
            "\u030a",
            "\u200d",
            "\ufe0f",
            "a",
            " ",
            "\t",
            "*",
            "_",
            ":",
            "change",
            "change:",
            " | change: ",
            "RESERVATION",
            "RESERVATION C1: x\n",
            "> ",
            "- ",
            "```\n",
            "\n");
    private static final List<String> PREFIXES = List.of("", "RESERVATION C2: x", "RESERVATION: a | CHANGE: ", "NOTE ");

    @Test
    void ofIsLinear() {
        for (String p : PREFIXES)
            for (String u : UNITS) Scaling.linear("ReviewClaims " + p + " + " + u, p, u, ReviewClaims::of);
    }
}
