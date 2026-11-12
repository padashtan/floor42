package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** What a plan's text may send to the sandbox: only the essential tools' own command names. */
class MissingToolsTest {

    @Test
    void aNameFromThePlansTextIsNeverProbedOnlyTheEssentialTables() {
        List<String> asked =
                MissingTools.asked(List.of("ffmpeg", "x;rm", "headless-chrome", "chromium", "pdftotext"), null);

        assertThat(asked)
                .containsExactly("chromium", "chromium-browser", "google-chrome", "google-chrome-stable", "pdftotext")
                .doesNotContain("ffmpeg", "headless-chrome");
        assertThat(MissingTools.essentials(List.of("ffmpeg", "browser", "soffice")))
                .containsExactly("chromium", "libreoffice");
    }
}
