package dev.floor42.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The same cases as ui/names.test.ts: the daemon and the hire form compare two names the same way. */
class NameNormalisationTest {

    @Test
    void oneNameWhateverTheCaseTheWidthTheMarksOrTheSpaces() {
        for (String twin : new String[] {"dev", " DEV ", "D​ev", "Ｄｅｖ", "D‎ev", "De͏v", "Dev️"}) {
            assertThat(AgentsResource.sameName(twin)).as(twin).isEqualTo("dev");
        }
        for (String twin : new String[] {"Mary  Ann", "Mary Ann", "Mary　Ann", "Mary Ann"}) {
            assertThat(AgentsResource.sameName(twin)).as(twin).isEqualTo("mary ann");
        }
        assertThat(AgentsResource.sameName("Devon")).isNotEqualTo("dev");
    }
}
