package dev.floor42.server.journal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Company;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sizing (docs/backups.md): a journal of 100,000 events, written then replayed as the daemon does at its start. The
 * figures are printed ("REPLAY-100K ...") so that the doc quotes a measure, not a guess. The bounds are generous: a
 * machine ten times slower still passes; a replay that went quadratic, or kept a hundred times more heap, would not.
 */
@Tag("slow")
class ReplayVolumeTest {

    static final int EVENTS = 100_000;

    @TempDir
    Path tmp;

    private static long usedHeapMb() {
        System.gc();
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) >> 20;
    }

    @Test
    void replaysAHundredThousandEventsInSecondsAndWithinHeap() throws IOException {
        Path file = tmp.resolve("floor42.db");
        SqliteJournal journal = TestJournals.open(file);
        String objective = "Add the export of the quarterly report to the billing service, with tests and the "
                + "migration of the existing rows; keep the public API unchanged and document the new option. "
                + "Plan: read the module, change the query, cover the edge cases, update the documentation.";
        long writeStart = System.nanoTime();
        for (int from = 0; from < EVENTS; from += 1000) {
            List<EventEnvelope> batch = new ArrayList<>(1000);
            for (int i = from; i < from + 1000; i += 2) {
                String id = "M-" + (i / 2);
                batch.add(TestJournals.envelope(
                        id, new MissionEvent.MissionCreated(new MissionId(id), "Mission " + i, objective, "C:/repo")));
                batch.add(TestJournals.completed(id));
            }
            journal.append(batch);
        }
        long writeMs = (System.nanoTime() - writeStart) / 1_000_000;
        long mb = Files.size(file) >> 20;

        long before = usedHeapMb();
        long start = System.nanoTime();
        Journal.Read read = journal.replay();
        long decodeMs = (System.nanoTime() - start) / 1_000_000;
        Company company = Company.replay(
                read.entries().stream().map(Journal.Entry::envelope).toList());
        long replayMs = (System.nanoTime() - start) / 1_000_000;
        long heapMb = usedHeapMb() - before;

        System.out.printf(
                "REPLAY-100K events=%d file=%d MB write=%d ms decode=%d ms decode+apply=%d ms heap-held=%d MB%n",
                read.entries().size(), mb, writeMs, decodeMs, replayMs, heapMb);
        assertThat(read.entries()).hasSize(EVENTS);
        assertThat(read.unreadable()).isEmpty();
        assertThat(company).isNotNull();
        assertThat(replayMs).as("replay of 100k events, ms").isLessThan(60_000);
        assertThat(heapMb).as("heap held by 100k replayed events, MB").isLessThan(2_048);
    }
}
