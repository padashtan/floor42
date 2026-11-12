package dev.floor42.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The director's rule: above five open debt items, nothing new lands until the debt is paid back (docs/DEBT.md). */
class DebtGateTest {

    static final int LIMIT = 5;

    @Test
    void openDebtStaysWithinTheLimit() throws Exception {
        List<String> open = Files.readAllLines(Path.of("..", "docs", "DEBT.md")).stream()
                .filter(l -> l.startsWith("- [ ] D"))
                .toList();

        assertThat(open)
                .as("Open debt in docs/DEBT.md exceeds %d items: pay some back before adding features", LIMIT)
                .hasSizeLessThanOrEqualTo(LIMIT);
    }
}
