package dev.floor42.server.app;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Committee;
import dev.floor42.domain.CommitteeRef;
import dev.floor42.domain.Company;
import dev.floor42.domain.Criticality;
import dev.floor42.domain.Decision;
import dev.floor42.domain.DefaultPermissions;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.PermissionRule;
import dev.floor42.domain.Phrase;
import dev.floor42.domain.Priority;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.WorkRole;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.server.runtime.ToolCatalog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The director adopted the committee's decision and asked for it to be implemented: Floor42 plans one implementation
 * ticket for the producer, whose brief carries the decision record. That ticket works under the director's default
 * table as it stands at the adoption (a snapshot, as a new mission's table is): one ticket line per tool where it
 * differs from the committee's read-only table, which the rest of the mission keeps (D15). A line the director set
 * for the mission is left to decide.
 */
@ApplicationScoped
public class Implementation {

    private static final Logger LOG = Logger.getLogger(Implementation.class);

    private final OfficeService office;
    private final StaffProvisioner staff;
    private final MissionPermissions permissions;
    private final Dispatcher dispatcher;

    @Inject
    Implementation(
            OfficeService office, StaffProvisioner staff, MissionPermissions permissions, Dispatcher dispatcher) {
        this.office = office;
        this.staff = staff;
        this.permissions = permissions;
        this.dispatcher = dispatcher;
    }

    /** @param ref the committee whose decision was adopted, as the workflow interpreter named it (ADR-0046) */
    void implement(CommitteeRef ref) {
        MissionId m = ref.mission();
        try {
            String what = office.query(c -> {
                // Read under the lock: the committee's contributions are a live list.
                Committee k = c.committees().find(ref).orElseThrow();
                Committee.Contribution record = k.synthesis();
                return record != null && record.proposal() != null ? record.proposal() : k.topic();
            });
            AgentId developer = staff.staff(WorkRole.DEVELOPER);
            List<AgentId> reviewers = staff.reviewers();
            denyReviewers(m, reviewers);
            TicketId t = planWithWriting(m, what);
            office.execute(c -> c.tickets().assign(office.context(Actor.system(), Origin.REAL), t, developer));
            // A proposed ticket waits for the director's approval; one already approved starts now.
            dispatcher.produce(m);
        } catch (RuntimeException e) {
            // Handled: logged; the director is told
            LOG.errorf(e, "Mission %s: the implementation could not be planned", m);
            tellTheDirector(m, e);
        }
    }

    /** Reviewers change nothing, whatever a ticket allows: a rule naming them outranks a ticket's rule. */
    private void denyReviewers(MissionId m, List<AgentId> reviewers) {
        for (ActionCategory category : MissionPermissions.CHANGES) {
            for (AgentId reviewer : reviewers) permissions.denyUnlessRuled(m, reviewer, category);
        }
    }

    /**
     * The ticket and its lines, in one command: nobody ever sees the implementation ticket under the committee's
     * read-only table, and no other ticket of the mission may write (D15). Floor42 records the lines (the system
     * actor): the director's later choice for a tool replaces them (MissionBook.setToolPermission).
     */
    private TicketId planWithWriting(MissionId m, String what) {
        List<EventEnvelope> events = office.execute(c -> {
            CommandContext floor42 = office.context(Actor.system(), Origin.REAL);
            List<EventEnvelope> all = new ArrayList<>(c.tickets()
                    .create(
                            floor42,
                            m,
                            "Implement: " + what,
                            "Implement the decision adopted by the director after the architecture committee.",
                            Priority.NORMAL,
                            List.of(),
                            CRITERIA));
            TicketId t = ((TicketCreated) all.getFirst().event()).ticket();
            for (PermissionRule rule : defaultsFor(c, m, t)) {
                all.addAll(c.missions().addPermission(floor42, m, rule));
            }
            return all;
        });
        return ((TicketCreated) events.getFirst().event()).ticket();
    }

    /**
     * The director's default line of each tool, for ticket {@code t}, where the mission's line says otherwise; a line
     * the director set for the mission stays the director's choice and gets no ticket rule (DefaultPermissions).
     */
    static List<PermissionRule> defaultsFor(Company c, MissionId m, TicketId t) {
        Mission mission = c.missions().find(m).orElseThrow();
        List<PermissionRule> rules = new ArrayList<>();
        for (String tool : ToolCatalog.GOVERNED_TOOLS) {
            ActionCategory category = ToolCatalog.category(tool);
            Decision wanted = DefaultPermissions.forImplementation(
                    mission, tool, category, MissionPermissions.defaultFor(c, tool));
            Decision line =
                    mission.policy().evaluate(null, category, tool, null).decision();
            if (wanted != line) rules.add(PermissionRule.forTicket(t, tool, category, wanted));
        }
        return rules;
    }

    private static final List<String> CRITERIA = List.of(
            "The change follows the adopted decision record",
            "Every claim is backed by a file or a command output, or marked as an assumption",
            "Every file changed is listed in the report");

    /** The director adopted the decision: they hear why nothing follows, instead of a silent log line. */
    private void tellTheDirector(MissionId m, RuntimeException e) {
        office.execute(c -> c.desk()
                .ask(
                        office.context(Actor.system(), Origin.REAL),
                        m,
                        "Floor42 could not plan the implementation",
                        String.valueOf(e.getMessage()),
                        List.of(),
                        null,
                        Criticality.HIGH,
                        false,
                        null,
                        Phrase.of("planFailed")));
    }
}
