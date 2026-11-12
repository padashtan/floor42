package dev.floor42.server.app;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.ConsultationKind;
import dev.floor42.domain.Decision;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.PermissionRule;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Who answers a reviewer's request, and how: a peer reads the producer's worktree and changes nothing; a research
 * session only gets the question and web tools, never the repository, and runs only if the mission's permission table
 * allows web tools (each call then governed as usual). When no session can run, Floor42 answers itself.
 */
@ApplicationScoped
public class HelperSessions {

    static final List<String> WEB_TOOLS = List.of("WebSearch", "WebFetch");

    private final OfficeService office;
    private final AgentWorktrees worktrees;
    private final StaffProvisioner staff;
    private final MissionPermissions permissions;

    @Inject
    HelperSessions(
            OfficeService office, AgentWorktrees worktrees, StaffProvisioner staff, MissionPermissions permissions) {
        this.office = office;
        this.worktrees = worktrees;
        this.staff = staff;
        this.permissions = permissions;
    }

    /** What answers the request: a session to run, or Floor42's own answer. */
    sealed interface Helper {}

    /** {@code temporary} are the rules added for this consultation only: removed once it ends. */
    record Session(SessionSpec spec, List<PermissionRule> temporary) implements Helper {}

    record Answer(String text) implements Helper {}

    /** The request as asked, read under the office monitor: a ticket's consultations are a live list. */
    record Request(MissionId mission, TicketId ticket, AgentId producer, Ticket.Consultation consultation) {}

    Helper prepare(Request r, AgentId helper) {
        return r.consultation().kind() == ConsultationKind.CONSULT ? peer(r, helper) : research(r, helper);
    }

    private Helper peer(Request r, AgentId helper) {
        CommitteeRoles.Role role = CommitteeRoles.all().stream()
                .filter(x -> x.key().equals(r.consultation().role()))
                .findFirst()
                .orElse(null);
        if (role != null && helper == null && !role.agent().equals(r.producer())) {
            return new Answer(role.name() + " has left the company. Conclude by checking yourself.");
        }
        if (role == null)
            return new Answer("No such role: " + r.consultation().role() + ". Conclude by checking yourself.");
        if (role.agent().equals(r.producer())) {
            return new Answer(role.name() + " produced this ticket and cannot advise its reviewer. Conclude by checking"
                    + " yourself.");
        }
        staff.hireIfMissing(role.agent(), role.name(), role.title(), role.model(), role.effort());
        // A colleague consulted reads only, for this consultation: its own work on the mission is not changed by it.
        List<PermissionRule> temporary =
                new ArrayList<>(permissions.denyAll(r.mission(), role.agent(), MissionPermissions.CHANGES));
        try {
            return new Session(peerSpec(r, role.agent()), temporary);
        } catch (RuntimeException e) {
            temporary.forEach(rule -> permissions.remove(r.mission(), rule));
            throw e;
        }
    }

    private SessionSpec peerSpec(Request r, AgentId consultant) {
        Path worktree = worktrees.path(r.mission(), r.producer());
        return office.query(c -> {
            Agent a = c.staff().find(consultant).orElseThrow();
            Mission mission = c.missions().find(r.mission()).orElseThrow();
            Ticket ticket = c.tickets().find(r.ticket()).orElseThrow();
            String brief = Briefs.consultant(c, mission, ticket, a, r.consultation());
            return SessionGate.spec(c, r.mission(), r.ticket(), a, worktree, brief, ReviewRunner.REVIEW_TOOLS);
        });
    }

    private Helper research(Request r, AgentId researcher) {
        if (researcher == null) {
            return new Answer("Research not run: no researcher on staff. Conclude without it, or say what you could not"
                    + " verify.");
        }
        if (researcher.equals(StaffProvisioner.RESEARCHER)) staff.ensureResearcher();
        permissions.denyAll(
                r.mission(), researcher, List.of(ActionCategory.READ, ActionCategory.WRITE, ActionCategory.EXECUTE));
        List<String> web = office.query(c -> WEB_TOOLS.stream()
                .filter(tool -> c.missions()
                                .find(r.mission())
                                .orElseThrow()
                                .policy()
                                .evaluate(researcher, ActionCategory.NETWORK, tool, null)
                                .decision()
                        != Decision.DENY)
                .toList());
        if (web.isEmpty()) {
            return new Answer("Research not run: the mission's permission table denies WebSearch and WebFetch."
                    + " Conclude without it, or say what you could not verify.");
        }
        Path empty = worktrees.research(r.mission());
        SessionSpec spec = office.query(c -> {
            Agent a = c.staff().find(researcher).orElseThrow();
            return SessionGate.spec(c, r.mission(), r.ticket(), a, empty, Briefs.researcher(a, r.consultation()), web);
        });
        return new Session(spec, List.of());
    }
}
