package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.Criticality;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.OptionCode;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Phrase;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.Workspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Asks the director about a ticket that depends on work done on other branches, which Floor42 does not merge (D10). Not
 * a session: the ticket waits for the answer.
 */
@ApplicationScoped
public class DependencyDesk {

    private static final Logger LOG = Logger.getLogger(DependencyDesk.class);

    /** The question's phrase code: {@link Dependencies#wentOnWithout} reads only this question's answer. */
    static final String CODE = "dependencies";

    private final OfficeService office;
    private final HeldWork heldWork;

    @Inject
    DependencyDesk(OfficeService office, HeldWork heldWork) {
        this.office = office;
        this.heldWork = heldWork;
    }

    /** False if the question could not be asked: the dispatcher then stops looking, rather than ask in a loop. */
    boolean ask(MissionId m, TicketId id) {
        try {
            recordStranded(m, id);
            String context = office.query(c -> context(c, m, id));
            office.execute(c -> c.desk()
                    .ask(
                            office.context(Actor.system(), Origin.REAL),
                            m,
                            id + " builds on work done on other branches",
                            context,
                            List.of(OptionCode.GO_ON_WITHOUT.label(), OptionCode.DROP_TICKET.label()),
                            null,
                            Criticality.MEDIUM,
                            false,
                            id,
                            Phrase.of(CODE, "ticket", id.value())));
            return true;
        } catch (RuntimeException e) {
            // Handled: logged; the caller learns that nothing was asked
            LOG.warnf("Mission %s: cannot ask about the dependencies of %s: %s", m, id, e.getMessage());
            return false;
        }
    }

    /** Work a developer who left never had accepted cannot be merged: recorded as a failed merge, with its branches. */
    private void recordStranded(MissionId m, TicketId id) {
        Set<AgentId> held = heldWork.held(m).keySet();
        List<String> stranded = office.query(
                c -> Dependencies.strandedWork(c, c.tickets().find(id).orElseThrow(), held).stream()
                        .map(a -> c.workshop()
                                .workspace(m, a)
                                .map(Workspace::branch)
                                .orElse(a.value()))
                        .toList());
        if (stranded.isEmpty()) return;
        office.execute(c -> c.workshop()
                .recordMerge(
                        office.context(Actor.system(), Origin.REAL),
                        id,
                        null,
                        stranded,
                        false,
                        "work not accepted on " + String.join(", ", stranded) + " by a developer no longer on staff"));
    }

    private static String context(Company c, MissionId m, TicketId id) {
        Ticket t = c.tickets().find(id).orElseThrow();
        Set<AgentId> producers = Dependencies.producersElsewhere(c, t);
        List<String> lines = t.dependsOn().stream()
                .map(d -> c.tickets().find(d).orElseThrow())
                .filter(d -> producers.contains(d.assignee()))
                .map(d -> d.id() + " was done by " + d.assignee()
                        + c.workshop()
                                .workspace(m, d.assignee())
                                .map(w -> " on branch " + w.branch())
                                .orElse("")
                        + (Dependencies.onStaff(c, d.assignee()) ? "" : ", who is no longer on staff"))
                .toList();
        String failed = c.workshop().merge(id).equals(Optional.of(false))
                ? " Floor42 could not merge these branches into one worktree: see the event log."
                : "";
        return String.join("; ", lines) + "." + failed + " Whoever takes " + id + " works in their own worktree"
                + " and will not see the other work. Go on without it (the brief says what is missing), or drop"
                + " the ticket; you can also merge the branches yourself, then answer.";
    }
}
