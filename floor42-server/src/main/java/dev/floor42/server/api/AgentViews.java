package dev.floor42.server.api;

import dev.floor42.domain.Agent;
import dev.floor42.domain.Company;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.WorkRole;
import dev.floor42.server.app.CommitteeRoles;
import dev.floor42.server.app.Dismissals;
import dev.floor42.server.app.Staff;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read models of the staff: the agents present, and "dismiss everyone except the core members". */
public final class AgentViews {

    private AgentViews() {}

    public record AgentView(
            String id,
            String name,
            String role,
            String provider,
            String model,
            String effort,
            String status,
            boolean suspended,
            String workRole,
            boolean atWork,
            boolean stays,
            boolean core) {
        // atWork: a session of the agent is open, it cannot be dismissed until that session ends.
        // stays: a chair of Floor42's committees, never dismissed; nobody can be hired in their place.
        // core: starred by the director as a core member; "dismiss everyone except the core members" keeps it.
        static AgentView of(Company c, Agent a) {
            WorkRole role = Staff.roleOf(a);
            return new AgentView(
                    a.id().value(),
                    a.displayName(),
                    a.role(),
                    a.provider(),
                    a.model(),
                    a.effort(),
                    c.queries().statusOf(a.id()).name(),
                    a.suspended(),
                    role == null ? null : role.name(),
                    c.queries().atWork(a.id()),
                    CommitteeRoles.chair(a.id()),
                    a.core());
        }
    }

    /**
     * One agent in the preview of "dismiss everyone except the core members". fate: LEAVES, STAYS (a core member) or
     * BLOCKED (cannot leave now; code and reason say why, as a single dismissal would be refused). handsBack: the
     * tickets it had started, which go back to the queue if it leaves.
     */
    public record CoreDismissalEntry(
            String id, String name, String role, String fate, String code, String reason, List<String> handsBack) {
        public CoreDismissalEntry {
            handsBack = handsBack == null ? null : Collections.unmodifiableList(new ArrayList<>(handsBack));
        }

        static CoreDismissalEntry of(Dismissals.Entry e) {
            return new CoreDismissalEntry(
                    e.agent().id().value(),
                    e.agent().displayName(),
                    e.agent().role(),
                    e.fate().name(),
                    e.refusal() == null ? null : e.refusal().code(),
                    e.refusal() == null ? null : e.refusal().message(),
                    ids(e.handsBack()));
        }
    }

    public record CoreDismissalPreview(List<CoreDismissalEntry> agents) {
        public CoreDismissalPreview {
            agents = agents == null ? null : Collections.unmodifiableList(new ArrayList<>(agents));
        }
    }

    /** What happened to one confirmed agent. result: DISMISSED, REFUSED (code, message) or SKIPPED (code, message). */
    public record CoreDismissalOutcome(
            String id, String name, String result, String code, String message, List<String> handsBack) {
        public CoreDismissalOutcome {
            handsBack = handsBack == null ? null : Collections.unmodifiableList(new ArrayList<>(handsBack));
        }

        static CoreDismissalOutcome of(Dismissals.Outcome o) {
            return new CoreDismissalOutcome(
                    o.agent().value(), o.name(), o.result().name(), o.code(), o.message(), ids(o.handsBack()));
        }
    }

    public record CoreDismissalReport(List<CoreDismissalOutcome> outcomes) {
        public CoreDismissalReport {
            outcomes = outcomes == null ? null : Collections.unmodifiableList(new ArrayList<>(outcomes));
        }
    }

    private static List<String> ids(List<TicketId> tickets) {
        return tickets.stream().map(TicketId::value).toList();
    }
}
