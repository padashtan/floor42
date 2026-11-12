package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.CommitteeEvent.CommitteeAdvanced;
import dev.floor42.domain.event.CommitteeEvent.CommitteeArbitrated;
import dev.floor42.domain.event.CommitteeEvent.CommitteeConvened;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.EventEnvelope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A mission keeps its committees in a numbered list (ADR-0046): replay never overwrites one with the next. */
class NumberedCommitteesTest {

    private static final MissionId M = new MissionId("M-1");
    private final List<EventEnvelope> journal = new ArrayList<>();

    @Test
    void eventsWithoutANumberAreTheMissionsFirstCommittee() {
        record(convened("T-1", null));
        record(new CommitteeAdvanced(M, Committee.Stage.DEBATE, 2, null));

        Company c = Company.replay(journal);

        assertThat(c.committees().of(M)).hasSize(1);
        Committee only = c.committees().latest(M).orElseThrow();
        assertThat(only.number()).isEqualTo(1);
        assertThat(only.ref()).isEqualTo(new CommitteeRef(M, 1));
        assertThat(only.stage()).isEqualTo(Committee.Stage.DEBATE);
        assertThat(c.committees().find(new CommitteeRef(M, 2))).isEmpty();
    }

    @Test
    void aSecondCommitteeIsKeptBesideTheFirstAndEachEventReachesItsOwn() {
        record(convened("T-1", 1));
        record(new CommitteeArbitrated(M, true, false, false, 1));
        record(convened("T-2", 2));
        record(new CommitteeAdvanced(M, Committee.Stage.DEBATE, 2, 2));

        Company c = Company.replay(journal);

        assertThat(c.committees().of(M)).extracting(Committee::number).containsExactly(1, 2);
        assertThat(c.committees().find(new CommitteeRef(M, 1)).orElseThrow().stage())
                .isEqualTo(Committee.Stage.ADOPTED);
        Committee latest = c.committees().latest(M).orElseThrow();
        assertThat(latest.number()).isEqualTo(2);
        assertThat(latest.ticket()).isEqualTo(new TicketId("T-2"));
        assertThat(latest.stage()).isEqualTo(Committee.Stage.DEBATE);
    }

    /** Journals before 1.8.0 knew one committee per mission: a second convening replaced the first. */
    @Test
    void aNumberConvenedAgainReplacesItsCommitteeAsBefore() {
        record(convened("T-1", null));
        record(convened("T-9", null));

        Company c = Company.replay(journal);

        assertThat(c.committees().of(M)).hasSize(1);
        assertThat(c.committees().latest(M).orElseThrow().ticket()).isEqualTo(new TicketId("T-9"));
    }

    @Test
    void anEventForACommitteeNeverConvenedStopsTheReplay() {
        record(convened("T-1", 1));
        record(new CommitteeAdvanced(M, Committee.Stage.DEBATE, 2, 2));

        assertThatThrownBy(() -> Company.replay(journal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("M-1 has no committee 2");
    }

    @Test
    void aGapInTheNumbersStopsTheReplay() {
        record(convened("T-1", 1));
        record(convened("T-3", 3));

        assertThatThrownBy(() -> Company.replay(journal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("convenes committee 3 after 1");
    }

    @Test
    void aCommitteeNumberStartsAtOne() {
        assertThatThrownBy(() -> new CommitteeRef(M, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static CommitteeConvened convened(String ticket, Integer no) {
        return new CommitteeConvened(
                M,
                new TicketId(ticket),
                "Which build?",
                List.of("ada", "bob"),
                List.of("chair", "security"),
                null,
                null,
                null,
                null,
                no);
    }

    private void record(DomainEvent event) {
        journal.add(new EventEnvelope(
                "e-" + journal.size(),
                Instant.parse("2026-11-01T10:00:00Z").plusSeconds(journal.size()),
                Origin.REAL,
                Actor.system(),
                M,
                null,
                null,
                event));
    }
}
