package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Company;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Origin;
import dev.floor42.domain.TicketStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Closes each note whose ticket is done into the archive, and adds the commit the director accepted for it in the
 * workshop once there is one. Read from the journal, so a restart misses nothing the next event will not catch up.
 */
@ApplicationScoped
public class NoteArchive {

    private static final Logger LOG = Logger.getLogger(NoteArchive.class);

    private final OfficeService office;

    @Inject
    NoteArchive(OfficeService office) {
        this.office = office;
    }

    private record Due(String note, String commit) {}

    void closeImplemented() {
        List<Due> due = office.query(NoteArchive::due);
        for (Due d : due) {
            try {
                office.execute(
                        c -> c.notebook().close(office.context(Actor.system(), Origin.REAL), d.note(), d.commit()));
            } catch (DomainException e) {
                // Handled: logged; the note stays open for the next pass
                LOG.infof("Note %s not closed: %s", d.note(), e.getMessage());
            }
        }
    }

    private static List<Due> due(Company c) {
        return c.notebook().notes().stream()
                .filter(n -> n.ticket() != null)
                .filter(n -> c.tickets()
                        .find(n.ticket())
                        .map(t -> t.status() == TicketStatus.DONE)
                        .orElse(false))
                .map(n -> new Due(n.id(), c.queries().commitFor(n.ticket())))
                .filter(d -> {
                    var n = c.notebook().notes().stream()
                            .filter(x -> x.id().equals(d.note()))
                            .findFirst()
                            .orElseThrow();
                    return !n.done() || (d.commit() != null && !d.commit().equals(n.commit()));
                })
                .toList();
    }
}
