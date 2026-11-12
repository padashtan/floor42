package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** An id is short, printable, and never a way to another folder or another log line (CodeQL java/path-injection). */
class IdentifierTest {

    @Test
    void anIdHoldsNoSeparatorNoLineBreakAndIsNoParentFolder() {
        for (String bad : new String[] {
            null, "", ".", "..", "...", "a/b", "a\\b", "../M-1", "M-1\n", "M-1\r\nforged", "M 1", "é", "x".repeat(129)
        }) {
            assertThatThrownBy(() -> new MissionId(bad))
                    .as("mission id %s", bad)
                    .isInstanceOf(InvalidIdentifierException.class);
            assertThatThrownBy(() -> new AgentId(bad)).isInstanceOf(InvalidIdentifierException.class);
        }
    }

    @Test
    void theIdsFloor42MakesAreAccepted() {
        assertThat(new MissionId("M-12").value()).isEqualTo("M-12");
        assertThat(new AgentId("dev.backend_2").value()).isEqualTo("dev.backend_2");
        assertThat(Identifier.check("M-1:0b6f-11", "session")).isEqualTo("M-1:0b6f-11");
        assertThat(new AgentId(".hidden").value()).isEqualTo(".hidden");
    }
}
