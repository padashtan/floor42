package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.TerminalEvent.TerminalClosed;
import dev.floor42.domain.event.TerminalEvent.TerminalOpened;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The director's own terminal (ADR-0041): only the facts opened and closed are journaled, by the director, real. */
@ExtendWith(GoldenFixtureExtension.class)
class TerminalTest {

    final Fixture f = new Fixture(Origin.REAL);

    @Test
    void theDirectorOpensOneTerminalPerProjectAndIdsAreNeverReused() {
        f.record(f.company.terminals().open(f.director(), "C:/work/app"));
        assertThat(f.journal.getLast().event()).isEqualTo(new TerminalOpened("TERM-1", "C:/work/app"));
        assertThat(f.journal.getLast().actor().isDirector()).isTrue();
        assertThatThrownBy(() -> f.company.terminals().open(f.director(), "C:/work/app"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("already open");

        f.record(f.company.terminals().close(f.director(), "TERM-1", "closed", null));
        f.record(f.company.terminals().open(f.director(), "C:/work/app"));
        assertThat(((TerminalOpened) f.journal.getLast().event()).terminal()).isEqualTo("TERM-2");
        f.record(f.company.terminals().close(f.system(), "TERM-2", "exited", 0));
        assertThat(f.journal.getLast().event()).isEqualTo(new TerminalClosed("TERM-2", "C:/work/app", "exited", 0));
        assertThat(Company.replay(f.journal).terminals().openTerminals()).isEmpty();
    }

    @Test
    void noAgentOpensOrClosesATerminalAndNothingSimulated() {
        assertThatThrownBy(() -> f.company.terminals().open(f.agent(Fixture.CHRIS), "C:/work/app"))
                .isInstanceOf(DomainException.class);
        f.record(f.company.terminals().open(f.director(), "C:/work/app"));
        assertThat(Company.replay(f.journal).terminals().openTerminals()).containsEntry("TERM-1", "C:/work/app");
        assertThatThrownBy(() -> f.company.terminals().close(f.agent(Fixture.CHRIS), "TERM-1", "closed", null))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> f.company.terminals().close(f.director(), "TERM-1", "whatever", null))
                .isInstanceOf(DomainException.class);

        Fixture simulated = new Fixture();
        assertThatThrownBy(() -> simulated.company.terminals().open(simulated.director(), "C:/work/app"))
                .isInstanceOf(DomainException.class);
    }
}
