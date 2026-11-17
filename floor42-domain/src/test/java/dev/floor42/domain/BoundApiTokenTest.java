package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** ADR-0083: a bound script token's row names its subject (kept apart from TerminalTest, whose events are a golden). */
class BoundApiTokenTest {

    final Fixture f = new Fixture(Origin.REAL);

    @Test
    void aBoundTokenNamesItsSubjectAndAnUnboundOneNeither() {
        String sha = "ab".repeat(32);
        f.record(f.company
                .terminals()
                .recordBoundApiTokenIssued(f.director(), "S-1", sha, "all", "2026-11-30T00:00:00Z", null, "sub-1"));
        var issued = (dev.floor42.domain.event.TerminalEvent.ApiTokenIssued)
                f.journal.getLast().event();
        assertThat(issued.subject()).isEqualTo("sub-1");
        assertThat(issued.binding()).isEqualTo("KEYCLOAK_OFFLINE_SESSION");
        assertThatThrownBy(() -> f.company
                        .terminals()
                        .recordBoundApiTokenIssued(f.director(), "S-2", sha, "all", "2026-11-30T00:00:00Z", null, null))
                .isInstanceOf(DomainException.class);
    }
}
