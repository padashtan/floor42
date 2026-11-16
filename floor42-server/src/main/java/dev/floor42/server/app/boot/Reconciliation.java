package dev.floor42.server.app.boot;

import dev.floor42.domain.Action;
import dev.floor42.domain.ActionStage;
import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.Criticality;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.OptionCode;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Phrase;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.app.core.OfficeService;
import dev.floor42.server.app.workspace.Workspaces;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a stop in the middle of work left that the journal alone does not say (ADR-0076): a call authorized and never
 * reported, an approval whose hook died with the daemon, a worktree with changes no ticket in progress explains. Each
 * becomes one "To do" row for the director, once per key, never with an outcome the journal does not hold.
 */
@ApplicationScoped
public class Reconciliation {

    static final String UNKNOWN_OUTCOME = "recoveryOutcomeUnknown";
    static final String ORPHAN_APPROVAL = "recoveryApprovalOrphaned";
    static final String UNCLAIMED_WORKTREE = "recoveryWorktreeUnclaimed";

    private final OfficeService office;
    private final Workspaces workspaces;

    @Inject
    Reconciliation(OfficeService office, Workspaces workspaces) {
        this.office = office;
        this.workspaces = workspaces;
    }

    /** Called once the mission's open sessions are closed: nothing it finds can still be running. */
    public void reconcile(MissionId m) {
        List<Action> actions = office.query(c -> c.actions().of(m));
        outcomesUnknown(m, actions);
        orphanApprovals(m, actions);
        unclaimedWorktrees(m);
    }

    private void outcomesUnknown(MissionId m, List<Action> actions) {
        for (Action a : actions) {
            if (a.stage() != ActionStage.AUTHORIZED) continue;
            ask(
                    m,
                    UNKNOWN_OUTCOME,
                    a.id().value(),
                    a.ticket(),
                    "The outcome of " + a.tool() + " is unknown",
                    "Floor42 authorized this call by " + a.agent() + " and recorded no result: it may or may not"
                            + " have run. Check the work before relying on it.",
                    Map.of("agent", a.agent().value(), "tool", a.tool()));
        }
    }

    private void orphanApprovals(MissionId m, List<Action> actions) {
        for (Action a : actions) {
            if (a.stage() != ActionStage.REQUESTED) continue;
            Question q = office.query(c -> c.desk().of(m).stream()
                    .filter(x -> x.kind() == QuestionKind.ACTION_APPROVAL
                            && x.status() == QuestionStatus.OPEN
                            && a.id().equals(x.action()))
                    .findFirst()
                    .orElse(null));
            if (q == null) continue;
            ask(
                    m,
                    ORPHAN_APPROVAL,
                    q.id().value(),
                    q.ticket(),
                    "The approval of " + a.tool() + " waits for a call that no longer exists",
                    "The session of " + a.agent() + " that asked ended with the daemon: answering " + q.id()
                            + " cannot resume the call.",
                    Map.of("agent", a.agent().value(), "tool", a.tool()));
        }
    }

    private void unclaimedWorktrees(MissionId m) {
        Set<AgentId> claiming = office.query(c -> c.tickets().of(m).stream()
                .filter(t -> t.status() == TicketStatus.IN_PROGRESS || t.status() == TicketStatus.IN_REVIEW)
                .map(dev.floor42.domain.Ticket::assignee)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet()));
        workspaces.worktrees(m).forEach((agent, dir) -> {
            if (claiming.contains(new AgentId(agent))) return;
            // Unknown (git did not answer) is skipped, never read as clean or as dirty.
            int pending = workspaces.pendingIfKnown(dir).map(List::size).orElse(0);
            if (pending == 0) return;
            ask(
                    m,
                    UNCLAIMED_WORKTREE,
                    agent,
                    null,
                    "The worktree of " + agent + " holds changes no ticket explains",
                    pending + " changed path(s) in " + dir.getFileName() + ", and " + agent
                            + " has no ticket in progress. Nothing was committed or discarded.",
                    Map.of("agent", agent, "count", Integer.toString(pending)));
        });
    }

    private void ask(
            MissionId m,
            String code,
            String key,
            dev.floor42.domain.TicketId ticket,
            String subject,
            String context,
            Map<String, String> params) {
        boolean asked = office.query(c -> alreadyAsked(c, m, code, key));
        if (asked) return;
        String leave = OptionCode.LEAVE_IT.label();
        String[] pairs = new String[params.size() * 2 + 2];
        pairs[0] = "key";
        pairs[1] = key;
        int i = 2;
        for (var e : new java.util.TreeMap<>(params).entrySet()) {
            pairs[i++] = e.getKey();
            pairs[i++] = e.getValue();
        }
        office.execute(c -> c.desk()
                .ask(
                        office.context(Actor.system(), Origin.REAL),
                        m,
                        subject,
                        context,
                        List.of(leave),
                        leave,
                        Criticality.MEDIUM,
                        false,
                        ticket,
                        Phrase.of(code, pairs)));
    }

    /** True when this key was asked on the mission already, answered or not: a restart adds nothing. */
    private static boolean alreadyAsked(Company c, MissionId m, String code, String key) {
        return c.desk().of(m).stream()
                .map(Question::phrase)
                .anyMatch(p -> p != null
                        && code.equals(p.code())
                        && key.equals(p.params().get("key")));
    }
}
