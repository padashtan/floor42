package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OriginTest {

    @Test
    void onlySimulatedAndRealExist() {
        // Adding a third origin is a governance change (ADR-0005), not a casual edit.
        assertThat(Origin.values()).containsExactly(Origin.SIMULATED, Origin.REAL);
    }
}
