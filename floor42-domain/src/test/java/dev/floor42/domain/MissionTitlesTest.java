package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MissionTitlesTest {

    @Test
    void aShortFirstLineIsTheTitleAndBlankLinesBeforeItAreSkipped() {
        assertThat(MissionTitles.fromObjective("\n  Fix the login \nthen the rest"))
                .isEqualTo("Fix the login");
    }

    @Test
    void aLongLineIsCutOnAWordAndAnUnbrokenOneAtTheLimit() {
        String words = "word ".repeat(20);
        assertThat(MissionTitles.fromObjective(words))
                .hasSizeLessThanOrEqualTo(60)
                .endsWith("word");
        assertThat(MissionTitles.fromObjective("x".repeat(100))).hasSize(60);
    }
}
