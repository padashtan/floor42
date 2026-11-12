package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.PreferenceEvent.UnsafeModeChanged;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Unsafe mode (ADR-0063): the director alone, for a real reason, and every switch is journaled. */
@ExtendWith(GoldenFixtureExtension.class)
class UnsafeModeTest {

    final Fixture f = new Fixture(Origin.REAL);

    @Test
    void offByDefaultAndSwitchedOnAndOffByTheDirectorWithEachSwitchJournaled() {
        assertThat(f.company.preferences().unsafeMode()).isFalse();
        f.record(f.company.preferences().setUnsafeMode(f.director(), true, " WSL is not installed here "));
        assertThat(f.company.preferences().unsafeMode()).isTrue();
        assertThat(f.company.preferences().setUnsafeMode(f.director(), true, "again"))
                .as("the state in force records nothing")
                .isEmpty();
        assertThat(Company.replay(f.journal).preferences().unsafeMode()).isTrue();
        f.record(f.company.preferences().setUnsafeMode(f.director(), false, null));
        assertThat(Company.replay(f.journal).preferences().unsafeMode()).isFalse();
        assertThat(f.journal.stream().map(e -> e.event()).filter(UnsafeModeChanged.class::isInstance))
                .extracting(e -> ((UnsafeModeChanged) e).on(), e -> ((UnsafeModeChanged) e).reason())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(true, "WSL is not installed here"),
                        org.assertj.core.groups.Tuple.tuple(false, null));
    }

    @Test
    void onlyTheDirectorAndNeverWithoutAReason() {
        assertThatThrownBy(() -> f.company.preferences().setUnsafeMode(f.agent(CHRIS), true, "x"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> f.company.preferences().setUnsafeMode(f.director(), true, " "))
                .hasMessageContaining("reason");
        assertThat(f.company.preferences().unsafeMode()).isFalse();
    }

    @Test
    void aSimulatedDecisionIsRefused() {
        Fixture sim = new Fixture(Origin.SIMULATED);
        assertThatThrownBy(() -> sim.company.preferences().setUnsafeMode(sim.director(), true, "x"))
                .isInstanceOf(DomainException.class);
    }
}
