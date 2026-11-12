package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A record built from a list keeps its own copy: the caller's later changes never reach it. */
class DefensiveCopyTest {

    @Test
    void aRiskKeepsItsOwnListOfReasons() {
        List<RiskReason> reasons = new ArrayList<>(List.of(RiskReason.POLICY_DENIED));
        AgentSession.Risk risk = new AgentSession.Risk(RiskLevel.values()[0], reasons, Instant.EPOCH);
        reasons.add(RiskReason.COMMAND_DENIED);
        assertThat(risk.reasons()).containsExactly(RiskReason.POLICY_DENIED);
        assertThatThrownBy(() -> risk.reasons().add(RiskReason.COMMAND_DENIED))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aNullListStaysNull() {
        assertThat(new AgentSession.Risk(RiskLevel.values()[0], null, Instant.EPOCH).reasons())
                .isNull();
    }

    @Test
    void aRefusalKeepsItsCause() {
        Exception cause = new IllegalArgumentException("not a number");
        assertThat(new DomainException(Refusal.INVALID_ARGUMENT, "bad", cause).getCause())
                .isSameAs(cause);
        assertThat(new DomainException("X", "bad", cause).code()).isEqualTo("X");
    }
}
