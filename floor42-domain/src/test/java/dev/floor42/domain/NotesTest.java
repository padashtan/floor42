package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.EventEnvelope;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The director's improvement checklist: notes added, ticked, unticked and removed, all in the journal. */
@ExtendWith(GoldenFixtureExtension.class)
class NotesTest {

    final Fixture f = new Fixture();

    @Test
    void theDirectorKeepsAChecklist() {
        f.record(f.company.notebook().add(f.director(), "  The meeting room needs a timer  "));
        f.record(f.company.notebook().add(f.director(), "Show who is typing"));
        f.record(f.company.notebook().check(f.director(), "N-1", true));

        assertThat(f.company.notebook().notes()).extracting(Note::id).containsExactly("N-1", "N-2");
        assertThat(f.company.notebook().notes().getFirst().text()).isEqualTo("The meeting room needs a timer");
        assertThat(f.company.notebook().notes().getFirst().done()).isTrue();
        assertThat(f.company.notebook().notes().getFirst().doneAt()).isNotNull();
        assertThat(f.journal.getLast().mission()).as("a company-wide fact").isNull();

        f.record(f.company.notebook().check(f.director(), "N-1", false));
        assertThat(f.company.notebook().notes().getFirst().doneAt()).isNull();
        assertThat(f.company.notebook().check(f.director(), "N-1", false))
                .as("nothing to record")
                .isEmpty();
    }

    @Test
    void aRemovedNoteIsGoneAndItsIdIsNeverReused() {
        f.record(f.company.notebook().add(f.director(), "first"));
        f.record(f.company.notebook().remove(f.director(), "N-1"));
        f.record(f.company.notebook().add(f.director(), "second"));

        assertThat(f.company.notebook().notes()).extracting(Note::id).containsExactly("N-2");
        assertThat(Company.replay(f.journal).notebook().notes())
                .extracting(Note::text)
                .containsExactly("second");
        assertThatThrownBy(() -> f.company.notebook().check(f.director(), "N-1", true))
                .hasMessageContaining("No note N-1");
    }

    @Test
    void onlyTheDirectorWritesTheChecklist() {
        assertThatThrownBy(() -> f.company.notebook().add(f.agent(Fixture.CHRIS), "agents do not write here"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> f.company.notebook().add(f.director(), "  ")).isInstanceOf(DomainException.class);
    }

    @Test
    void aNoteBecomesATicketTheDirectorWroteAndStaysLinkedToIt() {
        MissionId m = f.runningMission();
        f.record(f.company.notebook().add(f.director(), "Click a request in the API console to see its body"));

        f.record(f.company.notebook().turnIntoTicket(f.director(), "N-1", m));

        Note note = f.company.notebook().notes().getFirst();
        Ticket ticket = f.company.tickets().find(note.ticket()).orElseThrow();
        assertThat(ticket.mission()).isEqualTo(m);
        assertThat(ticket.title()).isEqualTo("Click a request in the API console to see its body");
        assertThat(ticket.status()).as("written by the director: no approval").isEqualTo(TicketStatus.TODO);
        assertThat(note.done()).isFalse();
        assertThat(Company.replay(f.journal).notebook().notes().getFirst().ticket())
                .isEqualTo(note.ticket());
        assertThatThrownBy(() -> f.company.notebook().turnIntoTicket(f.director(), "N-1", m))
                .hasMessageContaining("already");
        assertThatThrownBy(() -> f.company.notebook().turnIntoTicket(f.agent(Fixture.CHRIS), "N-1", m))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void aNoteClosesIntoTheArchiveWithItsCommit() {
        MissionId m = f.runningMission();
        f.record(f.company.notebook().add(f.director(), "Show the payload of a request"));
        f.record(f.company.notebook().add(f.director(), "Done outside Floor42"));
        f.record(f.company.notebook().turnIntoTicket(f.director(), "N-1", m));

        assertThatThrownBy(() -> f.company.notebook().close(f.system(), "N-1", null))
                .as("Floor42 closes a note only once its ticket is done")
                .hasMessageContaining("only the director");
        assertThatThrownBy(() -> f.company.notebook().close(f.director(), "N-2", "not-a-commit"))
                .hasMessageContaining("hex");

        List<EventEnvelope> events = f.record(f.company.notebook().close(f.director(), "N-2", "c01951e"));

        assertThat(events).extracting(EventEnvelope::type).containsExactly("NoteChecked", "NoteCommitted");
        Note closed = Company.replay(f.journal).notebook().notes().get(1);
        assertThat(closed.done()).isTrue();
        assertThat(closed.commit()).isEqualTo("c01951e");
        assertThat(f.company.notebook().close(f.director(), "N-2", "c01951e"))
                .as("nothing new")
                .isEmpty();
    }
}
