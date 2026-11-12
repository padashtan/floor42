package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The workshop's whole diff stays within its bounds, and says how many changes it left out. */
class WholeDiffTest {

    @Test
    void pastTheFileBoundTheRestIsOmittedAndNeverRead() {
        List<String> read = new ArrayList<>();
        WorkshopFiles.WholeDiff d = WorkshopFiles.bounded(
                List.of("a", "b", "c", "d"),
                p -> {
                    read.add(p);
                    return "diff " + p + "\n";
                },
                2,
                1_000);

        assertThat(d.diff()).isEqualTo("diff a\ndiff b\n");
        assertThat(d.omitted()).isEqualTo(2);
        assertThat(read).as("no git call past the bound").containsExactly("a", "b");
    }

    @Test
    void aDiffThatWouldPassTheCharacterBoundIsOmittedWhileSmallerOnesStillFit() {
        WorkshopFiles.WholeDiff d = WorkshopFiles.bounded(
                List.of("a", "big", "b"), p -> p.equals("big") ? "x".repeat(50) : p + "\n", 10, 10);

        assertThat(d.diff()).isEqualTo("a\nb\n");
        assertThat(d.diff().length()).isLessThanOrEqualTo(10);
        assertThat(d.omitted()).isEqualTo(1);
    }

    @Test
    void aFileTooLargeToReadIsOmitted() {
        WorkshopFiles.WholeDiff d =
                WorkshopFiles.bounded(List.of("a", "huge"), p -> p.equals("huge") ? null : "a\n", 10, 100);

        assertThat(d.diff()).isEqualTo("a\n");
        assertThat(d.omitted()).isEqualTo(1);
    }

    @Test
    void theRealBoundsAreTheDocumentedOnes() {
        assertThat(Workshop.MAX_DIFF_FILES).isEqualTo(500);
        assertThat(Workshop.MAX_DIFF_CHARS).isEqualTo(2_000_000);
    }
}
