package dev.floor42.domain;

import static dev.floor42.domain.Fixture.GEOFFREY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The director changes the model and effort an agent's next sessions run with; it is journaled. */
@ExtendWith(GoldenFixtureExtension.class)
class AgentSettingsTest {

    final Fixture f = new Fixture();

    @Test
    void theDirectorReconfiguresAnAgent() {
        f.record(f.company.staff().reconfigure(f.director(), GEOFFREY, "claude-sonnet-5-5", "medium"));

        Agent geoffrey = f.company.staff().find(GEOFFREY).orElseThrow();
        assertThat(geoffrey.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(geoffrey.effort()).isEqualTo("medium");
        assertThat(Company.replay(f.journal)
                        .staff()
                        .find(GEOFFREY)
                        .orElseThrow()
                        .model())
                .isEqualTo("claude-sonnet-5-5");
        assertThat(f.company.staff().reconfigure(f.director(), GEOFFREY, "claude-sonnet-5-5", "medium"))
                .as("nothing changes, nothing recorded")
                .isEmpty();
    }

    @Test
    void onlyTheDirectorAndOnlyKnownEfforts() {
        assertThatThrownBy(() -> f.company.staff().reconfigure(f.agent(GEOFFREY), GEOFFREY, "claude-opus-5-5", "max"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> f.company.staff().reconfigure(f.director(), GEOFFREY, "claude-opus-5-5", "turbo"))
                .hasMessageContaining("Effort");
        assertThatThrownBy(() -> f.company.staff().reconfigure(f.director(), GEOFFREY, " ", null))
                .isInstanceOf(DomainException.class);
    }
}
