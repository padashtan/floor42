package dev.floor42.domain.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Readers ship one release before their writers (ADR-0052, the director's answer 4): a schema version is written only
 * by a release after the one whose reader first read it, so a rollback by one release still runs every mission it
 * pinned. The release under construction is the project's version: raising {@link WorkflowDocument#WRITES} in the same
 * release as its reader fails here.
 *
 * <p>One exception, the director's explicit choice of 2026-11-04 (ADR-0062): schema version 4 (the graph mode) has its
 * reader and its writer in the same release, 1.16.0. A rollback to 1.15 then shows a graph mission as unrunnable but
 * readable ({@code WorkflowDocumentV4Test}). The exception is listed in {@link #SAME_RELEASE}; no other version may be
 * in it.
 */
class ReadersBeforeWritersTest {

    static final Workflow CHECKED = WorkflowDocumentV2Test.CHECKED;
    static final Workflow TASKED = WorkflowDocumentV3Test.TASKED;

    /** Versions whose reader and writer ship together, by the director's decision (ADR-0062, 2026-11-04). */
    static final Set<Integer> SAME_RELEASE = Set.of(4);

    @Test
    void everyVersionWrittenHadItsReaderShippedInAnEarlierRelease() throws IOException {
        int[] building = release(projectVersion());
        assertThat(WorkflowDocument.READER_SHIPPED.keySet()).containsExactlyInAnyOrder(1, 2, 3, 4);
        assertThat(WorkflowDocument.READS).isEqualTo(4);
        for (int v = 1; v <= WorkflowDocument.WRITES; v++) {
            int[] shipped = release(WorkflowDocument.READER_SHIPPED.get(v));
            if (SAME_RELEASE.contains(v)) {
                assertThat(Arrays.compare(shipped, building))
                        .as("version %d ships its reader and its writer together, in this release or an earlier one", v)
                        .isLessThanOrEqualTo(0);
                continue;
            }
            assertThat(Arrays.compare(shipped, building))
                    .as(
                            "version %d is written by %s, but its reader ships in %s",
                            v, projectVersion(), WorkflowDocument.READER_SHIPPED.get(v))
                    .isNegative();
        }
        // The reader of the highest version is in this release or an earlier one.
        assertThat(Arrays.compare(release(WorkflowDocument.READER_SHIPPED.get(WorkflowDocument.READS)), building))
                .isLessThanOrEqualTo(0);
    }

    /** The writer writes version 2 when the workflow has a check, and version 1 byte for byte otherwise. */
    @Test
    void checksAreWrittenAsVersionTwoAndReadBackEqual() {
        WorkflowDocument.Written w = WorkflowDocument.forJournal(CHECKED);
        assertThat(w.schemaVersion()).isEqualTo(2);
        assertThat(WorkflowDocument.read(w.document(), 2)).isEqualTo(new WorkflowDocument.Reading.Read(CHECKED));
        assertThat(WorkflowDocument.forJournal(Workflow.BUILT_IN))
                .isEqualTo(new WorkflowDocument.Written(WorkflowDocument.write(Workflow.BUILT_IN), 1));
    }

    /** 1.16 writes version 3 for a task and reads it back equal; the guard still bounds every write. */
    @Test
    void tasksAreWrittenAsVersionThreeAndReadBackEqual() {
        assertThat(WorkflowDocument.READS).isEqualTo(4);
        assertThat(WorkflowDocument.WRITES).isEqualTo(4);
        assertThat(WorkflowDocument.writable(TASKED)).isTrue();
        WorkflowDocument.Written w = WorkflowDocument.forJournal(TASKED);
        assertThat(w.schemaVersion()).isEqualTo(3);
        assertThat(WorkflowDocument.read(w.document(), 3)).isEqualTo(new WorkflowDocument.Reading.Read(TASKED));
        assertThat(WorkflowDocument.forJournal(CHECKED).schemaVersion()).isEqualTo(2);
        assertThat(WorkflowDocument.writableVersion()).isEqualTo(WorkflowDocument.WRITES);
    }

    static String projectVersion() throws IOException {
        Matcher m = Pattern.compile("<artifactId>floor42-parent</artifactId>\\s*<version>([^<]+)</version>")
                .matcher(Files.readString(Path.of("..", "pom.xml")));
        assertThat(m.find()).as("the parent's version in pom.xml").isTrue();
        return m.group(1);
    }

    /** 1.14.0-SNAPSHOT is release 1.14.0: the one under construction. */
    static int[] release(String version) {
        // An internal release came before the public numbering started again at 1.0.0: earlier than any public one.
        if (version.startsWith("internal ")) return new int[] {-1};
        // A prerelease or snapshot (1.0.0-rc.1, 1.1.0-SNAPSHOT) is ordered as its release.
        return Arrays.stream(version.replaceFirst("-.*$", "").split("\\."))
                .mapToInt(Integer::parseInt)
                .toArray();
    }
}
