package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.ConsultationKind;
import org.junit.jupiter.api.Test;

/** A reviewer's request for help, read back by Floor42 (ADR-0018). */
class BriefsHelpTest {

    @Test
    void consultNamesARoleThenAQuestion() {
        assertThat(Briefs.help("Findings.\n**CONSULT:** Security: Is a static token acceptable?"))
                .hasValue(new Briefs.Help(ConsultationKind.CONSULT, "security", "Is a static token acceptable?"));
    }

    @Test
    void researchIsJustAQuestion() {
        assertThat(Briefs.help("- RESEARCH: Is JUnit 4.12 still supported?"))
                .hasValue(new Briefs.Help(ConsultationKind.RESEARCH, null, "Is JUnit 4.12 still supported?"));
    }

    @Test
    void noRequestOrAMalformedOne() {
        assertThat(Briefs.help("VERDICT: VALIDATED")).isEmpty();
        assertThat(Briefs.help("CONSULT: just a question without role")).isEmpty();
        assertThat(Briefs.help(null)).isEmpty();
    }
}
