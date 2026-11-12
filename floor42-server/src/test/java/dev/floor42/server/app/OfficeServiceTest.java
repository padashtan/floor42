package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Origin;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.journal.JournalException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plain unit test: the failure path is easier to provoke with an in-memory journal. */
class OfficeServiceTest {

    static final class MemoryJournal implements Journal {
        final List<EventEnvelope> stored = new ArrayList<>();
        boolean failNextAppend;

        @Override
        public void append(List<EventEnvelope> events) {
            if (failNextAppend) {
                failNextAppend = false;
                throw new JournalException("disk full", null);
            }
            stored.addAll(events);
        }

        boolean failNextRead;

        @Override
        public List<EventEnvelope> readAll() {
            if (failNextRead) {
                failNextRead = false;
                throw new JournalException("disk gone", null);
            }
            return List.copyOf(stored);
        }

        @Override
        public List<Entry> latest(int limit) {
            return List.of();
        }

        @Override
        public List<Entry> since(long sequence, int limit) {
            return List.of();
        }
    }

    final MemoryJournal journal = new MemoryJournal();
    final OfficeService office =
            new OfficeService(journal, Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void aStoredCommandIsVisibleAndPersisted() {
        office.execute(c -> c.missions().create(office.context(Actor.director(), Origin.SIMULATED), "t", "o"));

        int missions = office.query(c -> c.missions().all().size());
        assertThat(missions).isEqualTo(1);
        assertThat(journal.stored).hasSize(1);
    }

    @Test
    void whenTheJournalFailsTheCompanyForgetsWhatWasNotStored() {
        office.execute(c -> c.missions().create(office.context(Actor.director(), Origin.SIMULATED), "kept", "o"));
        journal.failNextAppend = true;

        assertThatThrownBy(() -> office.execute(
                        c -> c.missions().create(office.context(Actor.director(), Origin.SIMULATED), "lost", "o")))
                .isInstanceOf(JournalException.class);

        List<String> titles = office.query(
                c -> c.missions().all().stream().map(m -> m.title()).toList());
        long version = office.query(c -> c.version());
        assertThat(titles).containsExactly("kept");
        assertThat(version).isEqualTo(journal.stored.size());
    }

    /** A command that emits, then fails: what it applied was never journaled. */
    List<EventEnvelope> emitsThenFails(dev.floor42.domain.Company c) {
        c.missions().create(office.context(Actor.director(), Origin.SIMULATED), "half-done", "o");
        throw new dev.floor42.domain.DomainException("REFUSED", "refused after emitting");
    }

    @Test
    void aCommandThatFailsAfterEmittingLeavesTheJournalsState() {
        office.execute(c -> c.missions().create(office.context(Actor.director(), Origin.SIMULATED), "kept", "o"));

        assertThatThrownBy(() -> office.execute(this::emitsThenFails))
                .isInstanceOf(dev.floor42.domain.DomainException.class)
                .hasMessageContaining("refused after emitting");

        List<String> titles = office.query(
                c -> c.missions().all().stream().map(m -> m.title()).toList());
        assertThat(titles).containsExactly("kept");
        long version = office.query(c -> c.version());
        assertThat(version).isEqualTo(journal.stored.size());
    }

    @Test
    void inTestsTheRollbackFailsLoudly() {
        office.failOnRollback = true;

        assertThatThrownBy(() -> office.execute(this::emitsThenFails))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("check before emitting");
        int missions = office.query(c -> c.missions().all().size());
        assertThat(missions).isZero();
    }

    @Test
    void aRebuildThatFailsKeepsTheCommandsOwnError() {
        office.execute(c -> c.missions().create(office.context(Actor.director(), Origin.SIMULATED), "kept", "o"));
        journal.failNextRead = true;

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> office.execute(this::emitsThenFails));

        assertThat(thrown).isInstanceOf(dev.floor42.domain.DomainException.class);
        assertThat(thrown.getSuppressed()).singleElement().isInstanceOf(JournalException.class);
    }

    @Test
    void whenAppendAndRebuildBothFailNoCommandRunsUntilARebuildWorks() {
        office.execute(c -> c.missions().create(office.context(Actor.director(), Origin.SIMULATED), "kept", "o"));
        journal.failNextAppend = true;
        journal.failNextRead = true;

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> office.execute(
                c -> c.missions().create(office.context(Actor.director(), Origin.SIMULATED), "lost", "o")));

        assertThat(thrown.getMessage()).contains("disk full");
        assertThat(thrown.getSuppressed())
                .singleElement()
                .satisfies(s -> assertThat(s.getMessage()).contains("disk gone"));

        journal.failNextRead = true;
        assertThatThrownBy(() -> office.execute(c -> List.of()))
                .isInstanceOf(JournalException.class)
                .hasMessageContaining("out of sync");

        office.execute(c -> c.missions().create(office.context(Actor.director(), Origin.SIMULATED), "next", "o"));
        List<String> titles = office.query(
                c -> c.missions().all().stream().map(m -> m.title()).toList());
        assertThat(titles).containsExactlyInAnyOrder("kept", "next");
    }

    @Test
    void aRefusalBeforeAnyEventNeedsNoRollback() {
        office.failOnRollback = true;

        assertThatThrownBy(() -> office.execute(c -> {
                    throw new dev.floor42.domain.DomainException("REFUSED", "nothing emitted");
                }))
                .isInstanceOf(dev.floor42.domain.DomainException.class);
    }

    @Test
    void theCompanyIsRestoredFromTheJournalAtStartup() {
        office.execute(c -> c.missions()
                .create(office.context(Actor.director(), Origin.REAL), "t", "o", "C:/repo", Workflow.BUILT_IN));

        OfficeService restarted = new OfficeService(journal, Clock.systemUTC());

        Origin origin =
                restarted.query(c -> c.missions().all().iterator().next().origin());
        assertThat(origin).isEqualTo(Origin.REAL);
    }
}
