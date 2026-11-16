package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefaultPermissionsTest {

    @Test
    void anExternalMcpToolAsksByDefaultAndTheDirectorsLineWins() {
        assertThat(DefaultPermissions.of(Optional.empty(), "mcp__floor42__call_mcp"))
                .isEqualTo(Decision.ASK);
        assertThat(DefaultPermissions.of(Optional.of(Decision.DENY), "mcp__floor42__call_mcp"))
                .isEqualTo(Decision.DENY);
    }
}
