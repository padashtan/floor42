package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.app.Delivered.DeliveredBranch;
import dev.floor42.server.app.Delivered.DeliveredFile;
import dev.floor42.server.app.Delivered.Source;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a close copies (ADR-0051): each file of an agent once, the worktree's bytes when it changed after its commit. */
class DeliverablesHomeItemsTest {

    static DeliveredFile file(String path, boolean committed, String status, boolean ignored) {
        return new DeliveredFile(path, null, status, 10L, committed ? "abc" : null, committed, ignored, false, "TEXT");
    }

    static DeliveredBranch branch(String agent, List<DeliveredFile> committed, List<DeliveredFile> uncommitted) {
        return new DeliveredBranch(
                agent,
                "floor42/m-1-" + agent,
                null,
                true,
                false,
                "a",
                "b",
                "NOT_MERGED",
                1,
                List.of(),
                committed,
                uncommitted,
                0,
                false,
                List.of());
    }

    @Test
    void aFileCommittedThenChangedInTheWorktreeIsCopiedOnceAsTheWorktreeHasIt() {
        DeliveredBranch b = branch(
                "dev-1",
                List.of(file("notes.md", true, "A", false), file("report.pdf", true, "A", false)),
                List.of(file("notes.md", false, "M", false), file("out/build.pdf", false, "!", true)));
        List<DeliverablesHome.Item> items = DeliverablesHome.items(List.of(b), false);
        assertThat(items)
                .extracting(i -> i.file().path(), DeliverablesHome.Item::source)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("notes.md", Source.WORKTREE),
                        org.assertj.core.groups.Tuple.tuple("report.pdf", Source.COMMITTED));
        assertThat(DeliverablesHome.items(List.of(b), true))
                .extracting(i -> i.file().path())
                .containsExactly("notes.md", "out/build.pdf", "report.pdf");
    }

    @Test
    void twoAgentsWithTheSamePathAreTwoFiles() {
        List<DeliveredBranch> both = List.of(
                branch("dev-1", List.of(file("notes.md", true, "A", false)), List.of()),
                branch("dev-2", List.of(file("notes.md", true, "A", false)), List.of()));
        assertThat(DeliverablesHome.items(both, false)).hasSize(2);
    }
}
